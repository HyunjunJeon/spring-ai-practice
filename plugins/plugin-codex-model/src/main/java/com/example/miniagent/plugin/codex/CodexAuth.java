package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 요청에 쓸 토큰을 고른다.
 *
 * <p><b>규칙: Codex CLI 의 토큰({@code ~/.codex/auth.json})은 읽기만 하고 절대 갱신하지 않는다.</b>
 * OpenAI 의 refresh token 은 갱신할 때마다 새것으로 바뀐다(rotation). 여기서 Codex CLI 의 refresh token 으로
 * 갱신하면 Codex CLI 에 저장된 refresh token 이 무효가 되어 Codex CLI 가 로그아웃된다.
 * 갱신은 {@code /login} 으로 받은 자체 토큰({@code ~/.mini-agent/auth/openai-codex.json})에만 한다.
 */
public class CodexAuth {

	/** 만료 5분 전부터 갱신 대상으로 본다. */
	private static final long REFRESH_MARGIN_SECONDS = 300;

	private final JsonMapper json = JsonMapper.builder().build();

	private final Path ownStore;

	private final Path codexCliAuth;

	private final String mode;

	private final CodexOAuth oauth;

	public CodexAuth(Path ownStore, Path codexCliAuth, String mode, CodexOAuth oauth) {
		this.ownStore = ownStore;
		this.codexCliAuth = codexCliAuth;
		this.mode = mode;
		this.oauth = oauth;
	}

	/** 지금 쓸 수 있는 토큰. 없거나 만료되었으면 무엇을 해야 하는지 담은 예외를 던진다. */
	public synchronized CodexCredentials current() {
		if (!"codex-cli".equals(this.mode)) {
			Optional<CodexCredentials> own = readOwn();
			if (own.isPresent()) {
				CodexCredentials credentials = own.get();
				return credentials.expiresWithin(REFRESH_MARGIN_SECONDS) ? refresh(credentials) : credentials;
			}
			if ("mini-agent".equals(this.mode)) {
				throw new CodexAuthException("로그인이 필요합니다. /login 을 실행하세요.");
			}
		}
		CodexCredentials cli = readCodexCli().orElseThrow(() -> new CodexAuthException(
				"Codex 토큰이 없습니다. /login 을 실행하거나 Codex CLI 에서 `codex login` 을 하세요."));
		if (cli.expiresWithin(0)) {
			throw new CodexAuthException("Codex CLI 토큰이 만료되었습니다(" + cli.expiresAt()
					+ "). `codex` 를 한 번 실행하면 Codex CLI 가 갱신합니다. 또는 /login 으로 자체 토큰을 받으세요.");
		}
		return cli;
	}

	/** 401 을 받았을 때: 자체 토큰이면 강제로 갱신해 본다. Codex CLI 토큰이면 할 수 있는 게 없다. */
	public synchronized Optional<CodexCredentials> recoverFromUnauthorized(CodexCredentials failed) {
		if (failed.source() == CodexCredentials.Source.MINI_AGENT && failed.refreshToken() != null) {
			// 같은 토큰으로 401 을 받은 다른 호출이 먼저 갱신했으면 저장된 새 토큰을 쓴다.
			// failed 의 refresh token 은 이미 회전되어 다시 쓰면 서버가 재사용으로 보고 로그인을 끊는다.
			Optional<CodexCredentials> stored = readOwn();
			if (stored.isPresent() && !stored.get().accessToken().equals(failed.accessToken())) {
				return stored;
			}
			return Optional.of(refresh(failed));
		}
		return Optional.empty();
	}

	/** 자체 토큰을 지금 바로 갱신한다(/codex refresh). Codex CLI 토큰에는 쓰지 않는다. */
	public synchronized CodexCredentials refreshOwnNow() {
		CodexCredentials own = readOwn().orElseThrow(() -> new CodexAuthException("자체 토큰이 없습니다. /login 을 먼저 하세요."));
		return refresh(own);
	}

	public synchronized void saveOwn(CodexCredentials credentials) {
		ObjectNode node = this.json.createObjectNode();
		node.put("access_token", credentials.accessToken());
		node.put("refresh_token", credentials.refreshToken());
		try {
			Files.createDirectories(this.ownStore.getParent());
			// 생성 시점부터 소유자 전용인 파일에 완성한 뒤 게시한다. 기존 토큰은 실패 시 유지된다.
			Path temp = Files.createTempFile(this.ownStore.getParent(), "." + this.ownStore.getFileName() + ".", ".tmp",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Throwable failure = null;
			try {
				Files.writeString(temp, this.json.writeValueAsString(node), StandardCharsets.UTF_8);
				Files.move(temp, this.ownStore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (IOException | RuntimeException | Error ex) {
				failure = ex;
				throw ex;
			}
			finally {
				try {
					Files.deleteIfExists(temp);
				}
				catch (IOException | RuntimeException | Error ex) {
					if (failure != null) {
						failure.addSuppressed(ex);
					}
					else {
						throw ex;
					}
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public synchronized boolean deleteOwn() {
		try {
			return Files.deleteIfExists(this.ownStore);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public Optional<CodexCredentials> readOwn() {
		return read(this.ownStore, false);
	}

	public Optional<CodexCredentials> readCodexCli() {
		return read(this.codexCliAuth, true);
	}

	public Path ownStore() {
		return this.ownStore;
	}

	/** Listing never reads token contents or triggers refresh. */
	public boolean available() {
		return (!"codex-cli".equals(this.mode) && Files.isRegularFile(this.ownStore))
				|| (!"mini-agent".equals(this.mode) && Files.isRegularFile(this.codexCliAuth));
	}

	private CodexCredentials refresh(CodexCredentials credentials) {
		if (credentials.source() != CodexCredentials.Source.MINI_AGENT || credentials.refreshToken() == null) {
			throw new IllegalStateException("자체 토큰만 갱신할 수 있습니다");
		}
		CodexCredentials refreshed = this.oauth.refresh(credentials.refreshToken());
		saveOwn(refreshed);
		return refreshed;
	}

	/**
	 * 두 파일의 형식:
	 * <ul>
	 *   <li>Codex CLI: {@code {"tokens": {"access_token", "refresh_token", "account_id", ...}, ...}}</li>
	 *   <li>mini-agent: {@code {"access_token", "refresh_token"}}</li>
	 * </ul>
	 */
	private Optional<CodexCredentials> read(Path file, boolean codexCliFormat) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			JsonNode root = this.json.readTree(Files.readString(file, StandardCharsets.UTF_8));
			JsonNode tokens = codexCliFormat ? root.path("tokens") : root;
			String access = tokens.path("access_token").asString(null);
			if (access == null || access.isBlank()) {
				return Optional.empty();
			}
			// Codex CLI 토큰은 refresh token 을 아예 들고 다니지 않는다. 실수로라도 갱신할 수 없게.
			@Nullable String refresh = codexCliFormat ? null : tokens.path("refresh_token").asString(null);
			var source = codexCliFormat ? CodexCredentials.Source.CODEX_CLI : CodexCredentials.Source.MINI_AGENT;
			return Optional.of(CodexCredentials.fromAccessToken(access, refresh, source));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static class CodexAuthException extends RuntimeException {

		public CodexAuthException(String message) {
			super(message);
		}

	}

}
