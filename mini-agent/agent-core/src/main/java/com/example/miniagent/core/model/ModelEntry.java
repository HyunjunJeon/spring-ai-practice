package com.example.miniagent.core.model;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import com.example.miniagent.core.model.ModelUnavailableException.Code;

/** A listing descriptor, not a live model or an authentication payload. */
public record ModelEntry(ModelRef ref, String wireFamily,
		@Nullable Code unavailableCode) {

	public ModelEntry {
		Objects.requireNonNull(ref, "ref");
		if (wireFamily == null || wireFamily.isBlank()) {
			throw new IllegalArgumentException("wireFamily is required");
		}
	}

}
