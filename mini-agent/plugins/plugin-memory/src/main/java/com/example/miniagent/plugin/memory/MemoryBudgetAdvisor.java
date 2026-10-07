package com.example.miniagent.plugin.memory;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/** Final model boundary, including rounds where memory owns the tool history. */
final class MemoryBudgetAdvisor implements CallAdvisor {

	private static final String ACTIVE = MemoryBudgetAdvisor.class.getName() + ".active";

	private final int maxMessages;

	private final long maxChars;

	private record ActiveGroup(List<Message> system, List<Message> messages, List<Message> optional) {
	}

	MemoryBudgetAdvisor(int maxMessages, long maxChars) {
		MemoryBudget.positive("max-messages", maxMessages);
		MemoryBudget.positive("max-context-chars", maxChars);
		this.maxMessages = maxMessages;
		this.maxChars = maxChars;
	}

	/** Capture before memory/compaction so intrinsic mandatory overflow never calls a model. */
	CallAdvisor capture(int order) {
		return new CallAdvisor() {
			@Override
			public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
				List<Message> messages = request.prompt().getInstructions();
				List<Message> active = new ArrayList<>(messages.subList(
						JsonlChatMemory.groupStart(messages, messages.size()), messages.size()));
				List<Message> system = messages.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).toList();
				checkMandatory(system, active);
				return chain.nextCall(request.mutate()
					.context(ACTIVE, new ActiveGroup(system, active, new ArrayList<>())).build());
			}

			@Override
			public int getOrder() {
				return order;
			}

			@Override
			public String getName() {
				return "MemoryActiveGroupCapture";
			}
		};
	}

	/** Before in-loop memory: do not compact an intrinsically oversized live tool group. */
	CallAdvisor preflight(int order) {
		return new CallAdvisor() {
			@Override
			public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
				if (!(request.context().get(ACTIVE) instanceof ActiveGroup group)) {
					throw new IllegalStateException("Memory active USER group was not captured");
				}
				List<Message> messages = request.prompt().getInstructions();
				int start = 0;
				for (int i = messages.size() - 1; i >= 0; i--) {
					if (messages.get(i).getMessageType() == MessageType.USER && !isSummary(messages.get(i))
							&& java.util.Objects.equals(messages.get(i).getText(), group.messages().getFirst().getText())) {
						start = i;
						break;
					}
				}
				for (Message message : messages.subList(start, messages.size())) {
					if (message instanceof ToolResponseMessage tool) {
						boolean alreadyPresent = group.messages().stream().filter(ToolResponseMessage.class::isInstance)
							.map(ToolResponseMessage.class::cast)
							.anyMatch(previous -> previous.getResponses().equals(tool.getResponses()));
						if (!alreadyPresent) {
							group.messages().add(tool);
						}
					}
				}
				checkMandatory(group.system(), group.messages());
				return chain.nextCall(request);
			}

			@Override
			public int getOrder() {
				return order;
			}

			@Override
			public String getName() {
				return "MemoryActiveGroupPreflight";
			}
		};
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		if (!(request.context().get(ACTIVE) instanceof ActiveGroup group)) {
			throw new IllegalStateException("Memory active USER group was not captured");
		}
		List<Message> active = group.messages();
		List<Message> messages = request.prompt().getInstructions();
		List<Message> system = messages.stream().filter(m -> m.getMessageType() == MessageType.SYSTEM).toList();
		List<Message> history = new ArrayList<>();
		Message summary = null;
		int activeStart = messages.size();
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message message = messages.get(i);
			if (message.getMessageType() == MessageType.USER && !isSummary(message)
					&& java.util.Objects.equals(message.getText(), active.getFirst().getText())) {
				activeStart = i;
				break;
			}
		}
		// Memory inside the loop can omit the stored active group. The captured group and
		// model tool calls remain authoritative; only new live tool responses are appended.
		for (int i = 0; i < messages.size(); i++) {
			Message message = messages.get(i);
			if (i < activeStart && message.getMessageType() != MessageType.SYSTEM) {
				if (isSummary(message)) {
					summary = message;
				}
				else if (!(message instanceof ToolResponseMessage) || activeStart != messages.size()) {
					history.add(message);
				}
			}
		}
		if (activeStart == messages.size()) {
			// In-loop memory can return no raw history while the last stored call is
			// awaiting its result. Rebudget the last dispatched optional context,
			// rather than discarding fitting complete historical groups with it.
			history.clear();
			summary = null;
			for (Message message : group.optional()) {
				if (isSummary(message)) {
					summary = message;
				}
				else {
					history.add(message);
				}
			}
		}
		checkMandatory(system, active);
		int remainingCount = this.maxMessages - system.size() - active.size();
		long remainingChars = this.maxChars - MemoryBudget.length(system) - MemoryBudget.length(active);
		int start = 0;
		while (start < history.size()) {
			int end = start + 1;
			while (end < history.size() && history.get(end).getMessageType() != MessageType.USER) {
				end++;
			}
			if (!MemoryBudget.validTools(history.subList(start, end))) {
				history.subList(start, end).clear();
			}
			else {
				start = end;
			}
		}
		while (!history.isEmpty() && (history.size() + (summary == null ? 0 : 1) > remainingCount
				|| MemoryBudget.length(history) + (summary == null ? 0 : MemoryBudget.length(summary)) > remainingChars)) {
			int end = 1;
			while (end < history.size() && history.get(end).getMessageType() != MessageType.USER) {
				end++;
			}
			history.subList(0, end).clear();
		}
		List<Message> optional = new ArrayList<>();
		if (summary != null && remainingCount >= 1 && MemoryBudget.length(summary) <= remainingChars) {
			optional.add(summary);
		}
		optional.addAll(history);
		group.optional().clear();
		group.optional().addAll(optional);
		List<Message> bounded = new ArrayList<>(system);
		bounded.addAll(optional);
		bounded.addAll(active);
		ChatClientResponse response = chain.nextCall(request.mutate()
			.prompt(new Prompt(bounded, request.prompt().getOptions())).build());
		if (response.chatResponse() != null && response.chatResponse().getResult() != null) {
			AssistantMessage output = response.chatResponse().getResult().getOutput();
			if (output.hasToolCalls()) {
				active.add(output);
			}
		}
		return response;
	}

	private void checkMandatory(List<Message> system, List<Message> active) {
		if (active.isEmpty() || active.getFirst().getMessageType() != MessageType.USER || !MemoryBudget.validTools(active)) {
			throw new IllegalArgumentException("Invalid active USER/tool group: check tool call and response IDs");
		}
		int count = system.size() + active.size();
		long chars = MemoryBudget.length(system) + MemoryBudget.length(active);
		if (count > this.maxMessages || chars > this.maxChars) {
			throw new IllegalArgumentException("Mandatory system and active USER/tool group need " + count
					+ " messages and " + chars + " chars; increase agent.plugins.memory.max-messages ("
					+ this.maxMessages + ") / agent.plugins.memory.max-context-chars (" + this.maxChars
					+ ") or reduce active input/tool output");
		}
	}

	private static boolean isSummary(Message message) {
		return Boolean.TRUE.equals(message.getMetadata().get("memory.summary"));
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE - 1;
	}

	@Override
	public String getName() {
		return "MemoryBudgetAdvisor";
	}

}
