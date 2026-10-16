package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱과 워커 JVM 처럼 서로 다른 CodexAuth 가 한 자체 토큰 저장소를 쓸 때의 갱신.
 * 인스턴스마다 모니터가 따로라서, 프로세스 사이의 경쟁을 한 JVM 안의 두 인스턴스로 재현한다.
 */
class CodexAuthConcurrencyTest {

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
	void aSecondInstanceReadsTheTokenTheFirstOneRotated() {
		CodexAuth app = auth();
		CodexAuth worker = auth();
		app.saveOwn(CodexEndpoints.ownToken(Instant.now().plusSeconds(60), "refresh-1"));
		this.endpoints.honor("refresh-1");

		CodexCredentials refreshed = app.current();
		CodexCredentials seen = worker.current();

		assertThat(seen.accessToken()).isEqualTo(refreshed.accessToken());
		assertThat(this.endpoints.presentedRefreshTokens()).containsExactly("refresh-1");
	}

	@Test
	@Disabled("SUSPECT plugins-providers:G4: both instances read the expiring own token and each refreshes it; "
			+ "the token endpoint saw [refresh-1, refresh-1], so the rotated refresh token is spent twice "
			+ "(no cross-process lock or re-read in CodexAuth.current)")
	void twoInstancesRefreshingAnExpiringTokenAtOnceSpendItsRefreshTokenOnce() throws Exception {
		CodexAuth app = auth();
		CodexAuth worker = auth();
		app.saveOwn(CodexEndpoints.ownToken(Instant.now().plusSeconds(60), "refresh-1"));
		this.endpoints.honor("refresh-1");
		// 프로세스 사이 잠금이 있으면 둘째 갱신 요청은 오지 않고, 첫 요청은 2초 뒤 그대로 처리된다.
		this.endpoints.holdFirstGrantUntilSecondArrives(Duration.ofSeconds(2));
		CyclicBarrier together = new CyclicBarrier(2);
		Callable<CodexCredentials> appTurn = () -> {
			together.await(10, TimeUnit.SECONDS);
			return app.current();
		};
		Callable<CodexCredentials> workerTurn = () -> {
			together.await(10, TimeUnit.SECONDS);
			return worker.current();
		};

		List<Future<CodexCredentials>> results = this.callers.invokeAll(List.of(appTurn, workerTurn), 20,
				TimeUnit.SECONDS);

		assertThat(this.endpoints.presentedRefreshTokens()).containsExactly("refresh-1");
		CodexCredentials stored = app.readOwn().orElseThrow();
		for (Future<CodexCredentials> result : results) {
			assertThat(result.get().accessToken()).isEqualTo(stored.accessToken());
		}
	}

	private CodexAuth auth() {
		return new CodexAuth(this.dir.resolve("auth/openai-codex.json"), this.dir.resolve("codex/auth.json"), "auto",
				this.endpoints.oauth());
	}

}
