package com.example.miniagent.plugin.memory;

import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.TurnContext;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;

/**
 * 메모리 = 저장소({@link ChatMemoryRepository}) + 정책({@link ChatMemory}) + 어드바이저(요청에 끼워 넣기).
 *
 * <p>Spring AI 의 ChatMemoryAutoConfiguration 도 ChatMemory/ChatMemoryRepository 빈을 만들지만
 * {@code @ConditionalOnMissingBean} 이다. 이 자동 구성이 먼저 돌도록 {@code beforeName} 을 걸어서
 * 우리 빈이 있으면 Spring AI 쪽 기본 빈(InMemory)은 만들어지지 않게 한다.
 */
@AutoConfiguration(beforeName = "org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration")
@ConditionalOnBooleanProperty(name = "agent.plugins.memory.enabled", matchIfMissing = true)
public class MemoryPluginAutoConfiguration {

	/** Fresh child policies and captured provenance model, never the mutable default model. */
	@Bean
	com.example.miniagent.core.delegation.ChildScopeFactory.AdvisorContribution childMemoryContribution(
			JsonlChatMemoryRepository repository, AgentConsole console,
			@Value("${agent.plugins.memory.max-messages:100}") int maxMessages,
			@Value("${agent.plugins.memory.max-context-chars:200000}") long maxChars,
			@Value("${agent.plugins.memory.max-summary-request-chars:32000}") int maxRequest,
			@Value("${agent.plugins.memory.max-summary-chars:8000}") int maxSummary,
			@Value("${agent.plugins.memory.compaction:true}") boolean compaction,
			@Value("${agent.plugins.memory.inside-loop:false}") boolean insideLoop,
			@Value("${spring.ai.chat.client.tool-calling.advisor-order:#{T(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor).DEFAULT_ORDER}}")
			int toolOrder) {
		return (model, authority) -> {
			var memory = new ScopeMemory(new JsonlChatMemory(repository, maxMessages, maxChars,
				compaction ? new ChatModelSummarizer(() -> model, maxRequest, maxSummary) : null,
				console::info, maxSummary), authority);
			var budget = new MemoryBudgetAdvisor(maxMessages, maxChars);
			return List.of(budget.capture(Math.min(toolOrder, Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER) - 1),
				budget.preflight(toolOrder + 1), budget,
				MessageChatMemoryAdvisor.builder(memory).order(insideLoop ? toolOrder + 50
					: Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER).build());
		};
	}

	@Bean
	JsonlChatMemoryRepository jsonlChatMemoryRepository(AgentProperties properties) {
		return new JsonlChatMemoryRepository(properties.getHome().resolve("sessions"));
	}

	/**
	 * 파일에는 전체 기록을 남기고, 모델에게는 최근 부분만 보낸다. 최근 부분은 메시지 수({@code max-messages})와
	 * UTF-16 내용 길이({@code max-context-chars}) 중 먼저 닿는 한도까지이며, 항상 사용자 그룹 경계에서 자른다.
	 * {@code compaction} 이 켜져 있으면 그 밖으로 밀려난 앞부분을 지금 쓰는 모델로 요약해 문맥 앞에 붙인다.
	 */
	@Bean
	ChatMemory chatMemory(JsonlChatMemoryRepository repository, ObjectProvider<ChatModel> chatModel,
			AgentConsole console, @Value("${agent.plugins.memory.max-messages:100}") int maxMessages,
			@Value("${agent.plugins.memory.max-context-chars:200000}") long maxContextChars,
			@Value("${agent.plugins.memory.max-summary-request-chars:32000}") int maxSummaryRequestChars,
			@Value("${agent.plugins.memory.max-summary-chars:8000}") int maxSummaryChars,
			@Value("${agent.plugins.memory.compaction:true}") boolean compaction) {
		ChatModelSummarizer.validate(maxSummaryRequestChars, maxSummaryChars);
		ConversationSummarizer summarizer = compaction
				? new ChatModelSummarizer(() -> TurnContext.current()
						.map(context -> context.binding().chatModel()).orElseGet(chatModel::getObject),
						maxSummaryRequestChars, maxSummaryChars) : null;
		return new JsonlChatMemory(repository, maxMessages, maxContextChars, summarizer, console::info, maxSummaryChars);
	}

	@Bean
	MemoryBudgetAdvisor memoryBudgetAdvisor(@Value("${agent.plugins.memory.max-messages:100}") int maxMessages,
			@Value("${agent.plugins.memory.max-context-chars:200000}") long maxContextChars) {
		return new MemoryBudgetAdvisor(maxMessages, maxContextChars);
	}

