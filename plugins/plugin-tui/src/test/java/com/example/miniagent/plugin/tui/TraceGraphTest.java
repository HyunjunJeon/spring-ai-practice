package com.example.miniagent.plugin.tui;

import java.util.List;

import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.TraceEvent.Tokens;
import com.example.miniagent.core.model.ModelRef;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceGraphTest {

	private static final long MS = 1_000_000;

	/** 사이드바 안쪽 폭 (SIDEBAR_WIDTH 40 - 테두리 2) */
	private static final int WIDTH = 38;

	@Test
	void conversationActivityIsBoundedAndNeverIncludesArgumentsOrResultBodies() {
		var graph = new TraceGraph();
		graph.accept(new TraceEvent.TurnStarted(1, "fixture"));
		graph.accept(new TraceEvent.RoundStarted(1, 0, 0, 0));
		graph.accept(round(1, List.of("tool-a {}", "tool-b {\"secret\":\"PRIVATE_ARGS\"}",
				"tool-c {}", "tool-d {}"), 0));
		graph.accept(new TraceEvent.ToolFailed("tool-c", "E".repeat(2000), false, MS));
		var summary = graph.recentTools();
		assertThat(summary).hasSize(4);
		assertThat(summary.stream().map(TraceGraph.Line::plain).toList())
				.anyMatch(line -> line.contains("tool-b"))
				.noneMatch(line -> line.contains("tool-a") || line.contains("PRIVATE_ARGS"));
		assertThat(summary.get(2).segments()).anyMatch(segment -> segment.kind() == TraceGraph.Kind.PROBLEM);
		assertThat(summary).allSatisfy(line -> assertThat(DisplayWidth.of(line.plain())).isLessThan(160));
		graph.accept(new TraceEvent.TurnStarted(2, "next"));
		assertThat(graph.recentTools()).isEmpty();
	}

	@Test
	void conversationActivityKeepsRefusalAsProblemInsteadOfSuccessfulExecution() {
		var graph = new TraceGraph();
		graph.accept(new TraceEvent.TurnStarted(1, "fixture"));
		graph.accept(new TraceEvent.RoundStarted(1, 0, 0, 0));
		graph.accept(round(1, List.of("write_file {}"), 0));
		graph.accept(new TraceEvent.RoundStarted(2, 0, 0, 0));
		assertThat(graph.recentTools()).hasSize(2);
		assertThat(graph.recentTools().get(1).segments())
				.anyMatch(segment -> segment.kind() == TraceGraph.Kind.PROBLEM);
	}

	@Test
	void childPendingAndParentPartialDoNotReplaceParentCancelIdentity() {
		var graph = new TraceGraph();
		var ref = new ModelRef("fake", "parent");
		graph.accept(new TraceEvent.RunStateChanged("parent-run", "parent", "attempt",
				com.example.miniagent.core.run.RunState.RUNNING, ref));
		graph.accept(new TraceEvent.DelegationStateChanged("parent", "plan", "research", "child-run",
				new ModelRef("fake", "child"), com.example.miniagent.core.delegation.ChildStatus.RUNNING,
				com.example.miniagent.core.run.RunState.WAITING_APPROVAL, null, null));
		assertThat(graph.activeRunId()).isEqualTo("parent-run");
		assertThat(graph.runStatus()).contains("research=WAITING_APPROVAL", "fake/child").doesNotContain("COMPLETE");
		assertThat(graph.render(20, 18)).allSatisfy(line -> assertThat(DisplayWidth.of(line.plain())).isLessThanOrEqualTo(18));
		graph.accept(new TraceEvent.DelegationStateChanged("parent", "plan", null, null, null, null,
				null, com.example.miniagent.core.delegation.DelegationResult.Status.PARTIAL, null));
		assertThat(graph.runStatus()).contains("PARTIAL", "research=WAITING_APPROVAL").doesNotContain("COMPLETE");
		graph.accept(new TraceEvent.DelegationStateChanged("parent", "plan", "verify", "verify-run",
				ref, com.example.miniagent.core.delegation.ChildStatus.FAILED,
				com.example.miniagent.core.run.RunState.COMPLETED, null, "STALE"));
		assertThat(graph.runStatus()).contains("verify=FAILED", "STALE").doesNotContain("COMPLETED");
		assertThat(graph.turnCount()).isZero();
	}

	@Test
	void changeOutcomeWrapsAtNarrowWidthAndClearsAtNextBinding() {
		var graph = new TraceGraph();
		var id = java.util.UUID.randomUUID();
		var owner = new com.example.miniagent.core.change.ChangeOwner("session",
				com.example.miniagent.core.run.RunId.random(), "attempt", "turn");
		graph.accept(new TraceEvent.ChangeStateChanged(id, owner, "a".repeat(64),
				com.example.miniagent.core.change.ChangeStatus.APPLIED,
				com.example.miniagent.core.change.VerificationRunner.Code.STALE, java.util.UUID.randomUUID()));
		assertThat(graph.runStatus()).contains(id.toString(), "APPLIED", "STALE");
		assertThat(graph.activeRunId()).isNull();
		assertThat(graph.render(20, 18)).allSatisfy(line -> assertThat(DisplayWidth.of(line.plain())).isLessThanOrEqualTo(18));
		graph.accept(new TraceEvent.ModelBound("session", "turn", new ModelRef("fake", "next"),
				java.util.UUID.randomUUID(), "options", null, null));
		assertThat(graph.runStatus()).doesNotContain(id.toString(), "APPLIED", "STALE");
	}

	@Test
	void boundProviderRefIsKeptWhenResponseReportsDifferentModel() {
		TraceGraph graph = new TraceGraph();
		graph.accept(new TraceEvent.ModelBound("session", "turn", new ModelRef("fake", "captured"),
				java.util.UUID.randomUUID(), "options", null, null));
		graph.accept(new TraceEvent.TurnStarted(1, "input"));
		graph.accept(new TraceEvent.RoundStarted(1, 1, 0, 0));
		graph.accept(round(1, List.of(), 10));
		graph.accept(new TraceEvent.TurnFinished(1, 0, "", MS, Tokens.ZERO, "wrong", null, true));
		assertThat(text(graph, 100)).contains("fake/captured").doesNotContain("wrong", "gpt-6-luna");
	}

	@Test
	void toolTurnIsDrawnAsInputAiToolAnswerGraphWithData() {
		TraceGraph graph = new TraceGraph();
		playToolTurn(graph, "plugins 폴더에 뭐가 있어?");

		String all = text(graph, 100);

		assertThat(all).contains("#1", "gpt-6-luna · xhigh");
		assertThat(all).contains("사용자 입력", "plugins 폴더에 뭐가 있어?");
		assertThat(all).contains("AI 출력", "도구 호출 1개", "in 500 · cache 256 · out 30");
		assertThat(all).contains("⚙ list_dir", "입력 {\"path\":\"plugins\"}", "출력 147자", "14ms");
		assertThat(all).contains("AI 답변 ✓", "145자");
		assertThat(all).contains("도구 1회 · ✓ 답변 · 4.7초", "in 1,119 · cache 512 · out 67");
		// 노드 순서
		assertThat(all.indexOf("사용자 입력")).isLessThan(all.indexOf("AI 출력"));
		assertThat(all.indexOf("AI 출력")).isLessThan(all.indexOf("⚙ list_dir"));
		assertThat(all.indexOf("⚙ list_dir")).isLessThan(all.indexOf("AI 답변"));
	}

	@Test
	void boxBordersLineUpEvenWithKoreanText() {
		TraceGraph graph = new TraceGraph();
		playToolTurn(graph, "한글이 섞인 아주 긴 질문입니다. 상자 폭을 넘어가면 두 줄로 접히고 그 다음은 잘립니다.");

		graph.render(100, WIDTH)
			.stream()
			.map(TraceGraph.Line::plain)
			.filter(l -> l.startsWith(" ╭") || l.startsWith(" │") || l.startsWith(" ╰"))
			.forEach(l -> assertThat(DisplayWidth.of(l)).as(l).isEqualTo(WIDTH));
	}

	@Test
	void previousTurnsBecomeSummariesAndSessionAccumulates() {
		TraceGraph graph = new TraceGraph();
		playToolTurn(graph, "첫 번째 질문");
		playToolTurn(graph, "두 번째 질문");

		String all = text(graph, 100);

		assertThat(all).contains("#1 ✓ 도구 1회 · 4.7초", "#2 gpt-6-luna");
		assertThat(all).contains("두 번째 질문").doesNotContain("첫 번째 질문");
		assertThat(all).contains("세션 2턴 · 도구 2회", "in 2,238 · cache 1,024 · out 134");
	}

	@Test
	void requestedToolWaitsAndIsMarkedWhenDenied() {
		TraceGraph graph = new TraceGraph();
		graph.accept(new TraceEvent.TurnStarted(2, "ls 해줘"));
		graph.accept(new TraceEvent.RoundStarted(1, 2, 0, 6));
		graph.accept(round(1, List.of("bash {\"command\":\"ls\"}"), 0));

		assertThat(text(graph, 100)).contains("⚙ bash", "출력 기다리는 중…", "진행 중");

		// 권한 거부: 실행 기록(ToolExecuted/ToolFailed) 없이 다음 라운드가 시작된다.
		graph.accept(new TraceEvent.RoundStarted(2, 4, 2, 6));
		assertThat(text(graph, 100)).contains("실행 안 됨 (거부)", "응답 기다리는 중…");
	}

	@Test
	void failedAndCancelledToolsAreNotShownAsDenied() {
		TraceGraph graph = new TraceGraph();
		graph.accept(new TraceEvent.TurnStarted(2, "읽고 실행해줘"));
		graph.accept(new TraceEvent.RoundStarted(1, 2, 0, 6));
		graph.accept(round(1, List.of("read_file {\"path\":\"../x\"}", "bash {\"command\":\"sleep 60\"}"), 0));
		graph.accept(new TraceEvent.ToolFailed("read_file", "Access denied: /x is outside the allowed directories", false,
				3 * MS));
		graph.accept(new TraceEvent.ToolFailed("bash", "Interrupted", true, 900 * MS));
		graph.accept(new TraceEvent.RoundStarted(2, 5, 3, 6));

		String all = text(graph, 100);
		assertThat(all).contains("오류 Access denied", "취소됨", "도구 2회").doesNotContain("거부");
	}

	@Test
	void turnEndingWithoutAnswerIsMarkedInterrupted() {
		TraceGraph graph = new TraceGraph();
		graph.accept(new TraceEvent.TurnStarted(2, "안녕"));
		graph.accept(new TraceEvent.RoundStarted(1, 2, 0, 6));
		graph.accept(new TraceEvent.TurnFinished(1, 0, "", 1400 * MS, Tokens.ZERO, null, null, false));

		assertThat(text(graph, 100)).contains("중단됨 (응답 없음)", "⚠ 중단").doesNotContain("기다리는 중");
	}

	@Test
	void shortSidebarFoldsOldTurnsThenEarlyNodesButKeepsAnswerAndTotals() {
		TraceGraph graph = new TraceGraph();
		for (int i = 0; i < 3; i++) {
			playToolTurn(graph, "질문 " + i);
		}

		List<String> lines = graph.render(16, WIDTH).stream().map(TraceGraph.Line::plain).toList();
		String all = String.join("\n", lines);

		assertThat(lines.size()).isLessThanOrEqualTo(16);
		assertThat(all).contains("⋮ 이전", "⋮ 앞 노드", "AI 답변 ✓", "세션 3턴");
	}

	@Test
	void displayWidthCountsHangulAsTwoCells() {
		assertThat(DisplayWidth.of("abc")).isEqualTo(3);
		assertThat(DisplayWidth.of("모델 1")).isEqualTo(6);
		assertThat(DisplayWidth.padRight("답변", 6)).isEqualTo("답변  ");
		assertThat(DisplayWidth.truncate("아주긴도구이름입니다", 8)).isEqualTo("아주...");
	}

	private static void playToolTurn(TraceGraph graph, String question) {
		graph.accept(new TraceEvent.TurnStarted(2, question));
		graph.accept(new TraceEvent.RoundStarted(1, 6, 4, 6));
		graph.accept(round(1, List.of("list_dir {\"path\":\"plugins\"}"), 0));
		graph.accept(new TraceEvent.ToolExecuted("list_dir", 147, 14 * MS));
		graph.accept(new TraceEvent.RoundStarted(2, 8, 2, 6));
		graph.accept(round(2, List.of(), 145));
		graph.accept(new TraceEvent.TurnFinished(2, 1, "list_dir", 4700 * MS, new Tokens(1119, 67, 512), "gpt-6-luna",
				"xhigh", true));
	}

	private static TraceEvent.RoundFinished round(int round, List<String> toolCalls, int answerChars) {
		return new TraceEvent.RoundFinished(round, toolCalls, answerChars, null, 2000 * MS, new Tokens(500, 30, 256),
				"gpt-6-luna", "xhigh");
	}

	private static String text(TraceGraph graph, int maxLines) {
		return String.join("\n", graph.render(maxLines, WIDTH).stream().map(TraceGraph.Line::plain).toList());
	}

}
