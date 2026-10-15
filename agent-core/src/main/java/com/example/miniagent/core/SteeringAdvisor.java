package com.example.miniagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.example.miniagent.core.model.TurnContext;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/** Request-scoped injection inside the existing Spring AI tool loop. */
public final class SteeringAdvisor implements CallAdvisor, StreamAdvisor {
	public static final String CONTEXT_KEY = SteeringAdvisor.class.getName() + ".scope";
	public record Consumed(InputQueue.Target target, String turnId, long sequence, UserMessage message) {}
	private record Injection(String afterTool, Consumed consumed) {}

	/** Explicit capability, never resolved from thread locals or user metadata. */
	public static final class Scope {
		private final InputQueue queue;
		private final InputQueue.Target target;
		private final Supplier<String> session;
		private final String turnId;
		private final List<Injection> injections = new ArrayList<>();
		private TurnContext captured;
		private boolean boundaryCaptured;

		public Scope(InputQueue queue, InputQueue.Target target, Supplier<String> session, String turnId,
				TurnContext captured) {
			this.queue = queue;
			this.target = target;
			this.session = session;
			this.turnId = turnId;
			this.captured = captured;
		}

		public String sessionId() { return this.target.sessionId(); }
		public String turnId() { return this.turnId; }
		public TurnContext captured() { return this.captured; }
		void captured(TurnContext context) { this.captured = context; }

		/** Snapshot for an outer history advisor; preserve sequence and turn identity. */
		public synchronized List<Consumed> consumed() {
			return this.injections.stream().map(Injection::consumed).toList();
		}

		private synchronized void captureBoundary(ChatClientRequest request) {
			if (this.boundaryCaptured) return;
			List<Message> messages = request.prompt().getInstructions();
			String anchor = null;
			for (Message message : messages) {
				if (message instanceof ToolResponseMessage tool && !tool.getResponses().isEmpty())
					anchor = tool.getResponses().getLast().id();
			}
			for (var entry : this.queue.drainSteering(this.target, this.session.get())) {
				UserMessage message = UserMessage.builder().text(entry.text())
					.metadata(java.util.Map.of("agent.steering.sequence", entry.sequence(),
							"agent.steering.generation", this.target.generation().toString())).build();
				this.injections.add(new Injection(anchor,
						new Consumed(this.target, this.turnId, entry.sequence(), message)));
			}
			this.boundaryCaptured = true;
		}

		private synchronized ChatClientRequest prepare(ChatClientRequest request) {
			captureBoundary(request);
			this.boundaryCaptured = false;
			List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
			// ToolCallingAdvisor retains its own history, not downstream prompt mutations.
			// Reinsert by complete tool-result anchor, never between a call and its results.
			int position = 0;
			for (Injection injection : this.injections) {
				int present = messages.indexOf(injection.consumed().message());
				if (present >= 0) { position = Math.max(position, present + 1); continue; }
				if (injection.afterTool() == null) {
					position = Math.max(position, messages.size() > 0 ? initialUserEnd(messages) : 0);
				}
				else {
					for (int i = 0; i < messages.size(); i++) {
						if (messages.get(i) instanceof ToolResponseMessage tool
								&& tool.getResponses().stream().anyMatch(r -> injection.afterTool().equals(r.id()))) {
							position = Math.max(position, i + 1);
						}
					}
				}
				messages.add(position++, injection.consumed().message());
			}
			return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
		}

		private static int initialUserEnd(List<Message> messages) {
			for (int i = 0; i < messages.size(); i++)
				if (messages.get(i).getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
						&& i == messages.size() - 1) return i + 1;
			// With complete tool history, insert before the first tool call of this turn.
			for (int i = messages.size() - 1; i >= 0; i--)
				if (messages.get(i).getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) return i + 1;
			return messages.size();
		}
	}

	public static List<Consumed> consumed(ChatClientRequest request) {
		return request.context().get(CONTEXT_KEY) instanceof Scope scope ? scope.consumed() : List.of();
	}

	/** Pre-compaction budget gates capture the same boundary without persisting users. */
	public static void captureBoundary(ChatClientRequest request) {
		if (request.context().get(CONTEXT_KEY) instanceof Scope scope) scope.captureBoundary(request);
	}

	@Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(prepare(request));
	}
	@Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		return Flux.defer(() -> chain.nextStream(prepare(request)));
	}
	private ChatClientRequest prepare(ChatClientRequest request) {
		return request.context().get(CONTEXT_KEY) instanceof Scope scope ? scope.prepare(request) : request;
	}
	@Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 2; }
	@Override public String getName() { return "SteeringAdvisor"; }
}
