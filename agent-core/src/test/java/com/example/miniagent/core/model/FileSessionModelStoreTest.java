package com.example.miniagent.core.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSessionModelStoreTest {

	@TempDir
	Path home;

	@Test
	void restartRestoresSelectionWithMemoryAbsentAndDoesNotCreateMemory() throws IOException {
		ModelSelection selection = new ModelSelection(new ModelRef("codex", "gpt-test"), Map.of("effort", "high"));
		FileSessionModelStore first = new FileSessionModelStore(this.home);
		assertThat(first.load("session")).isEmpty();
		first.save("session", selection);
		assertThat(new FileSessionModelStore(this.home).load("session")).contains(selection);
		assertThat(this.home.resolve("sessions")).doesNotExist();
	}

	@Test
	void conversationBytesAreUntouchedAndAtomicReplacementCleansTemporaryFiles() throws IOException {
		Path memory = this.home.resolve("sessions/session.jsonl");
		Files.createDirectories(memory.getParent());
		byte[] original = "{\"message\":\"sentinel\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(memory, original);
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		store.save("session", new ModelSelection(new ModelRef("openai", "a")));
		ModelSelection next = new ModelSelection(new ModelRef("zai", "b"), Map.of("reasoningEffort", "low"));
		store.save("session", next);
		assertThat(new FileSessionModelStore(this.home).load("session")).contains(next);
		assertThat(Files.readAllBytes(memory)).isEqualTo(original);
		try (var files = Files.list(this.home.resolve("model-selections"))) {
			assertThat(files.map(path -> path.getFileName().toString())).containsExactly("session.json");
		}
	}

	@Test
	void storedSchemaIsPrivateAndContainsOnlySelectionNotCredentialsOrRuntimeObjects() throws IOException {
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		store.save("session", new ModelSelection(new ModelRef("codex", "test"), Map.of("effort", "high")));
		Path directory = this.home.resolve("model-selections");
		Path file = directory.resolve("session.json");
		assertThat(Files.getPosixFilePermissions(directory)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
		assertThat(Files.getPosixFilePermissions(file)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(Files.readAllBytes(file));
		assertThat(json.properties().stream().map(Map.Entry::getKey))
			.containsExactlyInAnyOrder("schemaVersion", "provider", "model", "options");
		assertThat(json.path("schemaVersion").intValue()).isEqualTo(1);
		assertThat(json.path("options").properties().stream().map(Map.Entry::getKey)).containsExactly("effort");
		assertThat(Files.readString(file)).doesNotContain("apiKey", "authorization", "headers", "client",
				"chatModel", "bindingId", "synthetic-secret");
	}

	@ParameterizedTest
	@ValueSource(strings = { "../escape", "..", ".", "/absolute", "a/b", "a\\b", "", "a.json",
			" space", "a\nb", "한글", "-leading" })
	void traversalAndUnsafeIdsAreRejectedBeforeReadingOrWriting(String id) throws IOException {
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		assertThatThrownBy(() -> store.load(id)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.save(id, new ModelSelection(new ModelRef("fake", "a"))))
			.isInstanceOf(IllegalArgumentException.class);
		try (var files = Files.list(this.home.resolve("model-selections"))) {
			assertThat(files).isEmpty();
		}
	}

	@Test
	void validUuidAndBoundedIdsRoundTrip() throws IOException {
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		ModelSelection selection = new ModelSelection(new ModelRef("fake", "a"));
		for (String id : new String[] { java.util.UUID.randomUUID().toString(), "A_1-test", "a".repeat(128) }) {
			store.save(id, selection);
			assertThat(store.load(id)).contains(selection);
		}
		assertThatThrownBy(() -> store.load("a".repeat(129))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> store.load(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"", "{", "null", "[]",
			"{\"schemaVersion\":2,\"provider\":\"fake\",\"model\":\"a\",\"options\":{}}",
			"{\"schemaVersion\":1.0,\"provider\":\"fake\",\"model\":\"a\",\"options\":{}}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"model\":\"a\"}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"model\":\"a\",\"options\":{},\"apiKey\":\"secret\"}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"model\":\"a\",\"options\":{\"apiKey\":\"secret\"}}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"model\":\"a\",\"options\":{\"effort\":2}}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"model\":\"a\",\"options\":{\"effort\":\"\"}}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"model\":\"a\",\"options\":{}} {}",
			"{\"schemaVersion\":1,\"provider\":\"fake\",\"provider\":\"other\",\"model\":\"a\",\"options\":{}}"
	})
	void corruptAndUnknownSchemasAreRejectedWithoutOverwriting(String content) throws IOException {
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		Path file = this.home.resolve("model-selections/session.json");
		Files.writeString(file, content);
		assertThatThrownBy(() -> store.load("session")).isInstanceOf(IOException.class);
		assertThat(Files.readString(file)).isEqualTo(content);
	}

	@Test
	void oversizedFilesAreRejected() throws IOException {
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		Files.writeString(this.home.resolve("model-selections/session.json"), " ".repeat(65537));
		assertThatThrownBy(() -> store.load("session")).isInstanceOf(IOException.class);
	}

	@Test
	void symlinkFilesAndDirectoriesCannotEscapeStore() throws IOException {
		FileSessionModelStore store = new FileSessionModelStore(this.home);
		Path external = this.home.resolve("external.json");
		Files.writeString(external, "sentinel");
		Path link = this.home.resolve("model-selections/session.json");
		Files.createSymbolicLink(link, external);
		assertThatThrownBy(() -> store.load("session")).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> store.save("session", new ModelSelection(new ModelRef("fake", "a"))))
			.isInstanceOf(IOException.class);
		assertThat(Files.readString(external)).isEqualTo("sentinel");
		Files.delete(link);
		Files.delete(this.home.resolve("model-selections"));
		Files.createSymbolicLink(this.home.resolve("model-selections"), this.home);
		assertThatThrownBy(() -> store.load("session")).isInstanceOf(IOException.class);
		assertThatThrownBy(() -> new FileSessionModelStore(this.home)).isInstanceOf(IOException.class);
	}

}
