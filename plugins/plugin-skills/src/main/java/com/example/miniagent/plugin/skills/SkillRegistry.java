package com.example.miniagent.plugin.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code <dir>/<skill>/SKILL.md} 를 찾아 frontmatter 의 name/description 을 읽는다.
 * (Agent Skills 규격의 최소 부분만 구현)
 */
public class SkillRegistry {

	private final Map<String, Skill> skills = new LinkedHashMap<>();

	public SkillRegistry(List<Path> roots) {
		for (Path root : roots) {
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> files = Files.walk(root, 3)) {
				files.filter(f -> f.getFileName().toString().equals("SKILL.md"))
					.sorted()
					.map(SkillRegistry::parse)
					.flatMap(Optional::stream)
					.forEach(skill -> this.skills.putIfAbsent(skill.name(), skill));
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
	}

	public List<Skill> all() {
		return new ArrayList<>(this.skills.values());
	}

	public Optional<Skill> find(String name) {
		return Optional.ofNullable(this.skills.get(name));
	}

	private static Optional<Skill> parse(Path file) {
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			if (!content.startsWith("---")) {
				return Optional.empty();
			}
			int end = content.indexOf("\n---", 3);
			if (end < 0) {
				return Optional.empty();
			}
			Map<String, String> frontmatter = new LinkedHashMap<>();
			content.substring(3, end).lines().forEach(line -> {
				int colon = line.indexOf(':');
				if (colon > 0) {
					frontmatter.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
				}
			});
			String name = frontmatter.get("name");
			String description = frontmatter.getOrDefault("description", "");
			String body = content.substring(end + 4).strip();
			return (name == null || name.isBlank()) ? Optional.empty()
					: Optional.of(new Skill(name, description, file.getParent(), body));
		}
		catch (IOException ex) {
			// 읽을 수 없거나 UTF-8 이 아닌 SKILL.md 하나가 다른 스킬과 에이전트 시작을 막지 않도록 건너뛴다.
			return Optional.empty();
		}
	}

	public record Skill(String name, String description, Path directory, String instructions) {
	}

}
