package com.example.miniagent.plugin.tui;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 플러그인이 부르는 질문 API(readLine, requestApproval)와 출력 API 가 TUI 가 켜져 있을 때와 꺼져 있을 때
 * 각각 어디로 가는지 확인한다. 꺼진 상태는 부모 {@code AgentConsole} 이 생성 시점의 System.in/out 을 잡아 두므로
 * 콘솔을 만들 때만 표준 입출력을 바꾼다.
 */
class TuiConsoleTest {

	private static final String SECOND_READER_MESSAGE = "Console already has a reader";

	private final ChatLog log = new ChatLog();

	private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

	private final ExecutorService readers = Executors.newCachedThreadPool();

	/** 새 질문이 등록될 때마다 그 id 를 하나씩 넣는다. */
	private final BlockingQueue<UUID> asked = new LinkedBlockingQueue<>();

	private final AtomicReference<UUID> lastAsked = new AtomicReference<>();

	private final AtomicInteger redraws = new AtomicInteger();

	private TuiConsole console = consoleReading("");

	@AfterEach
	void stop() throws InterruptedException {
		this.console.deactivate();
		this.readers.shutdownNow();
		assertThat(this.readers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void lineAnswerReturnsTheTypedTextAndLogsTheQuestionThenTheUserReply() throws Exception {
		activate();
		Future<String> line = readLineAsync("Name? ");
		UUID id = awaitQuestion();

		assertThat(this.console.pendingQuestion()).isEqualTo("Name?");
		assertThat(this.console.pendingQuestionId()).isEqualTo(id);
		assertThat(this.log.snapshot()).containsExactly(new ChatLog.Entry(ChatLog.Role.QUESTION, "Name?"));
		assertThat(this.console.answer(id, "alice")).isTrue();

		assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("alice");
		assertThat(this.log.snapshot()).containsExactly(new ChatLog.Entry(ChatLog.Role.QUESTION, "Name?"),
				new ChatLog.Entry(ChatLog.Role.USER, "alice"));
		assertThat(this.console.pendingQuestion()).isNull();
		assertThat(this.console.pendingQuestionId()).isNull();
	}

	@Test
	void answerWithAWrongOrMissingIdIsRefusedAndTheQuestionStaysOpen() throws Exception {
		activate();
		Future<String> line = readLineAsync("Name? ");
		UUID id = awaitQuestion();

		assertThat(this.console.answer(UUID.randomUUID(), "mallory")).isFalse();
		assertThat(this.console.answer(null, "mallory")).isFalse();

		assertThat(line).isNotDone();
		assertThat(this.console.pendingQuestionId()).isEqualTo(id);
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.role() == ChatLog.Role.USER);
		assertThat(this.console.answer(id, "alice")).isTrue();
		assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("alice");
		assertThat(this.console.answer(id, "again")).isFalse();
	}

	@Test
	void secondReaderIsRefusedWhileALineQuestionIsWaiting() throws Exception {
		activate();
		Future<String> first = readLineAsync("First? ");
		UUID id = awaitQuestion();

		assertThatThrownBy(() -> readLineAsync("Second? ").get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(IllegalStateException.class)
			.hasRootCauseMessage(SECOND_READER_MESSAGE);
		assertThatThrownBy(() -> this.console.requestApproval(approval(), answer -> {}))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage(SECOND_READER_MESSAGE);

		assertThat(this.console.pendingQuestion()).isEqualTo("First?");
		assertThat(this.log.snapshot()).hasSize(1);
		assertThat(this.console.answer(id, "kept")).isTrue();
		assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("kept");
	}

	@Test
	void secondReaderIsRefusedWhileAnApprovalIsWaiting() throws Exception {
		activate();
		var request = approval();
		var answered = new CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(request, answered::complete);
		awaitQuestion();

		assertThatThrownBy(() -> readLineAsync("Name? ").get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasRootCauseMessage(SECOND_READER_MESSAGE);
		assertThatThrownBy(() -> this.console.requestApproval(approval(), answer -> {}))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage(SECOND_READER_MESSAGE);

		assertThat(this.console.pendingQuestionId()).isEqualTo(request.requestId());
		assertThat(this.console.answer(request.requestId(), request.requestId() + " y")).isTrue();
		assertThat(answered.get(5, TimeUnit.SECONDS))
			.isEqualTo(new ApprovalAnswer(request.requestId(), ApprovalDecision.APPROVED, false));
	}

	@Test
	void cancelQuestionReleasesAWaitingLineReaderAndTheConsoleAsksAgain() throws Exception {
		activate();
		Future<String> line = readLineAsync("Name? ");
		awaitQuestion();

		this.console.cancelQuestion();

		assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(CancellationException.class);
		assertThat(this.console.pendingQuestion()).isNull();
		assertThat(this.console.pendingQuestionId()).isNull();
		Future<String> next = readLineAsync("Again? ");
		assertThat(this.console.answer(awaitQuestion(), "alice")).isTrue();
		assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("alice");
	}

	@Test
	void cancelQuestionDeliversCancelledToThePendingApproval() throws Exception {
		activate();
		var request = approval();
		var answered = new CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(request, answered::complete);
		awaitQuestion();

		this.console.cancelQuestion();

		assertThat(answered.get(5, TimeUnit.SECONDS))
			.isEqualTo(new ApprovalAnswer(request.requestId(), ApprovalDecision.CANCELLED, false));
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.role() == ChatLog.Role.USER);
	}

	@Test
	void deactivateReleasesAWaitingLineReader() throws Exception {
		activate();
		Future<String> line = readLineAsync("Name? ");
		awaitQuestion();

		this.console.deactivate();

		assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(CancellationException.class);
		assertThat(this.console.pendingQuestion()).isNull();
	}

	@Test
	void interruptedLineReaderThrowsCancellationAndClearsTheQuestion() throws Exception {
		activate();
		var readerThread = new AtomicReference<Thread>();
		var interruptFlagKept = new AtomicBoolean();
		Future<String> line = this.readers.submit(() -> {
			readerThread.set(Thread.currentThread());
			try {
				return this.console.readLine("Name? ");
			}
			catch (CancellationException ex) {
				interruptFlagKept.set(Thread.currentThread().isInterrupted());
				throw ex;
			}
		});
		awaitQuestion();

		readerThread.get().interrupt();

		assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class)
			.hasCauseInstanceOf(CancellationException.class)
			.hasRootCauseMessage("Input cancelled");
		assertThat(interruptFlagKept).isTrue();
		assertThat(this.console.pendingQuestion()).isNull();
		assertThat(this.console.pendingQuestionId()).isNull();
	}

	@ParameterizedTest
	@CsvSource({ "y,APPROVED,false", "yes,APPROVED,false", "a,APPROVED,true", "always,APPROVED,true",
			"n,REJECTED,false", "defer,DEFERRED,false", "cancel,CANCELLED,false" })
	void approvalAnswerIsLoggedAndMappedToItsDecision(String token, ApprovalDecision decision, boolean remember)
			throws Exception {
		activate();
		var request = approval();
		var answered = new CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(request, answered::complete);
		UUID id = awaitQuestion();

		assertThat(this.console.pendingQuestion()).contains("[approval " + id, "tool=write_file");
		assertThat(this.console.answer(id, id + " " + token)).isTrue();

		assertThat(answered.get(5, TimeUnit.SECONDS)).isEqualTo(new ApprovalAnswer(id, decision, remember));
		assertThat(this.log.snapshot()).extracting(ChatLog.Entry::role)
			.containsExactly(ChatLog.Role.QUESTION, ChatLog.Role.USER);
		assertThat(this.log.snapshot().get(1).text()).isEqualTo(id + " " + token);
	}

	@Test
	void malformedApprovalAnswersAreRefusedWithAnErrorEntryAndTheRequestStaysOpen() throws Exception {
		activate();
		var request = approval();
		var answered = new CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(request, answered::complete);
		UUID id = awaitQuestion();

		for (String text : List.of("y", "yes please", id + " maybe", UUID.randomUUID() + " y")) {
			assertThat(this.console.answer(id, text)).isFalse();
		}

		assertThat(answered).isNotDone();
		assertThat(this.console.pendingQuestionId()).isEqualTo(id);
		assertThat(this.log.snapshot()).filteredOn(entry -> entry.role() == ChatLog.Role.ERROR)
			.hasSize(4)
			.allMatch(entry -> entry.text().contains("Use the displayed approval UUID"));
		assertThat(this.log.snapshot()).noneMatch(entry -> entry.role() == ChatLog.Role.USER);
	}

	@Test
	void dismissApprovalClearsOnlyTheMatchingRequestWithoutAnswering() throws Exception {
		activate();
		var request = approval();
		var answered = new CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(request, answered::complete);
		awaitQuestion();

		this.console.dismissApproval(UUID.randomUUID());
		assertThat(this.console.pendingQuestionId()).isEqualTo(request.requestId());

		this.console.dismissApproval(request.requestId());
		assertThat(this.console.pendingQuestion()).isNull();
		assertThat(this.console.pendingQuestionId()).isNull();
		assertThat(answered).isNotDone();
		Future<String> line = readLineAsync("Next? ");
		assertThat(this.console.answer(awaitQuestion(), "ok")).isTrue();
		assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
	}

	@Test
	void appendReplyDeltasJoinIntoOneAgentEntryAndEmptyDeltasAreIgnored() {
		activate();

		this.console.appendReply("Hello");
		this.console.appendReply("");
		this.console.appendReply(null);
		this.console.appendReply(", ");
		this.console.appendReply("world");
		this.console.endReply();

		assertThat(this.log.snapshot()).containsExactly(new ChatLog.Entry(ChatLog.Role.AGENT, "Hello, world"));
		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).isEmpty();
		assertThat(this.redraws).hasValue(4);
	}

