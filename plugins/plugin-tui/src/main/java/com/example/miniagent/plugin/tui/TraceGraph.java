package com.example.miniagent.plugin.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.model.command.ModelDisplay;
import dev.tamboui.text.CharWidth;
import org.jspecify.annotations.Nullable;

/**
 * 트레이스 이벤트를 그래프로 그린다. 화면 라이브러리와 무관한 순수 로직(단위 테스트 대상).
 *
 * <p>노드는 네 종류다: 사용자 입력 → AI 출력(도구 요청) → Tool 입/출력 → … → AI 답변.
 * 마지막 턴은 상자 그래프로 펼치고, 지난 턴은 위쪽에 두 줄 요약으로 쌓는다. 맨 아래는 세션 합계.
 *
 * <pre>
 *  #1 ✓ 도구 1회 · 4.3초
 *     in 1,119 · cache 0 · out 31
 *  ───────────────────────────────────
 *  #2 gpt-6-luna · xhigh
 *  ╭───────────────────────────────────╮
 *  │ 사용자 입력                         │
 *  │ 그 중 이름에 model 이 들어간 것만   │
 *  ╰─────────────────┬─────────────────╯
 *  ╭─────────────────▼─────────────────╮
 *  │ AI 출력                       2.1초 │
 *  │ 도구 호출 1개                       │
 *  │ in 590 · cache 0 · out 12           │
 *  ╰─────────────────┬─────────────────╯
 *  ╭─────────────────▼─────────────────╮
 *  │ ⚙ list_dir                     15ms │
 *  │ 입력 {"path":"plugins"}             │
 *  │ 출력 147자                          │
 *  ╰─────────────────┬─────────────────╯
 *  ╭─────────────────▼─────────────────╮
 *  │ AI 답변 ✓                     2.2초 │
 *  │ 145자                               │
 *  │ in 593 · cache 0 · out 20           │
 *  ╰───────────────────────────────────╯
 *  도구 1회 · ✓ 답변 · 4.3초
 *  in 1,183 · cache 0 · out 32
 * </pre>
 *
 * 이벤트는 에이전트 작업 스레드에서, 그리기는 UI 스레드에서 일어나므로 모든 공개 메서드를 동기화한다.
 */
public class TraceGraph {

	/** 조각 종류. 화면에서 색으로 구분한다. */
	public enum Kind {

		/** 노드 테두리/제목: 노드 종류별 색 */
		INPUT, AI, TOOL, ANSWER,

		/** 상태 */
		PROBLEM, RUNNING,

		/** 텍스트 */
		TURN, MODEL_NAME, BODY, TOKENS, NOTE, RULE

	}

	public record Segment(String text, Kind kind) {
	}

	public record Line(List<Segment> segments) {

		public String plain() {
			StringBuilder sb = new StringBuilder();
			this.segments.forEach(s -> sb.append(s.text()));
			return sb.toString();
		}

	}

	private static final int MAX_TURNS = 50;

	private enum Status {

		RUNNING, ANSWERED, INTERRUPTED

	}

	/** 상자 하나. 본문 줄마다 종류(BODY/TOKENS/PROBLEM)를 따로 둔다. */
	private static final class Node {

		final Kind kind;

		String title;

		String right = "";

		final List<Segment> body = new ArrayList<>();

		boolean running;

		boolean problem;

		/** 도구 노드: 결과를 기다리는 도구 이름 */
		@Nullable String toolName;

		Node(Kind kind, String title, boolean running) {
			this.kind = kind;
			this.title = title;
			this.running = running;
		}

		Node body(String text, Kind kind) {
			this.body.add(new Segment(text, kind));
			return this;
		}

	}

	private static final class Turn {

		final int number;

		final List<Node> nodes = new ArrayList<>();

		Status status = Status.RUNNING;

		@Nullable String model;

		@Nullable String effort;

		boolean bound;

		int tools;

		TraceEvent.Tokens tokens = TraceEvent.Tokens.ZERO;

		long durationNanos;

		Turn(int number) {
			this.number = number;
		}

	}

	private final Deque<Turn> turns = new ArrayDeque<>();

	private int turnCount;

	private int sessionTools;

	private TraceEvent.Tokens sessionTokens = TraceEvent.Tokens.ZERO;

	private TraceEvent.@Nullable ModelBound pendingBinding;
	private TraceEvent.@Nullable RunStateChanged runState;
	private TraceEvent.@Nullable ChangeStateChanged changeState;
	private final java.util.Map<String, TraceEvent.DelegationStateChanged> children = new java.util.LinkedHashMap<>();
	private TraceEvent.@Nullable DelegationStateChanged delegation;

