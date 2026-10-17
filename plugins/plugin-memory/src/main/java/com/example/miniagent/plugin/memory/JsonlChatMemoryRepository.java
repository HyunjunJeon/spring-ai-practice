package com.example.miniagent.plugin.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 세션 하나 = JSONL 파일 하나. (pi 의 세션 파일과 같은 아이디어, 트리 구조는 생략)
 *
 * <p>{@link ChatMemoryRepository} 는 "저장만" 담당하는 SPI 다. JDBC/Redis/Mongo 등 공식 구현체도 같은 4개 메서드만
 * 구현한다. SPI 의 {@link #saveAll} 은 목록 전체로 파일을 다시 쓰지만, 이 플러그인의 {@link JsonlChatMemory} 는
 * {@link #append} 로 끝에 덧붙이기만 한다. 그래서 파일에는 세션의 전체 기록이 남는다.
 *
 * <p>파일에는 메시지 줄 사이에 요약 기록({@code "type":"summary"})도 들어간다. 요약은 "앞에서부터 {@code covers} 개의
 * 메시지를 접은 내용"이다. {@link #findByConversationId} 는 메시지만 돌려주고, 요약까지 필요하면 {@link #read} 를 쓴다.
 *
 * <p>쓰다가 끊긴 마지막 조각(줄바꿈으로 끝나지 않은 줄)은 읽을 때 건너뛰고, 다음 append 때 잘라낸다.
 */
public class JsonlChatMemoryRepository implements ChatMemoryRepository {

	/** 세션 파일을 읽은 결과. {@code summary} 는 가장 마지막 요약 기록이고, 없으면 null 이다. */
	public record History(List<Message> messages, @Nullable Summary summary) {
	}

	/** 앞에서부터 {@code covers} 개의 메시지를 접은 요약. */
	public record Summary(String text, int covers) {
	}

	private static final String EXTENSION = ".jsonl";

	/**
	 * 세션 파일마다 하나씩, 이 JVM 의 모든 저장소 인스턴스가 함께 쓰는 잠금. POSIX 에서는 프로세스가 같은 파일의 기술자를
	 * 하나라도 닫으면 그 파일에 건 잠금이 모두 풀린다({@link FileLock} 문서). 그래서 쓰는 쪽이 파일 잠금을 쥔 동안 읽는
	 * 쪽이 같은 파일을 열고 닫으면 다른 프로세스가 끼어든다. 세션 파일을 여는 접근은 모두 이 잠금을 먼저 잡는다.
	 * 항목은 JVM 이 끝날 때까지 남고, 이 JVM 이 건드린 세션 파일 수만큼 생긴다.
	 */
	private static final ConcurrentMap<Path, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

	private final Path directory;

	private final JsonMapper json = JsonMapper.builder().build();

	private final Runnable whileFileLocked;

	public JsonlChatMemoryRepository(Path directory) {
		this(directory, () -> {
		});
	}

	/**
	 * {@code whileFileLocked} 는 쓰는 쪽이 파일 잠금을 쥔 동안, 파일을 쓰기 직전에 부른다. 파일 상태만으로는 그 순간을
	 * 잡을 수 없어서, 테스트가 그 사이에 같은 파일로 다른 접근을 끼워 넣는 자리로 쓴다.
	 */
	JsonlChatMemoryRepository(Path directory, Runnable whileFileLocked) {
		this.directory = directory;
		this.whileFileLocked = whileFileLocked;
	}

	@Override
	public List<String> findConversationIds() {
		if (!Files.isDirectory(this.directory)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(this.directory)) {
			return files.map(f -> f.getFileName().toString())
				.filter(name -> name.endsWith(EXTENSION))
				.map(name -> name.substring(0, name.length() - EXTENSION.length()))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		return read(conversationId).messages();
	}

	/** 메시지와 가장 마지막 요약 기록을 함께 읽는다. */
	public History read(String conversationId) {
		Path file = file(conversationId);
		if (!Files.exists(file)) {
			return new History(List.of(), null);
		}
		String text = locked(file, () -> Files.readString(file, StandardCharsets.UTF_8));
		List<Message> messages = new ArrayList<>();
		Summary summary = null;
		// 줄바꿈으로 끝나지 않은 마지막 조각은 쓰다 끊긴 기록이라 읽지 않는다.
		// 중간 줄이 깨졌다면 숨기지 않고 예외로 알린다.
		for (String line : text.substring(0, text.lastIndexOf('\n') + 1).lines().toList()) {
			if (line.isBlank()) {
				continue;
			}
			StoredMessage stored = this.json.readValue(line, StoredMessage.class);
			if ("branch".equals(stored.type())) {
				continue;
			}
			if (stored.isSummary()) {
				summary = new Summary((stored.text() != null) ? stored.text() : "",
						(stored.covers() != null) ? stored.covers() : 0);
			}
			else {
				messages.add(stored.toMessage());
			}
		}
		return new History(List.copyOf(messages), summary);
	}

	/**
	 * SPI 계약대로 목록 전체로 파일을 다시 쓴다(요약 기록은 남지 않는다). 이 플러그인의 메모리는 append 만 쓴다.
	 * append 와 같은 파일 잠금을 잡고 제자리에서 다시 쓴다. 임시 파일로 바꿔치면 다른 프로세스가 쥔 잠금이 옛 파일에
	 * 남아, 그 프로세스의 append 가 사라진다.
	 */
	@Override
	public synchronized void saveAll(String conversationId, List<Message> messages) {
		// 분기 머리글은 잠금을 잡기 전에 읽는다. 잠금을 쥔 채 같은 파일의 다른 기술자를 열고 닫으면 잠금이 풀릴 수 있다.
		StringBuilder lines = new StringBuilder();
		branch(conversationId).ifPresent(value -> lines.append(this.json.writeValueAsString(
				new StoredMessage("branch", this.json.writeValueAsString(value), null, null, null, null))).append('\n'));
		messages.stream().map(StoredMessage::from).forEach(message ->
				lines.append(this.json.writeValueAsString(message)).append('\n'));
		ByteBuffer bytes = ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8));
		Path file = file(conversationId);
		locked(file, () -> {
			Files.createDirectories(this.directory);
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
					StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
				this.whileFileLocked.run();
				channel.truncate(0);
				writeFully(channel, bytes, 0);
			}
			return null;
		});
	}

	/** 메시지를 파일 끝에 덧붙인다. 앞의 기록은 건드리지 않는다. */
	public void append(String conversationId, List<Message> messages) {
		appendRecords(conversationId, messages.stream().map(StoredMessage::from).toList());
	}

	/** 요약 기록을 덧붙인다. 읽을 때는 가장 마지막 요약 기록이 쓰인다. */
	public void appendSummary(String conversationId, Summary summary) {
		appendRecords(conversationId, List.of(StoredMessage.summary(summary)));
	}

	/** 파일 잠금으로 같은 세션을 연 다른 mini-agent 프로세스와 동시에 쓰지 않는다. */
	private synchronized void appendRecords(String conversationId, List<StoredMessage> records) {
		if (records.isEmpty()) {
			return;
		}
		StringBuilder lines = new StringBuilder();
		records.forEach(record -> lines.append(this.json.writeValueAsString(record)).append('\n'));
		ByteBuffer bytes = ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8));
		Path file = file(conversationId);
		locked(file, () -> {
			Files.createDirectories(this.directory);
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
					StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
				this.whileFileLocked.run();
				long position = completeLength(channel);
				channel.truncate(position);
				writeFully(channel, bytes, position);
			}
			return null;
		});
	}

	@Override
	public synchronized void deleteByConversationId(String conversationId) {
		Path file = file(conversationId);
		locked(file, () -> Files.deleteIfExists(file));
	}

	/** Forget conversation content without turning an existing branch into a new root. */
	public synchronized void clearHistory(String conversationId) {
		if (branch(conversationId).isPresent()) saveAll(conversationId, List.of());
		else deleteByConversationId(conversationId);
	}

	/** Immutable ancestry lives in the same atomic snapshot as the copied messages. */
	public record Branch(String id, String parentId, int forkTurns, int forkMessages, String createdAt) { }

	public java.util.Optional<Branch> branch(String conversationId) {
		Path path = file(conversationId);
		if (!Files.exists(path)) return java.util.Optional.empty();
		return locked(path, () -> {
			try (var input = Files.newInputStream(path)) {
				byte[] prefix = input.readNBytes(8192);
				String text = new String(prefix, StandardCharsets.UTF_8);
				int end = text.indexOf('\n');
				if (end < 0) return java.util.Optional.empty();
				StoredMessage first = this.json.readValue(text.substring(0, end), StoredMessage.class);
				if (!"branch".equals(first.type())) return java.util.Optional.empty();
				Branch branch = this.json.readValue(first.text(), Branch.class);
				if (!conversationId.equals(branch.id()) || branch.forkTurns() < 0 || branch.forkMessages() < 0) {
					throw new IllegalArgumentException("Invalid branch metadata");
				}
				file(branch.parentId());
				return java.util.Optional.of(branch);
			}
		});
	}

	/** Completed assistant answers are fork points; pending tool calls are never copied. */
	public synchronized Branch fork(String sourceId, String targetId, int turns) {
		Path source = file(sourceId);
		Path target = file(targetId);
		if (source.equals(target) || Files.exists(target) || turns < 0) {
			throw new IllegalArgumentException("Invalid fork target or turn count");
		}
		History history = read(sourceId);
		List<Integer> boundaries = completedTurnBoundaries(history.messages());
		if (turns > boundaries.size()) {
			throw new IllegalArgumentException("Fork turn exceeds completed history: " + boundaries.size());
		}
		int count = turns == 0 ? 0 : boundaries.get(turns - 1);
		Branch branch = new Branch(targetId, sourceId, turns, count, java.time.Instant.now().toString());
		StringBuilder text = new StringBuilder();
		text.append(this.json.writeValueAsString(new StoredMessage("branch",
				this.json.writeValueAsString(branch), null, null, null, null))).append('\n');
		history.messages().subList(0, count).forEach(message ->
				text.append(this.json.writeValueAsString(StoredMessage.from(message))).append('\n'));
		Summary summary = history.summary();
		if (summary != null && summary.covers() > 0 && summary.covers() <= count) {
			text.append(this.json.writeValueAsString(StoredMessage.summary(summary))).append('\n');
		}
		// 원본 잠금은 read 가 돌아올 때 이미 놓았다. 대상 잠금만 잡으니 두 세션 잠금을 겹쳐 쥐는 일이 없다.
		locked(target, () -> {
			Files.createDirectories(this.directory);
			Path temporary = Files.createTempFile(this.directory, ".fork-", ".tmp");
			try {
				Files.writeString(temporary, text, StandardCharsets.UTF_8);
				// The orchestrator generates a fresh UUID; existing sessions must never be replaced.
				if (Files.exists(target)) throw new IOException("Fork target already exists");
				Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(temporary);
			}
			return null;
		});
		return branch;
	}

	public int completedTurns(String conversationId) {
		return completedTurnBoundaries(read(conversationId).messages()).size();
	}

	private static List<Integer> completedTurnBoundaries(List<Message> messages) {
		List<Integer> boundaries = new ArrayList<>();
		int start = -1;
		for (int index = 0; index < messages.size(); index++) {
			Message message = messages.get(index);
			if (message instanceof UserMessage) {
				if (start >= 0 && !MemoryBudget.validTools(messages.subList(start, index))) break;
				start = index;
			}
			if (start >= 0 && message instanceof AssistantMessage assistant && !assistant.hasToolCalls()) {
				if (!MemoryBudget.validTools(messages.subList(start, index + 1))) break;
				boundaries.add(index + 1);
				start = -1;
			}
		}
		return List.copyOf(boundaries);
	}

	private Path file(String conversationId) {
		if (!conversationId.matches("[A-Za-z0-9._-]+")) {
			throw new IllegalArgumentException("Invalid session id: " + conversationId);
		}
		return this.directory.resolve(conversationId + EXTENSION);
	}

	/**
	 * 세션 파일의 JVM 안 잠금을 쥔 채 입출력한다. 다른 스레드나 인스턴스가 그 파일의 잠금을 쥐고 있으면 기다린다.
	 * 인터럽트로 깨지 않는 {@code lock()} 을 써서 {@link #withoutInterrupt} 와 같이 취소된 턴의 기록도 끝까지 쓴다.
	 */
	private static <T> T locked(Path file, IoAction<T> action) {
		ReentrantLock lock = FILE_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), key -> new ReentrantLock());
		lock.lock();
		try {
			return withoutInterrupt(action);
		}
		finally {
			lock.unlock();
		}
	}

	/**
	 * 파일 입출력 동안만 인터럽트 표시를 내려 두었다가 되돌린다. TUI 에서 Esc 로 취소하면 작업 스레드에 인터럽트가
	 * 걸리는데, FileChannel 은 인터럽트된 스레드에서 쓰면 채널을 닫고 실패한다. 그러면 취소된 턴의 마지막 기록이
	 * 빠지거나 오류가 난다. 짧은 기록은 끝까지 쓰고, 취소는 다음 대기 지점(모델 호출, bash)에서 처리되게 한다.
	 */
	private static <T> T withoutInterrupt(IoAction<T> action) {
		boolean interrupted = Thread.interrupted();
		try {
			return action.run();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@FunctionalInterface
	private interface IoAction<T> {

		T run() throws IOException;

	}

	/** {@code position} 부터 버퍼가 빌 때까지 쓴다. */
	private static void writeFully(FileChannel channel, ByteBuffer bytes, long position) throws IOException {
		while (bytes.hasRemaining()) {
			position += channel.write(bytes, position);
		}
	}

	/** 마지막 줄바꿈 바로 뒤의 위치. 파일이 줄바꿈으로 끝나면 파일 크기와 같다. */
	private static long completeLength(FileChannel channel) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		long end = channel.size();
		while (end > 0) {
			int length = (int) Math.min(buffer.capacity(), end);
			long start = end - length;
			buffer.clear().limit(length);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer, start + buffer.position()) < 0) {
					break;
				}
			}
			for (int i = length - 1; i >= 0; i--) {
				if (buffer.get(i) == '\n') {
					return start + i + 1;
				}
			}
			end = start;
		}
		return 0;
	}

	/**
	 * 파일에 쓰는 형태. Spring AI 의 Message 계층을 그대로 직렬화하지 않고 필요한 필드만 옮긴다.
	 * {@code covers} 는 요약 기록에만 있다. 문자열 메타데이터는 제공자별 추론/서명 재전송 상태다.
	 */
	record StoredMessage(String type, @Nullable String text, @Nullable List<AssistantMessage.ToolCall> toolCalls,
			@Nullable List<ToolResponseMessage.ToolResponse> toolResponses, @Nullable Integer covers,
			@Nullable Map<String, Object> metadata) {

		static final String SUMMARY = "summary";

		static StoredMessage from(Message message) {
			return switch (message) {
				case AssistantMessage assistant -> {
					Map<String, Object> metadata = new HashMap<>();
					assistant.getMetadata().forEach((key, value) -> {
						if (value instanceof String) {
							metadata.put(key, value);
						}
					});
					yield new StoredMessage("assistant", assistant.getText(),
							assistant.hasToolCalls() ? assistant.getToolCalls() : null, null, null,
							metadata.isEmpty() ? null : metadata);
				}
				case ToolResponseMessage tool -> new StoredMessage("tool", null, null, tool.getResponses(), null, null);
				case SystemMessage system -> new StoredMessage("system", system.getText(), null, null, null, null);
				default -> new StoredMessage("user", message.getText(), null, null, null, null);
			};
		}

		static StoredMessage summary(Summary summary) {
			return new StoredMessage(SUMMARY, summary.text(), null, null, summary.covers(), null);
		}

		boolean isSummary() {
			return SUMMARY.equals(this.type);
		}

		Message toMessage() {
			return switch (this.type) {
				case "assistant" -> AssistantMessage.builder()
					.content(this.text)
					.toolCalls((this.toolCalls != null) ? this.toolCalls : List.of())
					.properties(this.metadata != null ? this.metadata : Map.of())
					.build();
				case "tool" -> ToolResponseMessage.builder()
					.responses((this.toolResponses != null) ? this.toolResponses : List.of())
					.build();
				case "system" -> new SystemMessage(this.text);
				default -> new UserMessage(this.text);
			};
		}

	}

}
