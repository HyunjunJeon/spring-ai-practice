package com.example.miniagent.plugin.fakemodel;

import java.util.List;
import java.util.Map;

import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import org.springframework.ai.chat.model.ChatModel;

public final class FakeModelProvider implements ModelProvider {

	@Override
	public String providerId() {
		return "fake";
	}

	@Override
	public List<ModelEntry> models() {
		return List.of(new ModelEntry(new ModelRef("fake", "fake-scripted"), "fake", null));
	}

	@Override
	public ProviderAuth auth() {
		return () -> true;
	}

	@Override
	public ChatModel create(ModelRef ref, Map<String, String> options) {
		return new ScriptedChatModel();
	}
}