	@Bean
	Advisor memoryActiveGroupCapture(MemoryBudgetAdvisor budget,
			@Value("${spring.ai.chat.client.tool-calling.advisor-order:#{T(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor).DEFAULT_ORDER}}")
			int toolOrder) {
		return budget.capture(Math.min(toolOrder, Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER) - 1);
	}

	@Bean
	Advisor memoryActiveGroupPreflight(MemoryBudgetAdvisor budget,
			@Value("${spring.ai.chat.client.tool-calling.advisor-order:#{T(org.springframework.ai.chat.client.advisor.ToolCallingAdvisor).DEFAULT_ORDER}}")
			int toolOrder) {
		return budget.preflight(toolOrder + 1);
	}

	/**
	 * 실험 스위치 {@code agent.plugins.memory.inside-loop}.
	 * <ul>
	 *   <li>false(기본, HIGHEST+200): 루프 바깥. 사용자 입력과 최종 답변만 기억한다.</li>
	 *   <li>true(HIGHEST+350): 루프 안쪽. 도구 호출/결과 메시지까지 기억한다. 이때 ChatClient 는
	 *       ToolCallingAdvisor 의 내부 히스토리를 끄고 메모리에 맡긴다(DefaultChatClient 가 자동 판단).</li>
	 * </ul>
	 */
	@Bean
	Advisor chatMemoryAdvisor(ChatMemory chatMemory,
			@Value("${agent.plugins.memory.inside-loop:false}") boolean insideLoop) {
		int order = insideLoop ? ToolCallingAdvisor.DEFAULT_ORDER + 50 : Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
		return MessageChatMemoryAdvisor.builder(chatMemory).order(order).build();
	}

	@Bean
	SlashCommand sessionsCommand(ChatMemoryRepository repository) {
		return SlashCommand.of("sessions", "저장된 세션 목록", (args, ctx) -> {
			List<String> ids = repository.findConversationIds();
			if (ids.isEmpty()) {
				ctx.console().println("저장된 세션이 없습니다.");
			}
			ids.forEach(id -> ctx.console().println((id.equals(ctx.session().id()) ? "* " : "  ") + id));
		});
	}

	@Bean
	SlashCommand newSessionCommand() {
		return SlashCommand.of("new", "새 세션 시작", (args, ctx) -> ctx.console().println("새 세션: " + ctx.session().startNew()));
	}

	@Bean
	SlashCommand resumeCommand(ChatMemoryRepository repository) {
		return SlashCommand.of("resume", "세션 이어가기 (/resume <id>, id 생략 시 가장 최근)", (args, ctx) -> {
			List<String> ids = repository.findConversationIds();
			String id = args.isBlank() ? (ids.isEmpty() ? null : ids.get(ids.size() - 1)) : args.strip();
			if (id == null || !ids.contains(id)) {
				ctx.console().error("세션을 찾을 수 없습니다: " + args);
				return;
			}
			ctx.session().switchTo(id);
			ctx.console().println("세션 전환: " + id + " (전체 기록 " + repository.findByConversationId(id).size() + "개 메시지)");
		});
	}

	@Bean
	SlashCommand historyCommand(ChatMemory chatMemory) {
		return SlashCommand.of("history", "모델에게 보내는 현재 세션의 기억 (최근 부분)", (args, ctx) -> {
			List<Message> messages = chatMemory.get(ctx.session().id());
			if (messages.isEmpty()) {
				ctx.console().println("(비어 있음)");
			}
			messages.forEach(m -> ctx.console().println("%-10s %s".formatted(m.getMessageType(), abbreviate(String.valueOf(m.getText())))));
		});
	}

	@Bean
	SlashCommand forgetCommand(ChatMemory chatMemory) {
		return SlashCommand.of("forget", "현재 세션의 기억 지우기", (args, ctx) -> {
			chatMemory.clear(ctx.session().id());
			ctx.console().println("지웠습니다: " + ctx.session().id());
		});
	}

	@Bean
	AgentPlugin memoryPlugin(@Value("${agent.plugins.memory.inside-loop:false}") boolean insideLoop,
			@Value("${agent.plugins.memory.compaction:true}") boolean compaction) {
		return AgentPlugin.of("memory",
				"JSONL 세션 메모리 (" + (insideLoop ? "루프 안쪽" : "루프 바깥") + ", 요약 " + (compaction ? "켜짐" : "꺼짐") + ")");
	}

	private static String abbreviate(String text) {
		String oneLine = text.replaceAll("\\s+", " ");
		return (oneLine.length() <= 100) ? oneLine : oneLine.substring(0, 100) + "…";
	}

}
