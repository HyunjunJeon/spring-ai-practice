package com.example.miniagent.core.model;

import com.example.miniagent.core.SlashCommand;

public final class ModelsCommand implements SlashCommand {

	@Override
	public String name() {
		return "models";
	}

	@Override
	public String description() {
		return "모델 목록과 인증 상태";
	}

	@Override
	public void execute(String args, CommandContext context) {
		if (!args.isBlank()) {
			throw new IllegalArgumentException("Usage: /models");
		}
		ModelSelection selected = context.agent().selectedModel();
		for (ModelEntry entry : context.agent().modelRegistry().models()) {
			context.console().println((entry.ref().equals(selected.ref()) ? "* " : "  ")
					+ ModelDisplay.ref(entry.ref()) + " ["
					+ (entry.unavailableCode() == null ? "available" : entry.unavailableCode().name()) + "]");
		}
		context.console().println(ModelDisplay.label(context.agent()));
	}

}
