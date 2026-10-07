package com.example.miniagent.plugin.fakemodel;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * 네트워크를 쓰지 않는 가짜 {@link ChatModel}.
 *
 * <p>규칙은 세 가지뿐이다.
 * <ol>
 *   <li>마지막 메시지가 {@code !tool 이름 {json}} 이면 그 도구를 호출하라는 응답(tool call)을 만든다.</li>
 *   <li>마지막 메시지가 도구 결과({@link ToolResponseMessage})면 결과를 요약해 최종 답변을 만든다.</li>
 *   <li>그 밖에는 "모델이 실제로 받은 요청"을 그대로 설명한다. 메모리, 시스템 프롬프트, 도구 정의가
 *       어떻게 붙어서 들어오는지 눈으로 확인하는 용도다.</li>
 * </ol>
 *
 * <p>진짜 provider(Anthropic, OpenAI …)도 결국 이 인터페이스 하나를 구현한 것이다.
 */
public class ScriptedChatModel implements ChatModel {

	private static final Pattern TOOL_DIRECTIVE = Pattern.compile("^!tool\\s+(\\S+)\\s*(\\{.*})?\\s*$", Pattern.DOTALL);

	private final AtomicInteger callIds = new AtomicInteger();

	@Override
	public ChatResponse call(Prompt prompt) {
		List<Message> messages = prompt.getInstructions();
		Message last = messages.get(messages.size() - 1);

		AssistantMessage output;
		if (last instanceof ToolResponseMessage toolResponse) {
			output = new AssistantMessage(summarize(toolResponse));
		}
		else if (last instanceof UserMessage user && TOOL_DIRECTIVE.matcher(user.getText().strip()).matches()) {
			output = toolCall(user.getText().strip());
		}
		else {
			output = new AssistantMessage(describe(prompt));
		}

		// 토큰 수는 "글자 수 / 4" 로 흉내만 낸다. 트레이스 플러그인이 사용량을 보여줄 수 있게.
		int promptTokens = messages.stream().mapToInt(m -> length(m) / 4).sum();
		int completionTokens = (output.getText() != null) ? output.getText().length() / 4 : 0;
		var metadata = ChatResponseMetadata.builder()
			.model("fake-scripted")
			.usage(new DefaultUsage(promptTokens, completionTokens))
			.build();
		// finishReason 도 실제 provider 처럼 채운다. (Anthropic: tool_use/end_turn, OpenAI: tool_calls/stop)
		var generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(output.hasToolCalls() ? "TOOL_CALLS" : "STOP")
			.build();
		return new ChatResponse(List.of(new Generation(output, generationMetadata)), metadata);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.just(call(prompt));
	}

	/**
	 * 중요: 기본 옵션이 {@link ToolCallingChatOptions} 여야 한다.
	 *
	 * <p>ChatClient 는 {@code chatModel.getOptions().mutate()} 로 요청 옵션을 만들고, 그 빌더가
	 * ToolCallingChatOptions.Builder 일 때만 도구를 붙인다. 그리고 ToolCallingAdvisor 는 옵션이
	 * ToolCallingChatOptions 가 아니면 루프를 돌지 않고 그냥 통과시킨다.
	 * ({@code ChatModel.getOptions()} 의 기본 구현은 평범한 ChatOptions 를 돌려준다.)
	 */
	@Override
	public ChatOptions getOptions() {
		return ToolCallingChatOptions.builder().model("fake-scripted").build();
	}

	private AssistantMessage toolCall(String directive) {
		Matcher matcher = TOOL_DIRECTIVE.matcher(directive);
		matcher.matches();
		String name = matcher.group(1);
		String arguments = (matcher.group(2) != null) ? matcher.group(2) : "{}";
		var toolCall = new AssistantMessage.ToolCall("call_" + this.callIds.incrementAndGet(), "function", name,
				arguments);
		return AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
	}

	private static String summarize(ToolResponseMessage toolResponse) {
		return toolResponse.getResponses()
			.stream()
			.map(r -> "[fake] 도구 '%s' 결과를 받았습니다:%n%s".formatted(r.name(), abbreviate(r.responseData(), 800)))
			.collect(Collectors.joining("\n\n"));
	}

	private static String describe(Prompt prompt) {
		StringBuilder sb = new StringBuilder("[fake] 모델이 받은 요청\n");
		sb.append("- 메시지 ").append(prompt.getInstructions().size()).append("개\n");
		for (Message message : prompt.getInstructions()) {
			sb.append("    ")
				.append("%-10s".formatted(message.getMessageType()))
				.append(abbreviate(oneLine(text(message)), 70))
				.append('\n');
		}
		List<String> tools = (prompt.getOptions() instanceof ToolCallingChatOptions options
				&& options.getToolCallbacks() != null)
						? options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition).map(d -> d.name()).toList()
						: List.of();
		sb.append("- 도구 ").append(tools.size()).append("개: ").append(String.join(", ", tools)).append('\n');
		sb.append("도구를 호출시켜 보려면:  !tool list_dir {\"path\":\".\"}");
		return sb.toString();
	}

	private static String text(Message message) {
		if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
			return "(tool calls) " + assistant.getToolCalls().stream().map(AssistantMessage.ToolCall::name).toList();
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			return "(tool results) " + toolResponse.getResponses().stream().map(ToolResponseMessage.ToolResponse::name).toList();
		}
		return (message.getText() != null) ? message.getText() : "";
	}

	private static int length(Message message) {
		return text(message).length();
	}

	private static String oneLine(String text) {
		return text.replaceAll("\\s+", " ").strip();
	}

	private static String abbreviate(String text, int max) {
		return (text.length() <= max) ? text : text.substring(0, max) + "…";
	}

}
