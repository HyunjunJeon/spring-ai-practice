package com.example.miniagent.core.model;

import java.util.Objects;

/** Provider identity is part of model identity, even for compatible wire protocols. */
public record ModelRef(String provider, String model) {

	public ModelRef {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(model, "model");
		if (!provider.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
			throw new IllegalArgumentException("Invalid provider ID");
		}
		if (model.isBlank() || !model.equals(model.strip()) || model.length() > 256
				|| model.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Invalid model ID");
		}
	}

}