	public synchronized String runStatus() {
		if (this.delegation != null) {
			String state = this.children.values().stream().map(e -> e.childId() + "="
					+ (e.runState() == com.example.miniagent.core.run.RunState.WAITING_APPROVAL
							|| e.runState() == com.example.miniagent.core.run.RunState.INTERRUPTED
							? e.runState() : e.childStatus())
					+ (e.childModel() == null ? "" : "(" + ModelDisplay.ref(e.childModel()) + ")")
					+ (e.failureCode() == null ? "" : ":" + e.failureCode()))
					.collect(java.util.stream.Collectors.joining(" "));
			return "delegate=" + this.delegation.planId() + " "
					+ (this.delegation.resultStatus() == null ? state : this.delegation.resultStatus() + " " + state);
		}
		if (this.changeState != null) return "change=" + this.changeState.changeSetId()
				+ " " + this.changeState.status()
				+ (this.changeState.verification() == null ? "" : " verify=" + this.changeState.verification());
		return this.runState == null ? "준비됨" : "run=" + this.runState.runId()
				+ " attempt=" + this.runState.attemptId() + " " + this.runState.state()
				+ " " + ModelDisplay.ref(this.runState.ref());
	}

	public synchronized @Nullable String activeRunId() {
		return this.runState != null && this.runState.state() == com.example.miniagent.core.run.RunState.RUNNING
				? this.runState.runId() : null;
	}

	public synchronized void accept(TraceEvent event) {
		switch (event) {
			case TraceEvent.DelegationStateChanged e -> {
				if (this.delegation == null || !this.delegation.planId().equals(e.planId())) this.children.clear();
				this.delegation = e;
				if (e.childId() != null) this.children.put(e.childId(), e);
			}
			case TraceEvent.ChangeStateChanged e -> this.changeState = e;
			case TraceEvent.RunStateChanged e -> { this.runState = e; this.changeState = null; }
			case TraceEvent.ModelBound e -> { this.pendingBinding = e; this.changeState = null; }
			case TraceEvent.TurnStarted e -> {
				Turn turn = new Turn(++this.turnCount);
				if (this.pendingBinding != null) {
					turn.model = ModelDisplay.ref(this.pendingBinding.ref());
					turn.bound = true;
					this.pendingBinding = null;
				}
				Node input = new Node(Kind.INPUT, "사용자 입력", false);
				input.body(DisplayWidth.nfc(e.userText().strip().replaceAll("\\s+", " ")), Kind.BODY);
				turn.nodes.add(input);
				this.turns.addLast(turn);
				while (this.turns.size() > MAX_TURNS) {
					this.turns.removeFirst();
				}
			}
			case TraceEvent.RoundStarted e -> current().ifPresent(turn -> {
				// 실행 기록(ToolExecuted/ToolFailed)이 없는 도구는 권한 거부 등으로 실행되지 않은 것이다.
				closePendingTools(turn, "실행 안 됨 (거부)");
				turn.nodes.add(new Node(Kind.AI, "AI", true).body("응답 기다리는 중…", Kind.RUNNING));
			});
			case TraceEvent.RoundFinished e -> current().ifPresent(turn -> {
				turn.tokens = turn.tokens.plus(e.tokens());
				if (!turn.bound) {
					turn.model = (e.model() != null) ? e.model() : turn.model;
				}
				turn.effort = (e.reasoningEffort() != null) ? e.reasoningEffort() : turn.effort;
				Node ai = runningAi(turn);
				if (ai == null) {
					return;
				}
				ai.running = false;
				ai.right = TraceEvent.duration(e.durationNanos());
				ai.body.clear();
				if (!e.toolCalls().isEmpty()) {
					ai.title = "AI 출력";
					ai.body("도구 호출 " + e.toolCalls().size() + "개", Kind.BODY);
					ai.body(e.tokens().format(), Kind.TOKENS);
					for (String call : e.toolCalls()) {
						String[] parts = call.split(" ", 2);
						Node tool = new Node(Kind.TOOL, "⚙ " + parts[0], true);
						tool.toolName = parts[0];
						tool.body("입력 " + ((parts.length > 1) ? parts[1] : "{}"), Kind.BODY);
						tool.body("출력 기다리는 중…", Kind.RUNNING);
						turn.nodes.add(tool);
					}
				}
				else {
					boolean abnormal = e.abnormalFinish() != null;
					ai.title = abnormal ? "AI 답변 ⚠" : "AI 답변 ✓";
					ai.problem = abnormal;
					ai.body(e.answerChars() + "자" + (abnormal ? " · 종료 사유 " + e.abnormalFinish() : ""),
							abnormal ? Kind.PROBLEM : Kind.BODY);
					ai.body(e.tokens().format(), Kind.TOKENS);
				}
			});
			case TraceEvent.ToolExecuted e -> current().ifPresent(turn -> {
				turn.tools++;
				for (Node node : turn.nodes) {
					if (node.kind == Kind.TOOL && node.running && e.name().equals(node.toolName)) {
						node.running = false;
						node.right = TraceEvent.duration(e.durationNanos());
						node.body.set(1, new Segment("출력 " + "%,d".formatted(e.resultChars()) + "자", Kind.BODY));
						break;
					}
				}
			});
			case TraceEvent.ToolFailed e -> current().ifPresent(turn -> {
				turn.tools++;
				for (Node node : turn.nodes) {
					if (node.kind == Kind.TOOL && node.running && e.name().equals(node.toolName)) {
						node.running = false;
						node.problem = true;
						node.right = TraceEvent.duration(e.durationNanos());
						String what = e.cancelled() ? "취소됨"
								: "오류 " + DisplayWidth.nfc(e.error().strip().replaceAll("\\s+", " "));
						node.body.set(1, new Segment(what, Kind.PROBLEM));
						break;
					}
				}
			});
			case TraceEvent.TurnFinished e -> current().ifPresent(turn -> {
				closePendingTools(turn, "실행 안 됨");
				Node ai = runningAi(turn);
				if (ai != null) {
					ai.running = false;
					ai.problem = true;
					ai.title = "AI ⚠";
					ai.body.clear();
					ai.body("중단됨 (응답 없음)", Kind.PROBLEM);
				}
				turn.tokens = e.tokens();
				if (!turn.bound) {
					turn.model = (e.model() != null) ? e.model() : turn.model;
				}
				turn.effort = (e.reasoningEffort() != null) ? e.reasoningEffort() : turn.effort;
				turn.tools = e.toolCount();
				turn.durationNanos = e.durationNanos();
				turn.status = e.answered() ? Status.ANSWERED : Status.INTERRUPTED;
				this.sessionTools += e.toolCount();
				this.sessionTokens = this.sessionTokens.plus(e.tokens());
			});
		}
	}

