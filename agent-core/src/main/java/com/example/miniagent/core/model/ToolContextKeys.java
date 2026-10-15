package com.example.miniagent.core.model;

/**
 * 코어가 매 요청 {@code .toolContext(...)} 로 넣어 주는 값의 키.
 *
 * <p>ToolContext 는 모델에게는 보이지 않고 도구 구현에만 전달되는 값이다.
 * (세션 ID, 사용자 ID, 작업 디렉터리처럼 모델이 바꾸면 안 되는 정보)
 */
public final class ToolContextKeys {

	public static final String SESSION_ID = "agent.sessionId";

	public static final String CWD = "agent.cwd";

	/** 사용자 입력 한 건(턴)마다 새로 만드는 ID. 파일 체크포인트가 변경을 턴 단위로 묶는 데 쓴다. */
	public static final String TURN_ID = "agent.turnId";

	public static final String MODEL_PROVIDER = "agent.provider";

	public static final String MODEL_NAME = "agent.model";

	public static final String MODEL_BINDING_ID = "agent.bindingId";

	public static final String TURN_CONTEXT = "agent.turnContext";

	public static final String RUN_ID = "agent.runId";

	public static final String ATTEMPT_ID = "agent.attemptId";

	public static final String STAGED_WORKSPACE = "agent.stagedWorkspace";

	public static final String CHILD_SCOPE = "agent.childScope";

	private ToolContextKeys() {
	}

}
