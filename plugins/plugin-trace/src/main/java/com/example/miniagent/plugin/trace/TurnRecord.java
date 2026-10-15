package com.example.miniagent.plugin.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.example.miniagent.core.TraceEvent;
import org.jspecify.annotations.Nullable;

/**
 * 턴 하나의 기록. 루프 바깥 어드바이저가 만들고, 안쪽 어드바이저와 도구 인터셉터가 채운다.
 */
final class TurnRecord {

	final long startNanos = System.nanoTime();

	/** 메모리 등이 붙기 전, 사용자가 보낸 프롬프트의 메시지 수. */
	final int initialMessages;

	final AtomicInteger rounds = new AtomicInteger();

	/** 직전 라운드에 모델이 받은 메시지 수. 다음 라운드에서 늘어난 수를 계산하는 데 쓴다. */
	volatile int lastMessages;

	private final List<String> tools = new ArrayList<>();

	private TraceEvent.Tokens tokens = TraceEvent.Tokens.ZERO;

	private @Nullable String model;

	private @Nullable String reasoningEffort;

	private boolean answered;

	TurnRecord(int initialMessages) {
		this.initialMessages = initialMessages;
		this.lastMessages = initialMessages;
	}

	synchronized void toolExecuted(String name) {
		this.tools.add(name);
	}

	/** 실행됐지만 예외로 끝난 도구. 요약에 {@code read_file (오류)} 처럼 표시된다. */
	synchronized void toolFailed(String name, boolean cancelled) {
		this.tools.add(name + (cancelled ? " (취소)" : " (오류)"));
	}

	/** 라운드 하나가 끝났다: 토큰을 더하고, 마지막으로 응답한 모델 정보를 기억한다. */
	synchronized void roundFinished(TraceEvent.RoundFinished round) {
		this.tokens = this.tokens.plus(round.tokens());
		if (round.model() != null) {
			this.model = round.model();
		}
		if (round.reasoningEffort() != null) {
			this.reasoningEffort = round.reasoningEffort();
		}
		this.answered = round.toolCalls().isEmpty() && round.abnormalFinish() == null;
	}

	synchronized TraceEvent.TurnFinished finish() {
		return new TraceEvent.TurnFinished(this.rounds.get(), this.tools.size(), toolSummary(),
				System.nanoTime() - this.startNanos, this.tokens, this.model, this.reasoningEffort, this.answered);
	}

	/** {@code list_dir, read_file ×2} 처럼 이름별 횟수. */
	private String toolSummary() {
		Map<String, Long> counts = new LinkedHashMap<>();
		this.tools.forEach(name -> counts.merge(name, 1L, Long::sum));
		return counts.entrySet()
			.stream()
			.map(e -> (e.getValue() > 1) ? e.getKey() + " ×" + e.getValue() : e.getKey())
			.collect(Collectors.joining(", "));
	}

}
