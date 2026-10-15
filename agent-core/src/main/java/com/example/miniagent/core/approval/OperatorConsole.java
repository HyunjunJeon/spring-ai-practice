package com.example.miniagent.core.approval;

import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * 승인 대기열과 적용 엔진이 운영자 화면에서 쓰는 기능만 모은 것. 구현은 {@code core.AgentConsole} 이다.
 *
 * <p>approval 과 change 가 상위 패키지의 콘솔 클래스를 직접 참조하지 않게 하려고 둔다.
 */
public interface OperatorConsole {

	/** 출력 모드(한 번 실행하고 종료)에서는 false. 사용자에게 물어볼 수 없다는 뜻이다. */
	boolean isInteractive();

	@Nullable String readLine(String prompt);

	void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response);

	void dismissApproval(UUID requestId);

}
