package com.example.miniagent.plugin.trace;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * {@code /trace off|summary|detail} 로 바꾸는 런타임 스위치와, 진행 중인 턴의 기록.
 *
 * <p>어드바이저끼리는 Advisor 컨텍스트로 턴 기록을 넘기지만, {@code ToolInterceptor} 는 그 컨텍스트를 볼 수 없다
 * (도구는 ToolContext 만 받는다). 그래서 진행 중인 턴을 여기에도 걸어 둔다. REPL 은 한 번에 한 턴만 돌리므로
 * 필드 하나로 충분하다.
 */
public class TraceSettings {

	public enum Level {

		/** 아무것도 출력하지 않는다. */
		OFF,

		/** 턴이 끝날 때 한 줄: 걸린 시간, 모델 호출 횟수, 실행한 도구. */
		SUMMARY,

		/** 라운드마다 모델 호출/응답과 도구 실행을 트리로 보여준다. 에이전트 루프를 공부할 때. */
		DETAIL;

		/** 알 수 없는 값은 summary 로 바꿔 받지 않고 거부한다. 오타가 조용히 수준을 바꾸면 안 된다. */
		static Level parse(String value) {
			return switch (value.strip().toLowerCase(Locale.ROOT)) {
				case "off", "false" -> OFF;
				case "summary" -> SUMMARY;
				case "detail", "details", "verbose" -> DETAIL;
				default -> throw new IllegalArgumentException(
						"Unknown trace level '" + value.strip() + "': use off, summary or detail");
			};
		}

	}

	private volatile Level level;

	private volatile @Nullable TurnRecord currentTurn;

	public TraceSettings(String level) {
		this.level = Level.parse(level);
	}

	public Level level() {
		return this.level;
	}

	public void setLevel(String level) {
		this.level = Level.parse(level);
	}

	public boolean isOff() {
		return this.level == Level.OFF;
	}

	public boolean isDetail() {
		return this.level == Level.DETAIL;
	}

	@Nullable TurnRecord currentTurn() {
		return this.currentTurn;
	}

	void setCurrentTurn(@Nullable TurnRecord turn) {
		this.currentTurn = turn;
	}

}
