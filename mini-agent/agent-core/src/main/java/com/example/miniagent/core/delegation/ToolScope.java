package com.example.miniagent.core.delegation;

import java.nio.file.Path;
import java.util.Set;

/** Immutable authority ceiling. Generated/cache roots are explicit, not inferred from names. */
public record ToolScope(Set<String> toolNames, Set<Path> readRoots, Set<Path> writeRoots,
		Set<Path> generatedRoots, boolean mutation, boolean network, boolean delegation, boolean apply) {
	public ToolScope {
		toolNames = Set.copyOf(toolNames);
		if (toolNames.stream().anyMatch(n -> n.isBlank() || !n.equals(n.strip())
				|| n.chars().anyMatch(Character::isISOControl))) {
			throw new IllegalArgumentException("Invalid tool name");
		}
		readRoots = roots(readRoots);
		writeRoots = roots(writeRoots);
		generatedRoots = roots(generatedRoots);
	}

	private static Set<Path> roots(Set<Path> roots) {
		Set<Path> copy = Set.copyOf(roots);
		if (copy.stream().anyMatch(p -> !p.isAbsolute() || !p.equals(p.normalize()))) {
			throw new IllegalArgumentException("Roots must be absolute and normalized");
		}
		return copy;
	}
}
