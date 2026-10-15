package com.example.miniagent.core.model.command;

import java.util.LinkedHashMap;
import java.util.Map;

import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;

public final class ModelCommand implements SlashCommand {

	@Override
	public String name() {
		return "model";
	}

	@Override
	public String description() {
		return "show | [select] provider/model [key=value ...] (다음 턴)";
	}

	@Override
	public void execute(String args, CommandContext context) {
		if (args.isBlank() || args.strip().equals("show")) {
			context.console().println(ModelDisplay.label(context.agent()));
			return;
		}
		String[] parts = args.strip().split("\\s+");
		int index = parts[0].equals("select") ? 1 : 0;
		if (index >= parts.length) {
			throw usage();
		}
		String[] ref = parts[index++].split("/", 2);
		if (ref.length != 2) {
			throw usage();
		}
		Map<String, String> options = new LinkedHashMap<>();
		for (; index < parts.length; index++) {
			String[] option = parts[index].split("=", 2);
			if (option.length != 2 || options.putIfAbsent(option[0], option[1]) != null) {
				throw usage();
			}
			validate(option[0], option[1]);
		}
		context.agent().selectModel(new ModelSelection(new ModelRef(ref[0], ref[1]), options));
		context.console().println(ModelDisplay.label(context.agent()));
	}

	private static IllegalArgumentException usage() {
		return new IllegalArgumentException("Usage: /model [show | [select] provider/model [key=value ...]]");
	}

	private static void validate(String key, String value) {
		switch (key) {
			case "temperature", "topP" -> {
				double number = Double.parseDouble(value);
				if (!Double.isFinite(number) || number < 0 || (key.equals("topP") && number > 1)) {
					throw new IllegalArgumentException("Invalid " + key);
				}
			}
			case "topK", "maxTokens" -> {
				if (Integer.parseInt(value) <= 0) {
					throw new IllegalArgumentException("Invalid " + key);
				}
			}
			case "seed" -> Integer.parseInt(value);
			default -> {
				// Remaining keys and provider-specific values are validated by the selection/provider.
			}
		}
	}

}
