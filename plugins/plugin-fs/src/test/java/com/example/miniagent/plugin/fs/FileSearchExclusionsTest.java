package com.example.miniagent.plugin.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 비밀 유출 방지 목록: search_content 와 glob_paths 가 설명에 적어 둔 이름, 접두사, 접미사를 빠짐없이 건너뛴다.
 * 기대 목록은 {@link FileTools#SEARCH_DESCRIPTION} 이 모델에게 약속한 내용을 그대로 옮긴 것이다.
 */
class FileSearchExclusionsTest {

	/** 디렉터리 이름으로도 파일 이름으로도 쓰이는 정확한 이름들. */
	static Stream<String> exactNames() {
		return Stream.of(".git", "target", "build", "dist", "out", "node_modules", "vendor", ".gradle", ".m2", ".cache",
				".config", ".idea", ".mini-agent", ".omo", ".ssh", ".aws", ".codex", ".claude", "auth", "authentication",
				"credentials", "keys", "certs", "certificates", "runs", "run-requests", "tool-evidence",
				"model-selections", "changes", "logs", ".credentials", "credentials.json", "secrets.json", ".npmrc",
				".netrc", "auth.json");
	}

	/** .env, id_rsa, id_ed25519 로 시작하는 이름들. */
	static Stream<String> prefixedNames() {
		return Stream.of(".env", ".envrc", ".env.production", "id_rsa", "id_rsa.pub", "id_ed25519", "id_ed25519.pub",
				"id_ed25519_work");
	}

	/** .key, .pem, .crt, .cer, .der, .p12, .pfx, .jks, .keystore 로 끝나는 이름들. */
	static Stream<String> suffixedNames() {
		return Stream.of("server.key", "tls.pem", "ca.crt", "ca.cer", "ca.der", "client.p12", "client.pfx", "trust.jks",
				"release.keystore");
	}

	static Stream<String> allExcludedNames() {
		return Stream.of(exactNames(), prefixedNames(), suffixedNames()).flatMap(names -> names);
	}

	@TempDir
	Path work;

	private final FileTools tools = new FileTools(List.of(), 1024 * 1024, null);

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	private void file(String name, String content) throws Exception {
		Path file = this.work.resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private void assertOnlyVisibleSurvives(String... visible) throws Exception {
		assertThat(this.tools.searchContent(".", "needle", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly(visible);
		assertThat(this.tools.globPaths(".", "**", null, null, context()).matches())
			.extracting(FileSearch.Match::path).containsExactly(visible);
	}

	@ParameterizedTest
	@MethodSource("allExcludedNames")
	void excludedNameIsPrunedAsADirectory(String name) throws Exception {
		file("visible.txt", "needle");
		file("src/" + name + "/inner.txt", "needle");
		file("src/ok.txt", "needle");

		assertOnlyVisibleSurvives("src/ok.txt", "visible.txt");
		assertThatThrownBy(() -> this.tools.globPaths("src/" + name, "**", null, null, context()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ignored search root");
		assertThatThrownBy(() -> this.tools.searchContent("src/" + name, "needle", null, null, context()))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ignored search root");
	}

	@ParameterizedTest
	@MethodSource("allExcludedNames")
	void excludedNameIsSkippedAsAFile(String name) throws Exception {
		file("visible.txt", "needle");
		file("src/" + name, "needle");
		file("src/ok.txt", "needle");

		assertOnlyVisibleSurvives("src/ok.txt", "visible.txt");
	}

	@ParameterizedTest
	@ValueSource(strings = { ".SSH", "Credentials.JSON", "ID_ED25519.PUB", "Server.PEM", ".ENV.Local", "Keystore.JKS" })
	void matchingIgnoresCase(String name) throws Exception {
		file("visible.txt", "needle");
		file("file/" + name, "needle");
		file("dir/" + name + "/inner.txt", "needle");

		assertOnlyVisibleSurvives("visible.txt");
	}

	@Test
	void everyExcludedNameIsAdvertisedToTheModel() {
		// 도구 설명이 약속한 목록과 코드의 목록이 어긋나면 모델은 건너뛰어진다고 믿는 파일을 찾아 헤맨다.
		List<String> advertised = List.of(FileTools.SEARCH_DESCRIPTION.toLowerCase(Locale.ROOT).split("[\\s,;/]+"));

		assertThat(advertised).containsAll(exactNames().toList());
		assertThat(advertised).contains(".env", "id_rsa", "id_ed25519");
		assertThat(advertised).contains(".key", ".pem", ".crt", ".cer", ".der", ".p12", ".pfx", ".jks", ".keystore");
	}

	@Test
	void nearMissNamesAreStillSearchable() throws Exception {
		for (String name : List.of("keys.txt", "authors.md", "envoy.txt", "build.gradle", "logs.txt", "Keystore.java",
				"secrets.json.md", "notes.pem.txt")) {
			file(name, "needle");
		}
		file("node_modules_notes/readme.md", "needle");

		assertOnlyVisibleSurvives("Keystore.java", "authors.md", "build.gradle", "envoy.txt", "keys.txt", "logs.txt",
				"node_modules_notes/readme.md", "notes.pem.txt", "secrets.json.md");
	}

}
