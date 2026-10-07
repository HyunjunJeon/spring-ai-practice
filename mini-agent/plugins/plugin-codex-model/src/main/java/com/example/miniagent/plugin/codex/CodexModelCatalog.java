package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이 계정이 쓸 수 있는 모델 목록. Codex CLI 가 받아 둔 {@code ~/.codex/models_cache.json} 을 읽는다.
 *
 * <p>목록 API 를 직접 부르지 않는 이유: 그 엔드포인트도 비공개다. Codex CLI 가 실행될 때마다 캐시를
 * 갱신하므로 그것을 재사용한다. 파일이 없으면 목록 없이 동작한다(검증만 생략).
 */
public class CodexModelCatalog {

	private final Path cacheFile;

	private final JsonMapper json;

	public CodexModelCatalog(Path cacheFile, JsonMapper json) {
		this.cacheFile = cacheFile;
		this.json = json;
	}

	public record ModelInfo(String slug, String defaultEffort, List<String> efforts) {

		public boolean supports(String effort) {
			return this.efforts.isEmpty() || this.efforts.contains(effort);
		}

	}

	/** 화면에 보이는(visibility=list) 모델만. 매번 파일을 다시 읽어 Codex CLI 의 갱신을 반영한다. */
	public List<ModelInfo> models() {
		if (!Files.isRegularFile(this.cacheFile)) {
			return List.of();
		}
		try {
			JsonNode root = this.json.readTree(Files.readString(this.cacheFile, StandardCharsets.UTF_8));
			List<ModelInfo> models = new ArrayList<>();
			for (JsonNode m : root.path("models")) {
				if (!"list".equals(m.path("visibility").asString(""))) {
					continue;
				}
				List<String> efforts = new ArrayList<>();
				m.path("supported_reasoning_levels").forEach(level -> efforts.add(level.path("effort").asString()));
				models.add(new ModelInfo(m.path("slug").asString(), m.path("default_reasoning_level").asString(""),
						efforts));
			}
			return models;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public Optional<ModelInfo> find(String slug) {
		return models().stream().filter(m -> m.slug().equals(slug)).findFirst();
	}

	public Path cacheFile() {
		return this.cacheFile;
	}

}