	@Test
	void endReplyClosesTheStreamedEntrySoTheNextReplyStartsANewOne() {
		activate();

		this.console.appendReply("first answer");
		this.console.endReply();
		this.console.appendReply("second answer");

		assertThat(this.log.snapshot()).containsExactly(new ChatLog.Entry(ChatLog.Role.AGENT, "first answer"),
				new ChatLog.Entry(ChatLog.Role.AGENT, "second answer"));
	}

	@Test
	void streamedReplyAfterACompleteReplyStartsItsOwnEntry() {
		activate();

		this.console.reply("complete answer");
		this.console.appendReply("streamed answer");
		this.console.endReply();

		assertThat(this.log.snapshot()).containsExactly(new ChatLog.Entry(ChatLog.Role.AGENT, "complete answer"),
				new ChatLog.Entry(ChatLog.Role.AGENT, "streamed answer"));
	}

	@Test
	void activeOutputGoesToTheChatLogWithItsRoleAndNotToStandardOutput() {
		activate();

		this.console.println("  plain \n");
		this.console.info("note");
		this.console.error("failure");
		this.console.reply("answer");

		assertThat(this.log.snapshot()).containsExactly(new ChatLog.Entry(ChatLog.Role.TEXT, "plain"),
				new ChatLog.Entry(ChatLog.Role.INFO, "note"), new ChatLog.Entry(ChatLog.Role.ERROR, "failure"),
				new ChatLog.Entry(ChatLog.Role.AGENT, "answer"));
		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).isEmpty();
		assertThat(this.redraws).hasValue(4);
	}

	@Test
	void traceViewIsReportedOnlyWhileActive() {
		assertThat(this.console.hasTraceView()).isFalse();

		activate();
		assertThat(this.console.hasTraceView()).isTrue();

		this.console.deactivate();
		assertThat(this.console.hasTraceView()).isFalse();
	}

	@Test
	void inactiveOutputGoesToStandardOutputAndNotToTheChatLog() {
		this.console.println("plain");
		this.console.reply("answer");
		this.console.info("note");
		this.console.error("failure");

		String separator = System.lineSeparator();
		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).startsWith("plain" + separator + "answer" + separator)
			.contains("note", "failure");
		assertThat(this.log.snapshot()).isEmpty();
		assertThat(this.redraws).hasValue(0);
	}

	@Test
	void deactivatedConsoleWritesToStandardOutputAgain() {
		activate();
		this.console.deactivate();

		this.console.println("after");

		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).isEqualTo("after" + System.lineSeparator());
		assertThat(this.log.snapshot()).isEmpty();
	}

	@Test
	void inactiveReadLineReturnsTheStandardInputLineAfterPrintingThePrompt() throws Exception {
		this.console = consoleReading("alice\n");

		assertThat(readLineAsync("Name? ").get(5, TimeUnit.SECONDS)).isEqualTo("alice");

		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).isEqualTo("Name? ");
		assertThat(this.log.snapshot()).isEmpty();
		assertThat(this.console.pendingQuestion()).isNull();
		assertThat(readLineAsync("Name? ").get(5, TimeUnit.SECONDS)).isNull();
	}

	@Test
	void inactiveStreamedReplyIsPrintedAndClosedWithOneNewline() {
		this.console.appendReply("Hel");
		this.console.appendReply("lo");
		this.console.endReply();
		this.console.endReply();

		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).isEqualTo("Hello" + System.lineSeparator());
		assertThat(this.log.snapshot()).isEmpty();
	}

	@Test
	void inactiveApprovalIsAskedAndAnsweredOnStandardInput() throws Exception {
		var request = approval();
		this.console = consoleReading(request.requestId() + " y\n");
		var answered = new CompletableFuture<ApprovalAnswer>();

		this.console.requestApproval(request, answered::complete);

		assertThat(answered.get(5, TimeUnit.SECONDS))
			.isEqualTo(new ApprovalAnswer(request.requestId(), ApprovalDecision.APPROVED, false));
		assertThat(this.stdout.toString(StandardCharsets.UTF_8)).contains("[approval " + request.requestId());
		assertThat(this.log.snapshot()).isEmpty();
	}

	/** 질문이 등록될 때 그 id 를 {@link #asked} 에 넣는 다시 그리기 훅으로 켠다. */
	private void activate() {
		this.console.activate(() -> {
			this.redraws.incrementAndGet();
			UUID id = this.console.pendingQuestionId();
			if (id != null && !id.equals(this.lastAsked.getAndSet(id))) {
				this.asked.add(id);
			}
		});
	}

	private UUID awaitQuestion() throws InterruptedException {
		UUID id = this.asked.poll(5, TimeUnit.SECONDS);
		assertThat(id).isNotNull();
		return id;
	}

	private Future<String> readLineAsync(String prompt) {
		return this.readers.submit(() -> this.console.readLine(prompt));
	}

	private static ApprovalRequest approval() {
		return new ApprovalRequest(UUID.randomUUID(), RunId.random(), "attempt", null, null, "write_file",
				"a".repeat(64), null);
	}

	/** 부모 AgentConsole 이 생성 시점의 System.in/out 을 잡아 두므로 그때만 바꿨다가 되돌린다. */
	private TuiConsole consoleReading(String stdin) {
		PrintStream originalOut = System.out;
		InputStream originalIn = System.in;
		try {
			System.setOut(new PrintStream(this.stdout, true, StandardCharsets.UTF_8));
			System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
			return new TuiConsole(this.log);
		}
		finally {
			System.setOut(originalOut);
			System.setIn(originalIn);
		}
	}

}