	public synchronized int turnCount() {
		return this.turnCount;
	}

	/** A bounded, result-free activity card for the conversation; full details stay in Trace. */
	public synchronized List<Line> recentTools() {
		Turn turn = this.turns.peekLast();
		if (turn == null) return List.of();
		List<Node> tools = turn.nodes.stream().filter(node -> node.kind == Kind.TOOL).toList();
		if (tools.isEmpty()) return List.of();
		List<Line> result = new ArrayList<>();
		result.add(line(seg("도구 · #" + turn.number + " · 최근 " + Math.min(3, tools.size())
				+ "/" + tools.size() + " · Ctrl+T 상세", Kind.NOTE)));
		for (Node tool : tools.subList(Math.max(0, tools.size() - 3), tools.size())) {
			Segment status = tool.body.get(1);
			String summary = CharWidth.substringByWidth(status.text(), 96);
			if (CharWidth.of(status.text()) > 96) summary += "…";
			result.add(line(seg(tool.title + " · ", Kind.TOOL), seg(summary, status.kind()),
					seg(tool.right.isEmpty() ? "" : " · " + tool.right, Kind.NOTE)));
		}
		return List.copyOf(result);
	}

	/**
	 * 그릴 줄 목록. 위에서부터: 지난 턴 요약 → 마지막 턴 그래프 → 세션 합계.
	 * 높이가 모자라면 지난 턴 요약부터 빼고, 그래도 모자라면 그래프의 앞쪽 노드를 접는다.
	 */
	public synchronized List<Line> render(int maxLines, int width) {
		if (this.turns.isEmpty()) {
			if (this.runState != null || this.changeState != null || this.delegation != null) return wrapText(runStatus(), Math.max(1, width), Math.max(1, maxLines))
					.stream().map(text -> line(seg(text, Kind.NOTE))).toList();
			return List.of(line(seg(" 질문을 보내면 이 턴의 흐름이", Kind.NOTE)),
					line(seg(" 사용자 입력 → AI → Tool → AI 답변", Kind.NOTE)), line(seg(" 그래프로 그려집니다.", Kind.NOTE)));
		}
		List<Line> footer = sessionLines();
		Turn latest = this.turns.peekLast();
		List<Line> graph = turnGraph(latest, width, Math.max(0, maxLines - footer.size() - 1));

		// 지난 턴 요약: 최신 것부터 거꾸로, 남는 공간만큼
		int budget = maxLines - footer.size() - graph.size() - 1;
		List<Turn> older = new ArrayList<>(this.turns);
		older.remove(older.size() - 1);
		List<Line> history = new ArrayList<>();
		int shown = 0;
		for (int i = older.size() - 1; i >= 0; i--) {
			List<Line> summary = historyLines(older.get(i));
			if (history.size() + summary.size() + 1 > budget) {
				break;
			}
			history.addAll(0, summary);
			shown++;
		}
		int hidden = (this.turnCount - this.turns.size()) + (older.size() - shown);

		List<Line> lines = new ArrayList<>();
		if (hidden > 0) {
			lines.add(line(seg(" ⋮ 이전 " + hidden + "턴", Kind.NOTE)));
		}
		lines.addAll(history);
		if (!history.isEmpty() || hidden > 0) {
			lines.add(rule(width));
		}
		lines.addAll(graph);
		lines.add(rule(width));
		lines.addAll(footer);
		if (this.runState != null || this.delegation != null) lines.add(line(seg(DisplayWidth.truncate(runStatus(), width), Kind.NOTE)));
		return lines;
	}

