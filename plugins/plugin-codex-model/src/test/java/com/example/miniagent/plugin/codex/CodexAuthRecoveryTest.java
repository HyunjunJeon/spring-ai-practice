package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401 복구: 병렬 턴과 위임 자식이 한 CodexAuth 를 같이 쓴다. 같은 거절 토큰으로 여러 번 복구해도
 * 회전된 refresh token 을 다시 쓰면 안 된다. 서버가 재사용을 감지하면 로그인이 풀린다.
 */
class CodexAuthRecoveryTest {

	@TempDir
	Path dir;

	private final ExecutorService callers = Executors.newFixedThreadPool(2);

	private CodexEndpoints endpoints;

	@BeforeEach
	void start() throws IOException {
		this.endpoints = new CodexEndpoints();
	}

	@AfterEach
	void stop() {
		this.callers.shutdownNow();
		this.endpoints.close();
	}

	@Test
	void concurrentRecoveriesFromOneRejectedTokenSpendItsRefreshTokenOnce() throws Exception {
		CodexAuth auth = auth();
		CodexCredentials rejected = CodexEndpoints.ownToken(Instant.now().plusSeconds(3600), "refresh-1");
		auth.saveOwn(rejected);
		this.endpoints.honor("refresh-1");
		CyclicBarrier together = new CyclicBarrier(2);
		Callable<Optional<CodexCredentials>> recover = () -> {
			together.await(10, TimeUnit.SECONDS);
			return auth.recoverFromUnauthorized(rejected);
		};

		List<Future<Optional<CodexCredentials>>> results = this.callers.invokeAll(List.of(recover, recover), 20,
				TimeUnit.SECONDS);

		assertThat(this.endpoints.presentedRefreshTokens()).containsExactly("refresh-1");
		CodexCredentials stored = auth.readOwn().orElseThrow();
		assertThat(stored.refreshToken()).isEqualTo("rotated-1");
		for (Future<Optional<CodexCredentials>> result : results) {
			assertThat(result.get()).get().extracting(CodexCredentials::accessToken).isEqualTo(stored.accessToken());
		}
	}

	@Test
	void recoveryWithTheTokenStillInTheStoreRefreshesIt() {
		CodexAuth auth = auth();
		CodexCredentials rejected = CodexEndpoints.ownToken(Instant.now().plusSeconds(3600), "refresh-1");
		auth.saveOwn(rejected);
		this.endpoints.honor("refresh-1");

		Optional<CodexCredentials> recovered = auth.recoverFromUnauthorized(rejected);

		assertThat(this.endpoints.presentedRefreshTokens()).containsExactly("refresh-1");
		assertThat(recovered).get().satisfies(credentials -> {
			assertThat(credentials.refreshToken()).isEqualTo("rotated-1");
			assertThat(credentials.accessToken()).isNotEqualTo(rejected.accessToken());
		});
		assertThat(auth.readOwn()).get().extracting(CodexCredentials::accessToken)
			.isEqualTo(recovered.orElseThrow().accessToken());
	}

	@Test
	void ownCredentialsWithoutRefreshTokenAreNotRecovered() {
		CodexAuth auth = auth();
		CodexCredentials withoutRefresh = CodexCredentials.fromAccessToken(
				CodexEndpoints.jwt(Instant.now().plusSeconds(3600), "no-refresh"), null, CodexCredentials.Source.MINI_AGENT);

		assertThat(auth.recoverFromUnauthorized(withoutRefresh)).isEmpty();
		assertThat(this.endpoints.tokenRequests()).isEmpty();
	}

	private CodexAuth auth() {
		return new CodexAuth(this.dir.resolve("auth/openai-codex.json"), this.dir.resolve("codex/auth.json"), "auto",
				this.endpoints.oauth());
	}

}
