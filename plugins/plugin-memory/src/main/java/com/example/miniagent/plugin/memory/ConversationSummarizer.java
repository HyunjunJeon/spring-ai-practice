package com.example.miniagent.plugin.memory;

import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.Message;

/** 이전 요약(없으면 null)과 새로 접을 메시지들을 요약 하나로 합친다. */
@FunctionalInterface
public interface ConversationSummarizer {

	String summarize(@Nullable String previousSummary, List<Message> messages);

}
