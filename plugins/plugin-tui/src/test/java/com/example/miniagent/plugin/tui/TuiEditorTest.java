package com.example.miniagent.plugin.tui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.run.RunId;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.widgets.input.TextAreaState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TuiEditorTest {
	private final ExecutorService worker = Executors.newSingleThreadExecutor();
	private final Agent agent = mock(Agent.class);
	private final TuiRunner runner = mock(TuiRunner.class);
	private final AgentSession session = new AgentSession();
	private final ChatLog log = new ChatLog();
	private final TuiConsole console = new TuiConsole(this.log);
	private final TraceGraph graph = new TraceGraph();
	private final AtomicInteger commands = new AtomicInteger();
	private final TuiFrontEnd ui = new TuiFrontEnd(new AgentInputHandler(this.agent, this.session, this.console,
			List.of(SlashCommand.of("model", "fixture", (args, context) -> this.commands.incrementAndGet()),
					SlashCommand.of("models", "fixture", (args, context) -> this.commands.incrementAndGet()))),
			this.session, this.console, this.log, this.graph, () -> "fixture-model", Path.of("unused.log"));

	@AfterEach void close() throws InterruptedException {
		this.console.deactivate();
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test void pastePreservesNormalizedLinesWithoutSubmitting() {
		event(new PasteEvent("\u1112\u1161\u11ab\r\nsecond\rthird"));
		assertThat(input().text()).isEqualTo("한\nsecond\nthird");
		assertThat(input().lineCount()).isEqualTo(3);
		verifyNoInteractions(this.agent);
	}

	@Test void modifiedEnterAndControlNewlineNeverSubmit() {
		event(new PasteEvent("first"));
		event(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.ALT));
		event(KeyEvent.ofChar('n', KeyModifiers.CTRL));
		event(KeyEvent.ofKey(KeyCode.ENTER, KeyModifiers.SHIFT));
		assertThat(input().text()).isEqualTo("first\n\n\n");
		verifyNoInteractions(this.agent);
	}

	@Test void plainEnterDispatchesWholeMultilineInputExactlyOnce() throws Exception {
		String text = "first\n한글 second";
		when(this.agent.streamQueued(eq(text), any(), any())).thenReturn(new Agent.AgentReply("result", null));
		event(new PasteEvent(text));
		event(KeyEvent.ofKey(KeyCode.ENTER));
		drain();
		verify(this.agent, times(1)).streamQueued(eq(text), any(), any());
		assertThat(input().text()).isEmpty();
	}

	@Test void historyRestoresUnsubmittedMultilineDraft() throws Exception {
		when(this.agent.streamQueued(eq("history"), any(), any())).thenReturn(new Agent.AgentReply("result", null));
		event(new PasteEvent("history"));
		event(KeyEvent.ofKey(KeyCode.ENTER));
		drain();
		event(new PasteEvent("draft\n한글"));
		render(80, 24);
		event(KeyEvent.ofKey(KeyCode.HOME, KeyModifiers.CTRL));
		event(KeyEvent.ofKey(KeyCode.UP));
		assertThat(input().text()).isEqualTo("history");
		event(KeyEvent.ofKey(KeyCode.DOWN));
		assertThat(input().text()).isEqualTo("draft\n한글");
	}

	@Test void arrowsNavigateWrappedDraftBeforeHistory() {
		event(new PasteEvent("한글 ".repeat(60)));
		render(60, 24);
		int before = input().cursorCol();
		event(KeyEvent.ofKey(KeyCode.UP));
		assertThat(input().cursorCol()).isLessThan(before);
		int previousRow = input().cursorCol();
		event(KeyEvent.ofKey(KeyCode.DOWN));
		assertThat(input().cursorCol()).isGreaterThan(previousRow).isLessThanOrEqualTo(before);
		assertThat(input().text()).isEqualTo("한글 ".repeat(60));
	}

	@Test void completionCyclesRegisteredCommandsWithoutExecutingThemOrOpeningTrace() {
		event(new PasteEvent("/mo"));
		event(KeyEvent.ofKey(KeyCode.TAB));
		assertThat(input().text()).isEqualTo("/model ");
		event(KeyEvent.ofKey(KeyCode.TAB));
		assertThat(input().text()).isEqualTo("/models ");
		assertThat(ReflectionTestUtils.getField(this.ui, "showSidebar")).isEqualTo(false);
		assertThat(this.commands.get()).isZero();
		verifyNoInteractions(this.agent);
		event(KeyEvent.ofChar('x'));
		event(KeyEvent.ofKey(KeyCode.TAB));
		assertThat(ReflectionTestUtils.getField(this.ui, "showSidebar")).isEqualTo(true);
	}

	@Test void completionCannotMutateOrAnswerAnApprovalDraft() {
		this.console.activate(this.ui::requestRedraw);
		var approval = new ApprovalRequest(java.util.UUID.randomUUID(), RunId.random(), "attempt", null, null,
				"write_file", "a".repeat(64), null);
		var answer = new CompletableFuture<ApprovalAnswer>();
		this.console.requestApproval(approval, answer::complete);
		event(new PasteEvent("/mo"));
		event(KeyEvent.ofKey(KeyCode.TAB));
		assertThat(input().text()).isEqualTo("/mo");
		assertThat(answer).isNotDone();
		this.console.dismissApproval(approval.requestId());
	}

	@Test void conversationShowsFoldedToolActivityWithoutLeakingToolArguments() {
		this.graph.accept(new com.example.miniagent.core.TraceEvent.TurnStarted(1, "fixture"));
		this.graph.accept(new com.example.miniagent.core.TraceEvent.RoundStarted(1, 0, 0, 0));
		this.graph.accept(new com.example.miniagent.core.TraceEvent.RoundFinished(1,
				List.of("fixture-tool {\"private\":\"PRIVATE_ARGS\"}"), 0, null, 1,
				com.example.miniagent.core.TraceEvent.Tokens.ZERO, "fixture-model", null));
		String visible = text(render(80, 24));
		assertThat(visible).contains("fixture-tool").doesNotContain("PRIVATE_ARGS");
		assertThat(ReflectionTestUtils.getField(this.ui, "showSidebar")).isEqualTo(false);
	}

	@ParameterizedTest
	@CsvSource({"30,10", "60,18", "80,24", "100,24", "120,32", "160,40"})
	void realLayoutKeepsConversationByDefaultAndReflowsTrace(int width, int height) {
		this.log.add(ChatLog.Role.AGENT, "conversation-sentinel");
		var conversation = render(width, height);
		assertThat(text(conversation)).contains("conversation-sentinel");
		assertThat(conversation.cursorPosition()).isPresent();
		for (String row : text(conversation).split("\n"))
			assertThat(DisplayWidth.of(row)).isLessThanOrEqualTo(width);
		event(KeyEvent.ofChar('t', KeyModifiers.CTRL));
		var trace = render(width, height);
		if (width < 100) assertThat(text(trace)).doesNotContain("conversation-sentinel");
		else assertThat(text(trace)).contains("conversation-sentinel");
		event(KeyEvent.ofChar('t', KeyModifiers.CTRL));
		assertThat(text(render(width, height))).contains("conversation-sentinel");
	}

	@Test void longMultilineDraftScrollsAndPositionsHardwareCursorForIme() {
		event(new PasteEvent("first 한글\n".repeat(20) + "end한"));
		Frame frame = render(80, 24);
		var cursor = frame.cursorPosition().orElseThrow();
		assertThat(frame.area().contains(cursor.x(), cursor.y())).isTrue();
		assertThat(cursor.x()).isEqualTo(1 + DisplayWidth.of("end한"));
		assertThat(input().scrollRow()).isPositive();
		assertThat(text(frame)).contains("end한");
	}

	@Test void tinyTerminalDoesNotRenderAnOutOfBoundsCursor() {
		event(new PasteEvent("한글".repeat(20)));
		Frame frame = render(20, 6);
		assertThat(frame.cursorPosition()).isEmpty();
		for (String row : text(frame).split("\n"))
			assertThat(DisplayWidth.of(row)).isLessThanOrEqualTo(20);
	}

	private TextAreaState input() {
		return (TextAreaState) ReflectionTestUtils.getField(this.ui, "input");
	}
	private void event(Event event) {
		ReflectionTestUtils.invokeMethod(this.ui, "handle", event, this.runner, this.worker);
	}
	private Frame render(int width, int height) {
		Frame frame = Frame.forTesting(Buffer.empty(new Rect(0, 0, width, height)));
		ReflectionTestUtils.invokeMethod(this.ui, "render", frame);
		return frame;
	}
	private static String text(Frame frame) {
		StringBuilder result = new StringBuilder();
		for (int y = 0; y < frame.height(); y++) {
			for (int x = 0; x < frame.width(); x++) result.append(frame.buffer().get(x, y).symbol());
			result.append('\n');
		}
		return result.toString();
	}
	private void drain() throws Exception {
		this.worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
	}
}