	// ---------------- 그래프 ----------------

	private static List<Line> turnGraph(Turn turn, int width, int maxLines) {
		List<Line> header = new ArrayList<>();
		String model = (turn.model != null) ? turn.model : "모델 정보 없음";
		if (turn.effort != null) {
			model += " · " + turn.effort;
		}
		String number = " #" + turn.number + " ";
		header.add(line(seg(number, Kind.TURN),
				seg(DisplayWidth.truncate(model, Math.max(1, width - DisplayWidth.of(number) - 1)), Kind.MODEL_NAME)));

		List<Line> footer = turnSummary(turn);

		List<List<Line>> boxes = new ArrayList<>();
		for (int i = 0; i < turn.nodes.size(); i++) {
			boxes.add(box(turn.nodes.get(i), i > 0, i < turn.nodes.size() - 1, width));
		}
		int budget = Math.max(0, maxLines - header.size() - footer.size() - 1);
		int used = 0;
		int start = boxes.size();
		while (start > 0 && used + boxes.get(start - 1).size() <= budget) {
			used += boxes.get(--start).size();
		}
		List<Line> lines = new ArrayList<>(header);
		if (start > 0) {
			lines.add(line(seg(" ⋮ 앞 노드 " + start + "개", Kind.NOTE)));
		}
		for (int i = start; i < boxes.size(); i++) {
			lines.addAll(boxes.get(i));
		}
		lines.addAll(footer);
		return lines;
	}

	/**
	 * 상자 하나. 위아래 상자는 테두리 가운데의 ┬ / ▼ 로 이어진다(가운데 선 한 줄을 아끼려고 바로 붙인다).
	 */
	private static List<Line> box(Node node, boolean incoming, boolean outgoing, int width) {
		int inner = Math.max(6, width - 3);
		int content = inner - 2;
		int mid = inner / 2;
		Kind border = node.running ? Kind.RUNNING : node.problem ? Kind.PROBLEM : node.kind;
		List<Line> lines = new ArrayList<>();

		String top = incoming ? "╭" + "─".repeat(mid) + "▼" + "─".repeat(inner - mid - 1) + "╮"
				: "╭" + "─".repeat(inner) + "╮";
		lines.add(line(seg(" " + top, border)));

		String right = node.right;
		int titleWidth = content - (right.isEmpty() ? 0 : DisplayWidth.of(right) + 1);
		String title = DisplayWidth.padRight(DisplayWidth.truncate(node.title, titleWidth), titleWidth);
		lines.add(line(seg(" │ ", border), seg(title, border), seg(right.isEmpty() ? "" : " " + right, Kind.NOTE),
				seg(" │", border)));

		for (Segment body : node.body) {
			for (String text : wrapText(body.text(), content, (node.kind == Kind.INPUT) ? 2 : 1)) {
				lines.add(line(seg(" │ ", border), seg(DisplayWidth.padRight(text, content), body.kind()),
						seg(" │", border)));
			}
		}

		String bottom = outgoing ? "╰" + "─".repeat(mid) + "┬" + "─".repeat(inner - mid - 1) + "╯"
				: "╰" + "─".repeat(inner) + "╯";
		lines.add(line(seg(" " + bottom, border)));
		return lines;
	}

