package com.example.miniagent.core;

import java.util.List;

/**
 * 실행 중인 턴을 기다리지 않고 바로 처리하는 운영자 제어 명령인지 판별한다.
 *
 * <p>{@link AgentInputHandler} 가 기능 패키지의 명령 클래스를 참조하지 않도록 문자열 규칙만 여기에 둔다.
 * 각 명령의 {@code isControlInput} 은 이 메서드에 위임한다.
 */
public final class ControlInput {
	private ControlInput() {}

	public static boolean isRunControl(String input) {
		String[] parts = input.strip().split("\\s+");
		return parts[0].equals("/runs") || parts.length > 1 && parts[0].equals("/run")
				&& List.of("show", "status", "cancel", "approve", "reject", "defer", "inspect-drift").contains(parts[1]);
	}

	public static boolean isGoalControl(String input) {
		String[] parts = input.strip().split("\\s+");
		return parts.length >= 2 && parts[0].equals("/goal")
				&& List.of("show", "list", "cancel").contains(parts[1]);
	}

	public static boolean isLessonControl(String input) {
		String[] parts = input.strip().split("\\s+", 3);
		if (parts.length < 2 || !parts[0].equals("/lesson")) return false;
		return List.of("list", "skills").contains(parts[1]) && parts.length == 2
				|| List.of("show", "inspect", "history", "lookup").contains(parts[1]) && parts.length == 3;
	}

	public static boolean isDelegateControl(String input) {
		var parts = input.strip().split("\\s+");
		return parts.length > 1 && parts[0].equals("/delegate")
				&& List.of("list", "show", "cancel", "approve", "reject", "defer").contains(parts[1]);
	}
}
