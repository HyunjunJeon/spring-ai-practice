package com.example.miniagent.core.model.command;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;

/** Display reads selection and captured context, never a mutable global ChatModel. */
public final class ModelDisplay {

	private ModelDisplay() {
	}

	public static String ref(ModelRef ref) {
		return ref.provider() + "/" + ref.model();
	}

	public static String selection(ModelSelection selection) {
		String options = selection.options().entrySet().stream()
			.map(entry -> entry.getKey() + "=" + entry.getValue())
			.collect(java.util.stream.Collectors.joining(" "));
		return ref(selection.ref()) + (options.isEmpty() ? "" : " " + options);
	}

	public static String label(Agent agent) {
		ModelSelection selected = agent.selectedModel();
		String next = "next: " + selection(selected);
		var current = agent.currentTurn();
		if (current.isPresent()) {
			ModelBinding binding = current.get().binding();
			return "running: " + ref(binding.ref()) + " options=" + binding.optionsFingerprint()
					+ " | " + next;
		}
		ModelEntry entry = agent.modelRegistry().models().stream()
			.filter(model -> model.ref().equals(selected.ref())).findFirst().orElse(null);
		String availability = entry == null ? "UNKNOWN_MODEL"
				: entry.unavailableCode() == null ? "available" : entry.unavailableCode().name();
		return next + " [" + availability + "]";
	}

}