	private static List<Line> turnSummary(Turn turn) {
		String outcome = switch (turn.status) {
			case RUNNING -> "진행 중";
			case ANSWERED -> "✓ 답변";
			case INTERRUPTED -> "⚠ 중단";
		};
		Kind outcomeKind = switch (turn.status) {
			case RUNNING -> Kind.RUNNING;
			case ANSWERED -> Kind.ANSWER;
			case INTERRUPTED -> Kind.PROBLEM;
		};
		String time = (turn.status == Status.RUNNING) ? "" : " · " + TraceEvent.duration(turn.durationNanos);
		List<Line> lines = new ArrayList<>();
		lines.add(line(seg(" 도구 " + turn.tools + "회 · ", Kind.BODY), seg(outcome, outcomeKind), seg(time, Kind.BODY)));
		lines.add(line(seg(" " + turn.tokens.format(), Kind.TOKENS)));
		return lines;
	}

	private static List<Line> historyLines(Turn turn) {
		String mark = (turn.status == Status.ANSWERED) ? "✓" : (turn.status == Status.INTERRUPTED) ? "⚠" : "…";
		Kind markKind = (turn.status == Status.ANSWERED) ? Kind.ANSWER
				: (turn.status == Status.INTERRUPTED) ? Kind.PROBLEM : Kind.RUNNING;
		String time = (turn.status == Status.RUNNING) ? "" : " · " + TraceEvent.duration(turn.durationNanos);
		return List.of(
				line(seg(" #" + turn.number + " ", Kind.TURN), seg(mark, markKind),
						seg(" 도구 " + turn.tools + "회" + time, Kind.BODY)),
				line(seg("    " + turn.tokens.format(), Kind.TOKENS)));
	}

	private List<Line> sessionLines() {
		int tools = this.sessionTools;
		TraceEvent.Tokens tokens = this.sessionTokens;
		Optional<Turn> running = current();
		if (running.isPresent()) {
			// 진행 중인 턴도 지금까지의 값을 더해서 보여준다.
			tools += running.get().tools;
			tokens = tokens.plus(running.get().tokens);
		}
		return List.of(line(seg(" 세션 %d턴 · 도구 %d회".formatted(this.turnCount, tools), Kind.TURN)),
				line(seg(" " + tokens.format(), Kind.TOKENS)));
	}

	// ---------------- helpers ----------------

	/** 표시 폭으로 접는다. {@code maxLines} 를 넘으면 마지막 줄을 "…" 로 자른다. */
	private static List<String> wrapText(String text, int width, int maxLines) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		int used = 0;
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			String ch = new String(Character.toChars(cp));
			int w = DisplayWidth.of(ch);
			if (used + w > width) {
				lines.add(current.toString());
				current.setLength(0);
				used = 0;
			}
			current.append(ch);
			used += w;
			i += Character.charCount(cp);
		}
		lines.add(current.toString());
		if (lines.size() > maxLines) {
			List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
			cut.set(maxLines - 1, DisplayWidth.truncate(cut.get(maxLines - 1) + "…", width));
			return cut;
		}
		return lines;
	}

	private Optional<Turn> current() {
		return Optional.ofNullable(this.turns.peekLast()).filter(t -> t.status == Status.RUNNING);
	}

	private static @Nullable Node runningAi(Turn turn) {
		for (int i = turn.nodes.size() - 1; i >= 0; i--) {
			Node node = turn.nodes.get(i);
			if (node.kind == Kind.AI && node.running) {
				return node;
			}
		}
		return null;
	}

	private static void closePendingTools(Turn turn, String output) {
		for (Node node : turn.nodes) {
			if (node.kind == Kind.TOOL && node.running) {
				node.running = false;
				node.problem = true;
				node.body.set(1, new Segment(output, Kind.PROBLEM));
			}
		}
	}

	private static Line rule(int width) {
		return line(seg(" " + "─".repeat(Math.max(0, width - 2)), Kind.RULE));
	}

	private static Segment seg(String text, Kind kind) {
		return new Segment(text, kind);
	}

	private static Line line(Segment... segments) {
		return new Line(List.of(segments));
	}

}
