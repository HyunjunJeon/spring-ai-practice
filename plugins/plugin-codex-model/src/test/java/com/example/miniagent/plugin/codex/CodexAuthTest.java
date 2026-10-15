package com.example.miniagent.plugin.codex;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 핵심 규칙 검증: Codex CLI 토큰은 절대 갱신하지 않는다. */
class CodexAuthTest {

	@TempDir
	Path dir;

	/** 갱신이 호출되면 테스트를 실패시키는 OAuth. */
	private final CodexOAuth refusingOAuth = new CodexOAuth("test") {
		@Override
		public CodexCredentials refresh(String refreshToken) {
			throw new AssertionError("refresh must not be called");
		}
	};

	@Test
	void expiredCodexCliTokenIsNeverRefreshed() throws Exception {
		writeCodexCliAuth(Instant.now().minusSeconds(60));
		CodexAuth auth = new CodexAuth(this.dir.resolve("own.json"), this.dir.resolve("auth.json"), "auto",
				this.refusingOAuth);

		assertThat(auth.readCodexCli()).get().satisfies(c -> assertThat(c.refreshToken()).isNull());
		assertThatThrownBy(auth::current).isInstanceOf(CodexAuth.CodexAuthException.class).hasMessageContaining("codex");
		assertThat(auth.recoverFromUnauthorized(auth.readCodexCli().orElseThrow())).isEmpty();
	}

	@Test
	void validCodexCliTokenIsUsedWhenNoOwnToken() throws Exception {
		writeCodexCliAuth(Instant.now().plusSeconds(3600));
		CodexAuth auth = new CodexAuth(this.dir.resolve("own.json"), this.dir.resolve("auth.json"), "auto",
				this.refusingOAuth);

		CodexCredentials credentials = auth.current();

		assertThat(credentials.source()).isEqualTo(CodexCredentials.Source.CODEX_CLI);
		assertThat(credentials.accountId()).isEqualTo("acct_test");
		assertThat(credentials.plan()).isEqualTo("pro");
	}

	@Test
	void ownTokenIsPreferredAndStoredWithOwnerOnlyPermissions() throws Exception {
		writeCodexCliAuth(Instant.now().plusSeconds(3600));
		CodexAuth auth = new CodexAuth(this.dir.resolve("auth/own.json"), this.dir.resolve("auth.json"), "auto",
				this.refusingOAuth);
		auth.saveOwn(CodexCredentials.fromAccessToken(jwt(Instant.now().plusSeconds(3600)), "refresh-1",
				CodexCredentials.Source.MINI_AGENT));

		assertThat(auth.current().source()).isEqualTo(CodexCredentials.Source.MINI_AGENT);
		assertThat(Files.getPosixFilePermissions(this.dir.resolve("auth/own.json")))
			.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		try (Stream<Path> files = Files.list(auth.ownStore().getParent())) {
			assertThat(files).containsExactly(auth.ownStore());
		}
	}

	@Test
	void replacementPublishesPrivateTokenWithoutOverwritingOriginalInode() throws Exception {
		Path store = this.dir.resolve("own.json");
		CodexAuth auth = new CodexAuth(store, this.dir.resolve("auth.json"), "auto", this.refusingOAuth);
		auth.saveOwn(CodexCredentials.fromAccessToken(jwt(Instant.now().plusSeconds(3600)), "old-refresh",
				CodexCredentials.Source.MINI_AGENT));
		Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-r--r--"));
		Path original = Files.createLink(this.dir.resolve("original.json"), store);
		byte[] previous = Files.readAllBytes(original);

		auth.saveOwn(CodexCredentials.fromAccessToken(jwt(Instant.now().plusSeconds(7200)), "new-refresh",
				CodexCredentials.Source.MINI_AGENT));

		assertThat(auth.readOwn()).get().satisfies(c -> assertThat(c.refreshToken()).isEqualTo("new-refresh"));
		assertThat(Files.readAllBytes(original)).isEqualTo(previous);
		assertThat(Files.isSameFile(store, original)).isFalse();
		assertThat(Files.getPosixFilePermissions(store)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		try (Stream<Path> files = Files.list(this.dir)) {
			assertThat(files).containsExactlyInAnyOrder(store, original);
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void failedWriteLeavesPreviousTokenOrAbsentDestinationAndNoTemporaryFiles(boolean existed) throws Exception {
		Path store = this.dir.resolve("own.json");
		CodexAuth auth = new CodexAuth(store, this.dir.resolve("auth.json"), "auto", this.refusingOAuth);
		if (existed) {
			auth.saveOwn(CodexCredentials.fromAccessToken(jwt(Instant.now().plusSeconds(3600)), "old-refresh",
					CodexCredentials.Source.MINI_AGENT));
		}
		byte[] previous = existed ? Files.readAllBytes(store) : null;
		CodexCredentials invalid = CodexCredentials.fromAccessToken(jwt(Instant.now().plusSeconds(7200)), "bad\ud800",
				CodexCredentials.Source.MINI_AGENT);

		assertThatThrownBy(() -> auth.saveOwn(invalid)).isInstanceOf(UncheckedIOException.class);

		if (existed) {
			assertThat(Files.readAllBytes(store)).isEqualTo(previous);
		}
		else {
			assertThat(store).doesNotExist();
		}
		try (Stream<Path> files = Files.list(this.dir)) {
			assertThat(files).containsExactlyElementsOf(existed ? List.of(store) : List.of());
		}
	}

	@Test
	void failedPublicationKeepsDestinationAndRemovesTemporaryFile() throws Exception {
		Path store = Files.createDirectory(this.dir.resolve("own.json"));
		Path child = Files.writeString(store.resolve("child"), "original");
		CodexAuth auth = new CodexAuth(store, this.dir.resolve("auth.json"), "auto", this.refusingOAuth);
		CodexCredentials credentials = CodexCredentials.fromAccessToken(jwt(Instant.now().plusSeconds(3600)),
				"refresh", CodexCredentials.Source.MINI_AGENT);

		assertThatThrownBy(() -> auth.saveOwn(credentials)).isInstanceOf(UncheckedIOException.class);

		assertThat(Files.readString(child)).isEqualTo("original");
		try (Stream<Path> files = Files.list(this.dir)) {
			assertThat(files).containsExactly(store);
		}
	}

	private void writeCodexCliAuth(Instant expiresAt) throws Exception {
		Files.writeString(this.dir.resolve("auth.json"), """
				{"OPENAI_API_KEY": null, "tokens": {"access_token": "%s", "refresh_token": "cli-refresh", "account_id": "acct_test"}}
				""".formatted(jwt(expiresAt)));
	}

	private static String jwt(Instant expiresAt) {
		String claims = """
				{"exp": %d, "https://api.openai.com/auth": {"chatgpt_account_id": "acct_test", "chatgpt_plan_type": "pro"}}
				""".formatted(expiresAt.getEpochSecond());
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
				+ encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
	}

}
