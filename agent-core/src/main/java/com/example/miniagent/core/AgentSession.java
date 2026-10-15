package com.example.miniagent.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 현재 세션 ID 를 들고 있는 가변 홀더.
 *
 * <p>세션 ID 는 {@code ChatMemory.CONVERSATION_ID} 로 Advisor 컨텍스트에 실려 간다.
 * 메모리 플러그인이 없으면 아무도 이 값을 쓰지 않으므로 매 턴이 독립적인 대화가 된다.
 */
public class AgentSession {

	private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private volatile String id = newId();

	public String id() {
		return this.id;
	}

	public String startNew() {
		this.id = newId();
		return this.id;
	}

	public void switchTo(String sessionId) {
		this.id = sessionId;
	}

	private static String newId() {
		return LocalDateTime.now().format(ID_FORMAT) + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
	}

}
