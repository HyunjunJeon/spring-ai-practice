package com.example.miniagent.plugin.tui;

import java.util.ArrayList;
import java.util.List;

/**
 * 대화 패널에 쌓이는 항목들. 에이전트 스레드가 쓰고 UI 스레드가 읽으므로 동기화한다.
 */
public class ChatLog {

	public enum Role {

		/** 사용자가 입력한 말. */
		USER,

		/** 에이전트의 최종 답변. */
		AGENT,

		/** 명령 출력 등 일반 텍스트. */
		TEXT,

		/** 흐린 부가 정보 (토큰 수 등). */
		INFO,

		ERROR,

		/** 플러그인이 사용자에게 묻는 질문 (권한 확인 등). */
		QUESTION

	}

	public record Entry(Role role, String text) {
	}

	private final List<Entry> entries = new ArrayList<>();

	public synchronized void add(Role role, String text) {
		this.entries.add(new Entry(role, text));
	}

	public synchronized List<Entry> snapshot() {
		return List.copyOf(this.entries);
	}

	public synchronized void clear() {
		this.entries.clear();
	}

}
