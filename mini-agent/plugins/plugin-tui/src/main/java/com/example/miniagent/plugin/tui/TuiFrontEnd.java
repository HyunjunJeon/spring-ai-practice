package com.example.miniagent.plugin.tui;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.InteractiveFrontEnd;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;
import dev.tamboui.widgets.spinner.Spinner;
import dev.tamboui.widgets.spinner.SpinnerState;
import dev.tamboui.widgets.spinner.SpinnerStyle;
import org.jspecify.annotations.Nullable;

/**
 * 전체 화면 TUI. 왼쪽은 대화, 오른쪽은 트레이스 그래프, 아래는 입력창.
 *
 * <pre>
 * ╭ mini-agent · codex gpt-6-luna ─────────╮╭ Trace · turn 3 ──╮
 * │ › plugins 폴더에 뭐가 있어?             ││ ╭──────────────╮ │
 * │ ● 플러그인 9개: …                       ││ │ 입력          │ │
 * ╰─────────────────────────────────────────╯╰──────────────────╯
 * ⠋ 에이전트 작업 중…
 * ╭ 메시지 ─────────────────────────────────────────────────────╮
 * ╰─────────────────────────────────────────────────────────────╯
 * </pre>
 *
 * <p>스레드: TamboUI 의 그리기/키 처리는 {@code run()} 을 부른 스레드 하나에서만 돈다. 에이전트 호출은 오래
 * 걸리므로 별도 작업 스레드에서 돌리고, 끝나거나 대화/트레이스가 바뀌면 {@link #requestRedraw()} 로
 * 다음 틱에 다시 그리게 한다. 대화 로그와 트레이스 그래프는 동기화된 객체라 양쪽 스레드에서 안전하다.
 */
public class TuiFrontEnd implements InteractiveFrontEnd {

	private static final int SIDEBAR_WIDTH = 40;

	private final AgentInputHandler inputHandler;

	private final AgentSession session;

	private final TuiConsole console;

	private final ChatLog log;

	private final TraceGraph graph;

	private final Supplier<String> modelLabel;

	private final Path logFile;

	// ---- 그리기 스레드에서만 만지는 상태 ----
	private final TextInputState input = new TextInputState();

	private final SpinnerState spinnerState = new SpinnerState();

	private final Spinner spinner = Spinner.builder()
		.spinnerStyle(SpinnerStyle.DOTS)
		.style(Style.EMPTY.fg(Color.YELLOW))
		.build();

	private final List<String> history = new ArrayList<>();

	private int historyIndex;

	private @Nullable UUID draftQuestionId;

	private int scrollFromBottom;

	private int chatHeight = 10;

	private boolean showSidebar = true;

	private volatile boolean busy;
	private volatile boolean managedRunTask;

	private volatile boolean dirty;

	// 지금 돌고 있는 에이전트 작업. 비어 있는 입력창에서 Esc 를 누르면 취소한다(그리기 스레드에서만 만진다).
	private @Nullable Future<?> task;

	private final AtomicBoolean taskStarted = new AtomicBoolean();

	public TuiFrontEnd(AgentInputHandler inputHandler, AgentSession session, TuiConsole console, ChatLog log,
			TraceGraph graph, Supplier<String> modelLabel, Path logFile) {
		this.inputHandler = inputHandler;
		this.session = session;
		this.console = console;
		this.log = log;
		this.graph = graph;
		this.modelLabel = modelLabel;
		this.logFile = logFile;
	}

	/** 입력이 파이프로 들어오면(터미널이 아니면) TUI 를 띄울 수 없으므로 REPL 에 맡긴다. */
	@Override
	public boolean isAvailable() {
		return System.console() != null;
	}

	/** 아무 스레드에서나 부른다. 다음 틱(80ms)에 다시 그린다. */
	public void requestRedraw() {
		this.dirty = true;
	}

