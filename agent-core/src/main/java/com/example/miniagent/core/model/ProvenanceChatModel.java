package com.example.miniagent.core.model;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.run.RunContext;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/** Decorates the model boundary, including tool rounds and direct synchronous compaction. */
public final class ProvenanceChatModel implements ChatModel {

	private final ChatModel delegate;

	private final ModelRef ref;

	private final Set<String> privateKeys;

	ProvenanceChatModel(ChatModel delegate, ModelRef ref, Set<String> privateKeys) {
		this.delegate = delegate;
		this.ref = ref;
		this.privateKeys = Set.copyOf(privateKeys);
	}

	List<Message> filter(List<Message> messages) {
		return ProviderPrivateFieldFilter.filter(messages, this.ref, this.privateKeys);
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		var run = RunContext.current();
		run.ifPresent(RunContext::beforeModel);
		ChatResponse response;
		try {
			response = this.delegate.call(new Prompt(filter(prompt.getInstructions()), prompt.getOptions()));
		}
		catch (RuntimeException ex) {
			run.ifPresent(context -> context.recordModel(null));
			throw ex;
		}
		run.ifPresent(context -> context.recordModel(response.getMetadata().getUsage()));
		return stamp(response);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return this.delegate.stream(new Prompt(filter(prompt.getInstructions()), prompt.getOptions())).map(this::stamp);
	}

	@Override
	public ChatOptions getOptions() {
		return this.delegate.getOptions();
	}

	private ChatResponse stamp(ChatResponse response) {
		List<Generation> generations = response.getResults().stream().map(generation -> {
			var metadata = new HashMap<String, Object>(generation.getOutput().getMetadata());
			metadata.put(ToolContextKeys.MODEL_PROVIDER, this.ref.provider());
			metadata.put(ToolContextKeys.MODEL_NAME, this.ref.model());
			return new Generation(generation.getOutput().mutate().properties(metadata).build(),
					generation.getMetadata());
		}).toList();
		return new ChatResponse(generations, response.getMetadata());
	}
}
