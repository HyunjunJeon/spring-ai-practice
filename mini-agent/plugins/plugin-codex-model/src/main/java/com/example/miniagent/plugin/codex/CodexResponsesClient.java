package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST {baseUrl}/responses} 를 SSE 로 호출한다.
 *
 * <p>주의: {@code chatgpt.com/backend-api} 는 공개 문서가 없는 ChatGPT 내부 API 다. 언제든 바뀔 수 있고,
 * 사용량은 API 크레딧이 아니라 ChatGPT 플랜 한도에서 차감된다.
 *
 * <p>대기 제한: 응답 헤더든 SSE 줄이든 {@code idleTimeout} 동안 아무것도 오지 않으면 호출을 끊는다.
 * {@code connectTimeout} 은 연결까지만 지켜 주므로, 연결된 뒤 서버가 멈추면 작업 스레드가 계속 묶여 있게 된다.
 * 그래서 본문을 줄 단위로 큐에 받고, 기다릴 때마다 제한 시간을 건다. 기다리는 중에 인터럽트(TUI 의 Esc)가 오면 바로 멈춘다.
 */
public class CodexResponsesClient {

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

	private final JsonMapper json;

	private final String baseUrl;

	private final String originator;

	private final Duration idleTimeout;

	public CodexResponsesClient(JsonMapper json, String baseUrl, String originator, Duration idleTimeout) {
		this.json = json;
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.originator = originator;
		this.idleTimeout = idleTimeout;
	}

	CodexResponses.Result send(ObjectNode body, CodexCredentials credentials, @Nullable String sessionId) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + "/responses"))
			.header("Authorization", "Bearer " + credentials.accessToken())
			.header("chatgpt-account-id", credentials.accountId())
			.header("originator", this.originator)
			.header("OpenAI-Beta", "responses=experimental")
			.header("Accept", "text/event-stream")
			.header("Content-Type", "application/json")
			.header("User-Agent", "mini-agent/0.1 (spring-ai 2.0.1)")
			.POST(HttpRequest.BodyPublishers.ofString(this.json.writeValueAsString(body)));
		if (sessionId != null) {
			request.header("session-id", sessionId);
		}

		SseLines lines = new SseLines(this.idleTimeout);
		CompletableFuture<HttpResponse<Void>> response = this.http.sendAsync(request.build(), info -> {
			lines.status = info.statusCode();
			return HttpResponse.BodySubscribers.fromLineSubscriber(lines);
		});
		// 연결 실패처럼 본문이 오기 전에 끝나는 오류도 같은 큐로 받는다.
		response.whenComplete((ignored, error) -> {
			if (error != null) {
				lines.fail(error);
			}
		});
		try {
			// 첫 줄(또는 끝, 오류)이 올 때까지 기다린다. 그때는 상태 코드가 정해져 있다.
			lines.hasNext();
			if (lines.status != 200) {
				StringBuilder errorBody = new StringBuilder();
				lines.forEachRemaining(line -> errorBody.append(errorBody.isEmpty() ? "" : "\n").append(line));
				throw new CodexHttpException(lines.status, errorBody.toString());
			}
			return CodexResponses.parseSse(lines, this.json);
		}
		finally {
			// 정상적으로 다 읽었으면 아무 일도 없다. 시간 초과나 오류로 빠져나오면 연결을 끊는다.
			lines.cancel();
			response.cancel(true);
		}
	}

	/** 응답 본문을 줄 단위로 받는 큐. 읽는 쪽에는 {@link Iterator} 로 보이고, 기다릴 때마다 idleTimeout 을 건다. */
	static final class SseLines implements Flow.Subscriber<String>, Iterator<String> {

		private static final Object END = new Object();

		private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

		private final Duration idleTimeout;

		private volatile Flow.@Nullable Subscription subscription;

		volatile int status;

		private @Nullable Object next;

		SseLines(Duration idleTimeout) {
			this.idleTimeout = idleTimeout;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(String line) {
			this.queue.offer(line);
		}

		@Override
		public void onError(Throwable error) {
			this.queue.offer(error);
		}

		@Override
		public void onComplete() {
			this.queue.offer(END);
		}

		void fail(Throwable error) {
			this.queue.offer(error);
		}

		void cancel() {
			Flow.Subscription current = this.subscription;
			if (current != null) {
				current.cancel();
			}
		}

		@Override
		public boolean hasNext() {
			if (this.next == null) {
				this.next = take();
			}
			return this.next != END;
		}

		@Override
		public String next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			String line = (String) this.next;
			this.next = null;
			return line;
		}

		private Object take() {
			Object item;
			try {
				item = this.queue.poll(this.idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Codex 호출이 중단되었습니다", ex);
			}
			if (item == null) {
				throw new IllegalStateException("Codex 응답이 %s 동안 오지 않아 끊었습니다 (agent.codex.idle-timeout)"
					.formatted(readable(this.idleTimeout)));
			}
			if (item instanceof Throwable error) {
				Throwable cause = (error instanceof CompletionException && error.getCause() != null) ? error.getCause()
						: error;
				if (cause instanceof IOException io) {
					throw new UncheckedIOException("Codex 호출 실패: " + io.getMessage(), io);
				}
				throw new IllegalStateException("Codex 호출 실패: " + cause.getMessage(), cause);
			}
			return item;
		}

		private static String readable(Duration duration) {
			return (duration.toSeconds() > 0) ? duration.toSeconds() + "초" : duration.toMillis() + "ms";
		}

	}

	/** HTTP 오류. 사람이 읽을 안내 문구로 바꿔 준다. */
	public class CodexHttpException extends RuntimeException {

		private final int status;

		CodexHttpException(int status, String body) {
			super(describe(status, body));
			this.status = status;
		}

		public int status() {
			return this.status;
		}

	}

	private String describe(int status, String body) {
		String message = body;
		long resetsIn = -1;
		try {
			JsonNode error = this.json.readTree(body).path("error");
			if (!error.isMissingNode()) {
				message = error.path("message").asString(body);
				resetsIn = error.path("resets_in_seconds").asLong(-1);
			}
		}
		catch (RuntimeException ignored) {
			// 본문이 JSON 이 아니면 그대로 보여준다.
		}
		return switch (status) {
			case 401 -> "Codex 401 (토큰 무효/만료): " + message + "  → `codex` 를 한 번 실행하거나 /login";
			case 429 -> "Codex 429 (사용 한도): " + message
					+ ((resetsIn > 0) ? "  (약 %d분 후 초기화)".formatted(resetsIn / 60) : "");
			default -> "Codex HTTP " + status + ": " + message;
		};
	}

}