	@Override
	public void run() {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		PrintStream file = openLog();
		ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
			Thread thread = new Thread(r, "agent-worker");
			// 질문에 답을 기다리며 막혀 있어도 Ctrl+C 로 JVM 이 끝나도록 데몬 스레드로.
			thread.setDaemon(true);
			return thread;
		});
		// 출력을 바꾼 뒤에는 무엇이 실패하든 finally 에서 되돌린다. 설정 단계에서 실패해도 터미널 출력이
		// 로그 파일로 돌려진 채 남지 않게, 출력 교체부터 이 try 안에서 한다.
		try {
			// TUI 가 떠 있는 동안 System.out/err 에 뭔가 찍히면 화면이 깨진다. Spring 로그까지 파일로 돌린다.
			// TuiConfig.builder() 가 System.err 를 붙잡기 때문에 그 전에 바꿔야 한다.
			System.setOut(file);
			System.setErr(file);
			TuiConfig config = TuiConfig.builder()
				.mouseCapture(true)
				.bracketedPaste(true)
				.tickRate(Duration.ofMillis(80))
				// 0.5.0: 기본값(true)이면 입력창 커서가 보이지 않는다. 한글 입력기(IME)도 커서 위치에 조합 글자를 띄운다.
				.hideCursor(false)
				.build();
			this.console.activate(this::requestRedraw);
			try (TuiRunner tui = TuiRunner.create(config)) {
				this.log.add(ChatLog.Role.INFO,
						"Enter 전송 · ↑↓ 이전 입력 · PgUp/PgDn·휠 스크롤 · Tab 사이드바 · Esc 지우기/작업 취소 · /help · Ctrl+C 종료");
				tui.run((event, r) -> handle(event, r, worker), this::render);
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("TUI 실행 실패: " + ex.getMessage(), ex);
		}
		finally {
			this.console.deactivate();
			worker.shutdownNow();
			System.setOut(originalOut);
			System.setErr(originalErr);
			file.close();
		}
	}

	// ---------------- 이벤트 (그리기 스레드) ----------------

	private boolean handle(Event event, TuiRunner r, ExecutorService worker) {
		if (event instanceof TickEvent) {
			if (this.busy) {
				this.spinnerState.advance();
			}
			boolean redraw = this.dirty || this.busy;
			this.dirty = false;
			return redraw;
		}
		if (event instanceof MouseEvent mouse) {
			switch (mouse.kind()) {
				case SCROLL_UP -> this.scrollFromBottom += 3;
				case SCROLL_DOWN -> this.scrollFromBottom = Math.max(0, this.scrollFromBottom - 3);
				default -> {
					return false;
				}
			}
			return true;
		}
		if (event instanceof PasteEvent paste) {
			if (this.input.text().isEmpty()) this.draftQuestionId = this.console.pendingQuestionId();
			this.input.insert(DisplayWidth.nfc(paste.text().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')));
			return true;
		}
		if (!(event instanceof KeyEvent key)) {
			return false;
		}
		// isQuit() 은 기본 키 설정상 'q' 도 종료로 본다. 입력창에 q 를 칠 수 있어야 하므로 Ctrl+C 만 본다.
		if (key.isCtrlC()) {
			r.quit();
			return false;
		}
		int page = Math.max(1, this.chatHeight - 1);
		switch (key.code()) {
			case ENTER -> submit(r, worker);
			case BACKSPACE -> this.input.deleteBackward();
			case DELETE -> this.input.deleteForward();
			case LEFT -> this.input.moveCursorLeft();
			case RIGHT -> this.input.moveCursorRight();
			case HOME -> this.input.moveCursorToStart();
			case END -> this.input.moveCursorToEnd();
			case UP -> recall(-1);
			case DOWN -> recall(1);
			case PAGE_UP -> this.scrollFromBottom += page;
			case PAGE_DOWN -> this.scrollFromBottom = Math.max(0, this.scrollFromBottom - page);
			case TAB -> this.showSidebar = !this.showSidebar;
			case ESCAPE -> {
				// 입력이 있으면 지우고, 입력창이 비어 있는데 작업 중이면 작업을 취소한다.
				if (this.input.text().isEmpty() && this.busy) {
					cancelTask();
				}
				else {
					this.input.clear();
				}
			}
			case CHAR -> {
				if (key.hasCtrl() || key.hasAlt()) {
					return false;
				}
				if (this.input.text().isEmpty()) this.draftQuestionId = this.console.pendingQuestionId();
				this.input.insert(key.string());
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	private void submit(TuiRunner r, ExecutorService worker) {
		String text = DisplayWidth.nfc(this.input.text().strip());
		UUID questionId = this.draftQuestionId;
		this.draftQuestionId = null;
		this.input.clear();
		if (text.isEmpty()) {
			return;
		}
		this.scrollFromBottom = 0;
		if (AgentInputHandler.isControlInput(text)) {
			this.log.add(ChatLog.Role.USER, text);
			this.inputHandler.handle(text);
			requestRedraw();
			return;
		}
		if (questionId != null || this.console.pendingQuestion() != null) {
			// 작업 스레드가 console.readLine(...) 에서 기다리는 중: 답을 넘긴다.
			if (!this.console.answer(questionId, text)) {
				this.log.add(ChatLog.Role.INFO, "질문이 변경되었거나 답변 ID가 일치하지 않습니다.");
			}
			return;
		}
		if (text.equals("/exit") || text.equals("/quit")) {
			r.quit();
			return;
		}
		if (this.busy) {
			this.log.add(ChatLog.Role.INFO, "에이전트가 작업 중입니다. 끝난 뒤에 다시 보내세요.");
			return;
		}
		this.history.add(text);
		this.historyIndex = this.history.size();
		this.log.add(ChatLog.Role.USER, text);
		this.busy = true;
		this.managedRunTask = text.startsWith("/run start ") || text.startsWith("/run resume ");
		this.taskStarted.set(false);
		this.task = worker.submit(() -> {
			this.taskStarted.set(true);
			try {
				this.inputHandler.handle(text);
			}
			finally {
				this.busy = false;
				requestRedraw();
			}
		});
	}

	/**
	 * 작업 스레드를 인터럽트한다. bash 는 셸과 자식 프로세스를 정리하고, Codex 응답 대기와 권한 질문 대기는 바로 멈춘다.
	 * 이미 끝난 도구 실행(파일 쓰기 등)은 되돌리지 않는다.
	 */
	private void cancelTask() {
		String runId = this.graph.activeRunId();
		if (this.busy && this.managedRunTask && runId != null && this.taskStarted.get()) {
			// Persist cancellation intent through the executor before its worker is interrupted.
			this.inputHandler.handle("/run cancel " + runId);
			return;
		}
		Future<?> running = this.task;
		if (running == null || !running.cancel(true)) {
			return;
		}
		this.console.cancelQuestion();
		this.log.add(ChatLog.Role.INFO, "작업 취소를 요청했습니다.");
		// 아직 시작하지 않은 작업은 finally 가 돌지 않으므로 여기서 상태를 되돌린다.
		if (!this.taskStarted.get()) {
			this.busy = false;
		}
	}

	private void recall(int direction) {
		if (this.history.isEmpty()) {
			return;
		}
		this.historyIndex = Math.max(0, Math.min(this.history.size(), this.historyIndex + direction));
		this.input.clear();
		if (this.historyIndex < this.history.size()) {
			this.input.insert(this.history.get(this.historyIndex));
		}
	}

	// ---------------- 그리기 (그리기 스레드) ----------------

	private void render(Frame frame) {
		int headerHeight = Math.min(Math.max(1, frame.area().height() / 3),
				headerLines(frame.area().width()).size());
		List<Rect> rows = Layout.vertical()
			.constraints(Constraint.length(headerHeight), Constraint.fill(), Constraint.length(1), Constraint.length(3))
			.split(frame.area());
		boolean sidebar = this.showSidebar && frame.area().width() >= SIDEBAR_WIDTH + 40;
		List<Rect> body = sidebar
				? Layout.horizontal().constraints(Constraint.fill(), Constraint.length(SIDEBAR_WIDTH)).split(rows.get(1))
				: List.of(rows.get(1));

		renderHeader(frame, rows.get(0));
		renderChat(frame, body.get(0));
		if (sidebar) {
			renderTrace(frame, body.get(1));
		}
		renderStatus(frame, rows.get(2));
		renderInput(frame, rows.get(3));
	}

	private void renderHeader(Frame frame, Rect area) {
		frame.renderWidget(Paragraph.builder().text(Text.from(headerLines(area.width()))).build(), area);
	}

	private List<Line> headerLines(int width) {
		return wrap(List.of(Line.from(
				Span.styled(" mini-agent ", Style.EMPTY.fg(Color.BLACK).bg(Color.CYAN).bold()),
				Span.styled("  " + this.modelLabel.get(), Style.EMPTY.fg(Color.YELLOW)),
				Span.styled("  session " + this.session.id(), Style.EMPTY.fg(Color.DARK_GRAY)))), width);
	}

	private void renderChat(Frame frame, Rect area) {
		Rect inner = Block.bordered().inner(area);
		// 테두리에 글자가 붙지 않게 왼쪽 한 칸을 띄운다.
		List<Line> wrapped = wrap(transcript(), inner.width() - 1).stream()
			.map(line -> {
				List<Span> spans = new ArrayList<>();
				spans.add(Span.raw(" "));
				spans.addAll(line.spans());
				return Line.from(spans);
			})
			.toList();
		int maxScroll = Math.max(0, wrapped.size() - inner.height());
		this.scrollFromBottom = Math.min(this.scrollFromBottom, maxScroll);
		this.chatHeight = inner.height();
		String title = (this.scrollFromBottom > 0) ? " 대화 (↑" + this.scrollFromBottom + ") " : " 대화 ";
		Block block = Block.builder()
			.borders(Borders.ALL)
			.borderType(BorderType.ROUNDED)
			.title(title)
			.borderColor(Color.DARK_GRAY)
			.build();
		frame.renderWidget(Paragraph.builder()
			.text(Text.from(wrapped))
			.block(block)
			.overflow(Overflow.CLIP)
			.scroll(maxScroll - this.scrollFromBottom)
			.build(), area);
	}

	private void renderTrace(Frame frame, Rect area) {
		Rect inner = Block.bordered().inner(area);
		List<Line> lines = new ArrayList<>();
		this.graph.render(inner.height(), inner.width()).forEach(line -> lines.add(toLine(line)));
		int turns = this.graph.turnCount();
		String title = (turns > 0) ? " Trace · " + turns + "턴 " : " Trace ";
		Block block = Block.builder()
			.borders(Borders.ALL)
			.borderType(BorderType.ROUNDED)
			.title(Title.from(Line.from(Span.styled(title, Style.EMPTY.fg(Color.MAGENTA).bold()))))
			.borderColor(Color.DARK_GRAY)
			.build();
		frame.renderWidget(Paragraph.builder().text(Text.from(lines)).block(block).overflow(Overflow.CLIP).build(),
				area);
	}

	private void renderStatus(Frame frame, Rect area) {
		String question = this.console.pendingQuestion();
		if (question != null) {
			frame.renderWidget(Paragraph.from(Line.styled(" ? " + question, Style.EMPTY.fg(Color.YELLOW).bold())), area);
			return;
		}
		if (this.busy) {
			List<Rect> cols = Layout.horizontal().constraints(Constraint.length(3), Constraint.fill()).split(area);
			frame.renderStatefulWidget(this.spinner, cols.get(0), this.spinnerState);
			frame.renderWidget(Paragraph.from(Line.styled(this.graph.runStatus()
					+ " · 작업 중… (Esc 취소)", Style.EMPTY.fg(Color.YELLOW))), cols.get(1));
			return;
		}
		String status = " " + this.graph.runStatus();
		String hints = " · Tab 사이드바 · Ctrl+C 종료";
		if (DisplayWidth.of(status + hints) <= area.width()) status += hints;
		frame.renderWidget(Paragraph.from(Line.styled(status, Style.EMPTY.fg(Color.DARK_GRAY))),
				area);
	}

	private void renderInput(Frame frame, Rect area) {
		boolean asking = this.console.pendingQuestion() != null;
		TextInput widget = TextInput.builder()
			.block(Block.builder()
				.borders(Borders.ALL)
				.borderType(BorderType.ROUNDED)
				.title(asking ? " 답변 " : " 메시지 ")
				.borderColor(asking ? Color.YELLOW : Color.CYAN)
				.build())
			.placeholder(asking ? "답을 입력하고 Enter" : "메시지 또는 /명령을 입력하세요")
			.build();
		widget.renderWithCursor(area, frame.buffer(), this.input, frame);
	}

	private List<Line> transcript() {
		List<Line> lines = new ArrayList<>();
		for (ChatLog.Entry entry : this.log.snapshot()) {
			String[] parts = DisplayWidth.nfc(entry.text()).split("\n", -1);
			switch (entry.role()) {
				case USER -> {
					lines.add(Line.from(Span.raw("")));
					lines.add(Line.from(Span.styled("› ", Style.EMPTY.fg(Color.CYAN).bold()),
							Span.styled(parts[0], Style.EMPTY.fg(Color.CYAN).bold())));
					for (int i = 1; i < parts.length; i++) {
						lines.add(Line.styled("  " + parts[i], Style.EMPTY.fg(Color.CYAN)));
					}
				}
				case AGENT -> {
					lines.add(Line.from(Span.styled("● ", Style.EMPTY.fg(Color.GREEN)), Span.raw(parts[0])));
					for (int i = 1; i < parts.length; i++) {
						lines.add(Line.from(Span.raw("  " + parts[i])));
					}
				}
				case QUESTION -> {
					for (String part : parts) {
						lines.add(Line.styled("? " + part, Style.EMPTY.fg(Color.YELLOW).bold()));
					}
				}
				case ERROR -> {
					for (String part : parts) {
						lines.add(Line.styled(part, Style.EMPTY.fg(Color.RED)));
					}
				}
				case INFO -> {
					for (String part : parts) {
						lines.add(Line.styled(part, Style.EMPTY.fg(Color.DARK_GRAY)));
					}
				}
				case TEXT -> {
					for (String part : parts) {
						lines.add(Line.from(Span.raw(part)));
					}
				}
			}
		}
		return lines;
	}

	private static Line toLine(TraceGraph.Line line) {
		List<Span> spans = new ArrayList<>();
		for (TraceGraph.Segment segment : line.segments()) {
			spans.add(Span.styled(segment.text(), style(segment.kind())));
		}
		return Line.from(spans);
	}

	private static Style style(TraceGraph.Kind kind) {
		return switch (kind) {
			case INPUT -> Style.EMPTY.fg(Color.LIGHT_BLUE);
			case AI -> Style.EMPTY.fg(Color.CYAN);
			case TOOL -> Style.EMPTY.fg(Color.YELLOW);
			case ANSWER -> Style.EMPTY.fg(Color.GREEN);
			case PROBLEM -> Style.EMPTY.fg(Color.RED);
			case RUNNING -> Style.EMPTY.fg(Color.MAGENTA).bold();
			case TURN -> Style.EMPTY.fg(Color.MAGENTA).bold();
			case MODEL_NAME -> Style.EMPTY.fg(Color.YELLOW);
			case BODY -> Style.EMPTY.fg(Color.WHITE);
			case NOTE -> Style.EMPTY.fg(Color.GRAY);
			case TOKENS, RULE -> Style.EMPTY.fg(Color.DARK_GRAY);
		};
	}

	/** 표시 폭(한글 2칸) 기준으로 줄을 접는다. 스크롤 계산을 정확히 하려고 직접 접는다. */
	static List<Line> wrap(List<Line> source, int width) {
		List<Line> out = new ArrayList<>();
		if (width <= 0) {
			return out;
		}
		for (Line line : source) {
			List<Span> current = new ArrayList<>();
			int used = 0;
			for (Span span : line.spans()) {
				StringBuilder sb = new StringBuilder();
				String content = span.content();
				for (int i = 0; i < content.length();) {
					int cp = content.codePointAt(i);
					int w = CharWidth.of(cp);
					if (used + w > width && used > 0) {
						if (sb.length() > 0) {
							current.add(new Span(sb.toString(), span.style()));
							sb.setLength(0);
						}
						out.add(Line.from(current));
						current = new ArrayList<>();
						used = 0;
					}
					sb.appendCodePoint(cp);
					used += w;
					i += Character.charCount(cp);
				}
				if (sb.length() > 0) {
					current.add(new Span(sb.toString(), span.style()));
				}
			}
			out.add(Line.from(current));
		}
		return out;
	}

	private PrintStream openLog() {
		try {
			Files.createDirectories(this.logFile.getParent());
			return new PrintStream(new FileOutputStream(this.logFile.toFile(), true), true, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
