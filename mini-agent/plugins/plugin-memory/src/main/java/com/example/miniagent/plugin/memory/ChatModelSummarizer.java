package com.example.miniagent.plugin.memory;

import java.util.List;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 지금 쓰는 채팅 모델로 대화를 요약한다.
 *
 * <p>{@code ChatClient} 가 아니라 {@code ChatModel} 을 직접 부른다. 그래서 어드바이저 체인(메모리, 트레이스)과
 * 도구 루프를 거치지 않는 한 번의 호출이다. 메모리 어드바이저 안에서 다시 메모리를 부르는 순환도 생기지 않는다.
 */
public class ChatModelSummarizer implements ConversationSummarizer {

	static final String INSTRUCTIONS = """
			You compress the earlier part of a conversation between a user and a coding agent, so the agent can \
			continue the work without the original messages.
			Keep: the user's goals and constraints, decisions and their reasons, files and paths that were read or \
			changed, commands that were run and what they showed, open questions and unfinished tasks.
			Drop: greetings, repetition, and raw tool output beyond the facts it established.
			If a previous summary is given, merge it with the new messages into one summary.
			Write in the language the user writes in. Use short bullet points, at most 20.""";

	/** 요약 요청이 너무 커지지 않도록 메시지 하나당 이 글자 수까지만 보낸다. */
	static final int MAX_MESSAGE_CHARS = 2000;

	static final String PREVIOUS = "Previous summary:\n";

	static final String HEADING = "Messages to fold into the summary:\n";

	static final String OMITTED = "… [omitted]\n";

	static final int MIN_REQUEST_CHARS = INSTRUCTIONS.length() + PREVIOUS.length() + HEADING.length()
			+ OMITTED.length() * 2 + 32;

	private final Supplier<ChatModel> chatModel;

	private final int maxRequestChars;

	private final int maxSummaryChars;

	public ChatModelSummarizer(Supplier<ChatModel> chatModel) {
		this(chatModel, 32000, 8000);
	}

	public ChatModelSummarizer(Supplier<ChatModel> chatModel, int maxRequestChars, int maxSummaryChars) {
		validate(maxRequestChars, maxSummaryChars);
		this.chatModel = chatModel;
		this.maxRequestChars = maxRequestChars;
		this.maxSummaryChars = maxSummaryChars;
	}

	static void validate(int maxRequestChars, int maxSummaryChars) {
		MemoryBudget.positive("max-summary-chars", maxSummaryChars);
		if (maxRequestChars < MIN_REQUEST_CHARS) {
			throw new IllegalArgumentException("agent.plugins.memory.max-summary-request-chars must be at least "
					+ MIN_REQUEST_CHARS);
		}
	}

	@Override
	public String summarize(@Nullable String previousSummary, List<Message> messages) {
		StringBuilder request = new StringBuilder();
		int limit = this.maxRequestChars - INSTRUCTIONS.length() - OMITTED.length();
		if (previousSummary != null) {
			request.append(PREVIOUS);
			int available = Math.min(this.maxSummaryChars,
					(limit - PREVIOUS.length() - HEADING.length() - OMITTED.length() - 2) / 2);
			String previous = MemoryBudget.prefix(previousSummary, available);
			request.append(previous);
			if (previous.length() < previousSummary.length()) {
				request.append(OMITTED);
			}
			request.append("\n\n");
		}
		request.append(HEADING);
		boolean omitted = false;
		outer: for (Message message : messages) {
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				omitted |= append(request, "ASSISTANT called ", limit);
				for (var call : assistant.getToolCalls()) {
					omitted |= append(request, call.name(), limit);
					omitted |= append(request, " " + abbreviate(call.arguments()) + ", ", limit);
					if (request.length() >= limit) {
						omitted = true;
						break outer;
					}
				}
				if (!isBlank(assistant.getText())) {
					omitted |= append(request, " after saying: " + abbreviate(assistant.getText()), limit);
				}
			}
			else if (message instanceof ToolResponseMessage tool) {
				for (var response : tool.getResponses()) {
					omitted |= append(request, "TOOL RESULT ", limit);
					omitted |= append(request, response.name(), limit);
					omitted |= append(request, ": " + abbreviate(response.responseData()) + "\n", limit);
					if (request.length() >= limit) {
						omitted = true;
						break outer;
					}
				}
			}
			else {
				omitted |= append(request, (message instanceof AssistantMessage ? "ASSISTANT: " : "USER: ")
						+ abbreviate(message.getText()), limit);
			}
			omitted |= append(request, "\n", limit);
			if (request.length() >= limit) {
				omitted = true;
				break;
			}
		}
		if (omitted) {
			request.append(OMITTED);
		}
		ChatResponse response = this.chatModel.get()
			.call(new Prompt(List.of(new SystemMessage(INSTRUCTIONS), new UserMessage(request.toString()))));
		Generation result = response.getResult();
		String text = (result != null) ? result.getOutput().getText() : null;
		if (text == null || text.isBlank()) {
			throw new IllegalStateException("요약 결과가 비어 있습니다");
		}
		String accepted = MemoryBudget.prefix(text.strip(), this.maxSummaryChars);
		if (accepted.isBlank()) {
			throw new IllegalStateException("요약 결과가 비어 있습니다");
		}
		return accepted;
	}

	private static boolean append(StringBuilder request, @Nullable String value, int limit) {
		String text = value == null ? "" : value;
		String part = MemoryBudget.prefix(text, limit - request.length());
		request.append(part);
		return part.length() < text.length();
	}

	private static boolean isBlank(@Nullable String text) {
		return text == null || text.isBlank();
	}

	private static String abbreviate(@Nullable String text) {
		if (text == null) {
			return "";
		}
		int start = 0;
		int end = text.length();
		while (start < end && Character.isWhitespace(text.charAt(start))) {
			start++;
		}
		while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
			end--;
		}
		int cut = Math.min(end, start + MAX_MESSAGE_CHARS);
		if (cut > start && cut < end && Character.isHighSurrogate(text.charAt(cut - 1))
				&& Character.isLowSurrogate(text.charAt(cut))) {
			cut--;
		}
		return text.substring(start, cut) + (cut < end ? "…" : "");
	}

}
