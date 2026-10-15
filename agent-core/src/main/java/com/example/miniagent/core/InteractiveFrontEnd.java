package com.example.miniagent.core;

/**
 * 대화형 화면을 통째로 바꾸는 플러그인 계약. (예: 전체 화면 TUI)
 *
 * <p>이 빈이 있으면 REPL 대신 {@link #run()} 이 대화형 세션을 맡는다. 한 번 실행 모드
 * ({@code ./agent.sh "질문"})는 항상 일반 텍스트 출력을 쓴다.
 */
public interface InteractiveFrontEnd {

	/** 지금 쓸 수 있는지. 예: TUI 는 입력이 파이프로 들어오면(터미널이 아니면) false. 그러면 REPL 을 쓴다. */
	default boolean isAvailable() {
		return true;
	}

	/** 사용자가 종료할 때까지 블록한다. */
	void run();

}
