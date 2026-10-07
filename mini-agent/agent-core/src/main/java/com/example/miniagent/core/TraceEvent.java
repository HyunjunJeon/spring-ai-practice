package com.example.miniagent.core;

import java.util.List;
import java.util.UUID;

import com.example.miniagent.core.model.ModelRef;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.metadata.Usage;

/**
 * 에이전트 루프에서 일어난 일. trace 플러그인이 Spring 이벤트로 발행하고, 화면(콘솔/TUI)이 각자 그린다.
 *
 * <p>관찰(어드바이저)과 표시(렌더러)를 나눈 이유: 같은 데이터를 REPL 에서는 텍스트 한 줄로,
 * TUI 에서는 사이드바 카드로 보여주기 위해서다. Spring 의 {@code ApplicationEventPublisher} 는
 * 기본적으로 발행한 스레드(에이전트 작업 스레드)에서 리스너를 동기 호출한다.
 */
public sealed interface TraceEvent {

	/** Child execution and parent verification are distinct; pending is never success. */
	record DelegationStateChanged(String conversationId, String planId,
			@Nullable String childId, @Nullable String childRunId, @Nullable ModelRef childModel,
			com.example.miniagent.core.delegation.@Nullable ChildStatus childStatus,
			com.example.miniagent.core.run.@Nullable RunState runState,
			com.example.miniagent.core.delegation.DelegationResult.@Nullable Status resultStatus,
			@Nullable String failureCode) implements TraceEvent {
	}

	record ChangeStateChanged(UUID changeSetId, com.example.miniagent.core.change.ChangeOwner owner,
			String digest, com.example.miniagent.core.change.ChangeStatus status,
			com.example.miniagent.core.change.VerificationRunner.@Nullable Code verification,
			@Nullable UUID evidenceId) implements TraceEvent {
	}

	/** Persisted state observed at command boundaries or captured dispatch entry. */
	record RunStateChanged(String runId, String conversationId, @Nullable String attemptId,
			com.example.miniagent.core.run.RunState state, ModelRef ref) implements TraceEvent {
	}

	/**
	 * provider 가 {@code ChatResponseMetadata} 에 reasoning effort 를 넣을 때 쓰는 키.
	 * Spring AI 표준 키가 아니라 이 프로젝트의 약속이다(codex 플러그인이 넣는다).
	 */
	String REASONING_EFFORT_METADATA = "reasoningEffort";

	/** Captured at execution entry, before any advisor or model round runs. */
	record ModelBound(String sessionId, String turnId, ModelRef ref, UUID bindingId,
			String optionsFingerprint, @Nullable String runId, @Nullable String attemptId) implements TraceEvent {
	}

	/** 사용자 입력 한 건이 체인에 들어왔다. (루프 바깥 어드바이저) */
	record TurnStarted(int inputMessages, String userText) implements TraceEvent {
	}

	/** 모델 호출 직전. {@code addedMessages} 는 직전보다 늘어난 메시지 수(1라운드는 메모리, 이후는 도구 호출·결과). */
	record RoundStarted(int round, int messages, int addedMessages, int toolCount) implements TraceEvent {
	}

	/** 모델 응답 한 번. 도구를 요청했으면 {@code toolCalls} 에 "이름 인자" 가 담기고, 아니면 최종 답변 길이. */
	record RoundFinished(int round, List<String> toolCalls, int answerChars, @Nullable String abnormalFinish,
			long durationNanos, Tokens tokens, @Nullable String model, @Nullable String reasoningEffort)
			implements TraceEvent {
	}

	/** 도구가 실제로 실행되었다(권한 확인 통과 후). */
	record ToolExecuted(String name, int resultChars, long durationNanos) implements TraceEvent {
	}

	/**
	 * 도구가 실행됐지만 예외로 끝났다. {@code cancelled} 는 취소(인터럽트)로 끝났는지다.
	 * 권한 거부는 도구가 실행되지 않은 것이라 이 이벤트도, {@link ToolExecuted} 도 오지 않는다.
	 */
	record ToolFailed(String name, String error, boolean cancelled, long durationNanos) implements TraceEvent {
	}

	/** 턴 종료. 토큰은 라운드별 사용량을 합한 값이다. {@code answered} 는 최종 답변까지 갔는지(예외/한도로 끊기면 false). */
	record TurnFinished(int rounds, int toolCount, String toolSummary, long durationNanos, Tokens tokens,
			@Nullable String model, @Nullable String reasoningEffort, boolean answered) implements TraceEvent {
	}

	/**
	 * 토큰 수. OpenAI 계열은 {@code input} 에 캐시 적중분이 포함되어 있고 {@code cached} 는 그중 일부다.
	 * (더하면 안 된다)
	 */
	record Tokens(long input, long output, long cached) {

		public static final Tokens ZERO = new Tokens(0, 0, 0);

		public static Tokens from(@Nullable Usage usage) {
			if (usage == null) {
				return ZERO;
			}
			return new Tokens(orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()),
					orZero(usage.getCacheReadInputTokens()));
		}

		public Tokens plus(Tokens other) {
			return new Tokens(this.input + other.input, this.output + other.output, this.cached + other.cached);
		}

		public boolean isEmpty() {
			return this.input == 0 && this.output == 0;
		}

		/** {@code in 1,119 · cache 512 · out 24}. 캐시는 0 이어도 보여준다(캐시가 안 걸렸다는 정보). */
		public String format() {
			return "in %,d · cache %,d · out %,d".formatted(this.input, this.cached, this.output);
		}

		private static long orZero(@Nullable Number value) {
			return (value != null) ? value.longValue() : 0;
		}

	}

	/** 1초 미만은 ms, 그 이상은 소수 첫째 자리 초. */
	static String duration(long nanos) {
		long millis = nanos / 1_000_000;
		return (millis < 1000) ? millis + "ms" : "%.1f초".formatted(millis / 1000.0);
	}

}
