package com.example.miniagent.plugin.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 같은 세션 파일에 여러 스레드와 다른 프로세스가 동시에 덧붙여도 기록이 끊기거나 섞이거나 사라지지 않는다.
 * 같은 JVM 안에서는 두 번째 파일 잠금이 막히지 않고 예외를 던지므로, 다른 프로세스는 자식 JVM 으로 만든다.
 * POSIX 에서는 같은 파일의 기술자를 하나라도 닫으면 이 프로세스의 잠금이 모두 풀리므로, 쓰는 쪽이 잠금을 쥔 동안
 * 같은 JVM 의 다른 접근은 그 파일을 열지 않고 기다려야 한다.
 */
class JsonlConcurrentAppendTest {

	private static final int LIMIT_SECONDS = 60;

	/** 한 번에 쓰는 줄을 길게 만들어, 잠금이 없으면 쓰기가 섞일 가능성을 키운다. */
	private static final String PADDING = "-".repeat(1500);

	@TempDir
	Path dir;

	@Test
	void eightThreadsAppendingToOneSessionKeepEveryRecordWholeAndInOrder() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.dir);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Future<?>> writers = new ArrayList<>();
			for (int thread = 0; thread < 8; thread++) {
				int writer = thread;
				writers.add(executor.submit(() -> {
					start.await();
					appendPairs(repository, "shared", writer, 50);
					return null;
				}));
			}
			start.countDown();
			for (Future<?> writer : writers) {
				writer.get(LIMIT_SECONDS, TimeUnit.SECONDS);
			}
		}

		assertPairsWholeAndInOrder(repository.findByConversationId("shared"),
				Map.of(0, 50, 1, 50, 2, 50, 3, 50, 4, 50, 5, 50, 6, 50, 7, 50));
	}

	@Test
	void appendWaitsUntilAnotherProcessReleasesTheSessionLock() throws Exception {
		Files.createDirectories(this.dir);
		Path file = this.dir.resolve("locked.jsonl");
		var repository = new JsonlChatMemoryRepository(this.dir);
		try (var executor = Executors.newSingleThreadExecutor();
				var holder = Child.launch("hold", file.toString())) {
			holder.awaitLine("LOCKED");

			Future<?> append = executor.submit(() -> repository.append("locked", List.of(new UserMessage("after release"))));

			assertThatThrownBy(() -> append.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			assertThat(file).isEmptyFile();
			holder.send("RELEASE");
			append.get(LIMIT_SECONDS, TimeUnit.SECONDS);
			assertThat(holder.awaitExit()).isZero();
		}

		assertThat(repository.findByConversationId("locked")).extracting(Message::getText)
			.containsExactly("after release");
	}

	@Test
	void threadsAndAnotherProcessAppendingToOneSessionKeepEveryRecordWholeAndInOrder() throws Exception {
		var repository = new JsonlChatMemoryRepository(this.dir);
		try (var executor = Executors.newFixedThreadPool(4);
				var other = Child.launch("append", this.dir.toString(), "shared", "9", "100")) {
			other.awaitLine("READY");
			var start = new CountDownLatch(1);
			List<Future<?>> writers = new ArrayList<>();
			for (int thread = 0; thread < 4; thread++) {
				int writer = thread;
				writers.add(executor.submit(() -> {
					start.await();
					appendPairs(repository, "shared", writer, 25);
					return null;
				}));
			}
			other.send("GO");
			start.countDown();
			for (Future<?> writer : writers) {
				writer.get(LIMIT_SECONDS, TimeUnit.SECONDS);
			}
			assertThat(other.awaitExit()).isZero();
		}

		assertPairsWholeAndInOrder(repository.findByConversationId("shared"),
				Map.of(0, 25, 1, 25, 2, 25, 3, 25, 9, 100));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("accessesWhileLocked")
	void anotherAccessInThisJvmWaitsWhileAWriterHoldsTheSessionLock(
			Consumer<JsonlChatMemoryRepository> operation, List<String> stored) throws Exception {
		Path file = this.dir.resolve("held.jsonl");
		var other = new JsonlChatMemoryRepository(this.dir);
		other.append("held", List.of(new UserMessage("before")));
		var during = new FutureTask<Void>(() -> operation.accept(other), null);
		var probe = new AtomicReference<String>();
		var writer = new JsonlChatMemoryRepository(this.dir, () -> {
			awaitFinishedOrWaiting(Thread.ofPlatform().start(during));
			try (var child = Child.launch("probe", file.toString())) {
				probe.set(child.awaitAnyLine(Set.of("ACQUIRED", "HELD")));
				assertThat(child.awaitExit()).isZero();
			}
			catch (IOException | InterruptedException ex) {
				throw new IllegalStateException(ex);
			}
		});

		writer.append("held", List.of(new UserMessage("while locked")));

		assertThat(probe).as("another process took the lock while this JVM's writer held it").hasValue("HELD");
		during.get(LIMIT_SECONDS, TimeUnit.SECONDS);
		assertThat(new JsonlChatMemoryRepository(this.dir).findByConversationId("held")).extracting(Message::getText)
			.containsExactlyElementsOf(stored);
	}

	static Stream<Arguments> accessesWhileLocked() {
		List<String> bothWrites = List.of("before", "while locked");
		return Stream.of(
				// 대조군: 디렉터리만 읽으니 세션 파일을 열지 않는다. 잠금 앞에서 기다리지 않아도 HELD 여야 한다.
				access("findConversationIds", JsonlChatMemoryRepository::findConversationIds, bothWrites),
				access("findByConversationId", repository -> repository.findByConversationId("held"), bothWrites),
				access("read", repository -> repository.read("held"), bothWrites),
				access("branch", repository -> repository.branch("held"), bothWrites),
				access("completedTurns", repository -> repository.completedTurns("held"), bothWrites),
				access("fork", repository -> repository.fork("held", "copy", 0), bothWrites),
				access("append from another instance",
						repository -> repository.append("held", List.of(new UserMessage("after"))),
						List.of("before", "while locked", "after")));
	}

	private static Arguments access(String name, Consumer<JsonlChatMemoryRepository> operation,
			List<String> stored) {
		return Arguments.of(Named.of(name, operation), stored);
	}

	@Test
	void forksAndAppendsFromTwoInstancesFinishAndKeepEveryRecord() throws Exception {
		var appender = new JsonlChatMemoryRepository(this.dir);
		var forker = new JsonlChatMemoryRepository(this.dir);
		appendPairs(appender, "source", 9, 1);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(4)) {
			List<Future<?>> workers = new ArrayList<>();
			workers.add(executor.submit(() -> {
				start.await();
				appendPairs(appender, "source", 0, 25);
				return null;
			}));
			workers.add(executor.submit(() -> {
				start.await();
				appendPairs(forker, "source", 1, 25);
				return null;
			}));
			workers.add(executor.submit(() -> {
				start.await();
				for (int fork = 0; fork < 25; fork++) {
					forker.fork("source", "fork-" + fork, 1);
					appender.append("fork-" + fork, List.of(new UserMessage("after fork " + fork)));
				}
				return null;
			}));
			workers.add(executor.submit(() -> {
				start.await();
				for (int read = 0; read < 25; read++) {
					assertThat(appender.completedTurns("source")).isPositive();
				}
				return null;
			}));
			start.countDown();
			for (Future<?> worker : workers) {
				worker.get(LIMIT_SECONDS, TimeUnit.SECONDS);
			}
		}

		assertPairsWholeAndInOrder(appender.findByConversationId("source"), Map.of(9, 1, 0, 25, 1, 25));
		for (int fork = 0; fork < 25; fork++) {
			String id = "fork-" + fork;
			assertThat(forker.branch(id)).get()
				.extracting(JsonlChatMemoryRepository.Branch::parentId, JsonlChatMemoryRepository.Branch::forkTurns,
						JsonlChatMemoryRepository.Branch::forkMessages)
				.containsExactly("source", 1, 2);
			assertThat(forker.findByConversationId(id)).extracting(Message::getText)
				.containsExactly("w9-000 question", "w9-000 answer" + PADDING, "after fork " + fork);
		}
	}

	/**
	 * 접근하는 스레드가 끝나거나(잠금을 무시하고 파일을 열고 닫았다) 잠금 앞에서 멈출 때까지 돈다. 어느 쪽인지는
	 * 다른 프로세스가 잠금을 잡아 보는 것으로 판정한다. 기한은 멈춘 테스트를 끝내기 위한 것이고 판정에 쓰지 않는다.
	 */
	private static void awaitFinishedOrWaiting(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
		Set<Thread.State> settled = Set.of(Thread.State.TERMINATED, Thread.State.WAITING, Thread.State.BLOCKED);
		while (!settled.contains(thread.getState())) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("access neither finished nor waited: " + thread.getState());
			}
			Thread.onSpinWait();
		}
	}

	/** 한 번의 append 가 질문과 답 두 메시지를 함께 쓰므로, 두 메시지는 항상 붙어서 저장되어야 한다. */
	private static void appendPairs(JsonlChatMemoryRepository repository, String session, int writer, int count) {
		for (int sequence = 0; sequence < count; sequence++) {
			String tag = "w%d-%03d".formatted(writer, sequence);
			repository.append(session, List.of(new UserMessage(tag + " question"),
					new AssistantMessage(tag + " answer" + PADDING)));
		}
	}

	private static void assertPairsWholeAndInOrder(List<Message> stored, Map<Integer, Integer> appendsPerWriter) {
		assertThat(stored).hasSize(appendsPerWriter.values().stream().mapToInt(Integer::intValue).sum() * 2);
		Map<Integer, Integer> next = new HashMap<>();
		for (int index = 0; index < stored.size(); index += 2) {
			String question = stored.get(index).getText();
			String tag = question.substring(0, question.indexOf(' '));
			assertThat(stored.get(index + 1).getText()).isEqualTo(tag + " answer" + PADDING);
			int writer = Integer.parseInt(tag.substring(1, tag.indexOf('-')));
			int sequence = Integer.parseInt(tag.substring(tag.indexOf('-') + 1));
			assertThat(sequence).as("next record of writer %d", writer).isEqualTo(next.getOrDefault(writer, 0));
			next.put(writer, sequence + 1);
		}
		assertThat(next).isEqualTo(appendsPerWriter);
	}

	/** 자식 JVM 한 개. 표준 출력의 줄을 큐에 모으고, 표준 입력으로 한 줄씩 명령을 보낸다. */
	static final class Child implements AutoCloseable {

		private final Process process;

		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

		private final StringBuffer output = new StringBuffer();

		private Child(Process process) {
			this.process = process;
			Thread.ofVirtual().start(() -> {
				try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
						StandardCharsets.UTF_8))) {
					for (String line; (line = reader.readLine()) != null;) {
						this.output.append(line).append('\n');
						this.lines.add(line);
					}
				}
				catch (IOException ex) {
					this.output.append(ex).append('\n');
				}
			});
		}

		static Child launch(String... arguments) throws IOException {
			// surefire 가 만든 클래스패스를 절대 경로로 넘긴다. 자식의 작업 디렉터리는 다를 수 있다.
			String classpath = Arrays.stream(System.getProperty("surefire.test.class.path",
					System.getProperty("java.class.path")).split(java.io.File.pathSeparator))
				.map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
				.collect(Collectors.joining(java.io.File.pathSeparator));
			List<String> command = new ArrayList<>(List.of(
					Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
					Child.class.getName()));
			command.addAll(List.of(arguments));
			var builder = new ProcessBuilder(command).redirectErrorStream(true);
			builder.environment().clear();
			return new Child(builder.start());
		}

		void awaitLine(String expected) throws InterruptedException {
			awaitAnyLine(Set.of(expected));
		}

		/** {@code expected} 가운데 자식이 먼저 출력한 줄을 돌려준다. */
		String awaitAnyLine(Set<String> expected) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
			while (System.nanoTime() < deadline) {
				String line = this.lines.poll(1, TimeUnit.SECONDS);
				if (line != null && expected.contains(line)) {
					return line;
				}
				if (line == null && !this.process.isAlive()) {
					break;
				}
			}
			throw new AssertionError("child never printed " + expected + ", output: " + this.output);
		}

		void send(String line) throws IOException {
			this.process.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
			this.process.getOutputStream().flush();
		}

		int awaitExit() throws InterruptedException {
			assertThat(this.process.waitFor(LIMIT_SECONDS, TimeUnit.SECONDS)).as("child output: %s", this.output)
				.isTrue();
			return this.process.exitValue();
		}

		@Override
		public void close() {
			this.process.destroyForcibly();
		}

		/**
		 * {@code hold <file>} 은 파일 잠금을 쥐고 있다가 한 줄을 받으면 놓는다. {@code append} 는 GO 를 받으면 덧붙인다.
		 * {@code probe <file>} 은 잠금을 한 번만 잡아 보고 ACQUIRED 나 HELD 를 출력한다.
		 */
		public static void main(String[] arguments) throws Exception {
			var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
			if (arguments[0].equals("probe")) {
				try (var channel = FileChannel.open(Path.of(arguments[1]), StandardOpenOption.READ,
						StandardOpenOption.WRITE); var lock = channel.tryLock()) {
					System.out.println((lock != null) ? "ACQUIRED" : "HELD");
				}
			}
			else if (arguments[0].equals("hold")) {
				try (var channel = FileChannel.open(Path.of(arguments[1]), StandardOpenOption.CREATE,
						StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = channel.lock()) {
					System.out.println("LOCKED");
					input.readLine();
				}
			}
			else {
				var repository = new JsonlChatMemoryRepository(Path.of(arguments[1]));
				System.out.println("READY");
				input.readLine();
				appendPairs(repository, arguments[2], Integer.parseInt(arguments[3]), Integer.parseInt(arguments[4]));
			}
			System.out.println("DONE");
		}

	}

}
