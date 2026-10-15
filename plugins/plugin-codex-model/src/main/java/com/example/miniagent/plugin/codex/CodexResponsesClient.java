package com.example.miniagent.plugin.codex;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.model.ChatResponse;
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
		try (Exchange exchange = open(body, credentials, sessionId)) {
			exchange.checkStatus();
			return CodexResponses.parseSse(exchange.lines, this.json);
		}
	}

	Flux<ChatResponse> stream(ObjectNode body, CodexCredentials credentials, @Nullable String sessionId,
			String model, @Nullable String reasoningEffort) {
		return Flux.using(() -> open(body, credentials, sessionId), exchange -> {
			CodexResponses.StreamingParser parser = new CodexResponses.StreamingParser();
			return Flux.<ChatResponse>generate(sink -> {
				try {
					exchange.checkStatus();
					ChatResponse chunk = parser.next(exchange.lines, this.json, model, reasoningEffort);
					if (!exchange.lines.cancelled) {
						sink.next(chunk);
					}
					if (parser.completed() || exchange.lines.cancelled) {
						sink.complete();
					}
				}
				catch (RuntimeException ex) {
					if (!exchange.lines.cancelled) {
						sink.error(ex);
						return;
					}
					// Closing a cancelled exchange wakes its blocking read; that is not a stream failure.
					sink.complete();
				}
			});
		}, Exchange::close);
	}

	private Exchange open(ObjectNode body, CodexCredentials credentials, @Nullable String sessionId) {
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
			return lines;
		});
		// 연결 실패처럼 본문이 오기 전에 끝나는 오류도 같은 큐로 받는다.
		response.whenComplete((ignored, error) -> {
			if (error != null) {
				lines.fail(error);
			}
		});
		return new Exchange(lines, response);
	}

	private final class Exchange implements AutoCloseable {

		private final SseLines lines;

		private final CompletableFuture<HttpResponse<Void>> response;

		private boolean checked;

		Exchange(SseLines lines, CompletableFuture<HttpResponse<Void>> response) {
			this.lines = lines;
			this.response = response;
		}

		void checkStatus() {
			if (this.checked) {
				return;
			}
			// 첫 줄(또는 끝, 오류)이 올 때까지 기다린다. 그때는 상태 코드가 정해져 있다.
			this.lines.hasNext();
			if (this.lines.status != 200) {
				StringBuilder errorBody = new StringBuilder();
				this.lines.forEachRemaining(line -> errorBody.append(errorBody.isEmpty() ? "" : "\n").append(line));
				throw new CodexHttpException(this.lines.status, errorBody.toString());
			}
			this.checked = true;
		}

		@Override
		public void close() {
			this.lines.cancel();
			this.response.cancel(true);
		}

	}

	/** 응답 본문을 줄 단위로 받는 큐. 읽는 쪽에는 {@link Iterator} 로 보이고, 기다릴 때마다 idleTimeout 을 건다. */
	static final class SseLines implements HttpResponse.BodySubscriber<Void>, Iterator<String> {

		static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

		private static final Object END = new Object();

		// One requested batch plus a terminal signal. No decoded-line publisher buffer.
		private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2);

		private final CompletableFuture<Void> body = new CompletableFuture<>();

		private final Duration idleTimeout;

		private volatile Flow.@Nullable Subscription subscription;

		private volatile boolean cancelled;

		volatile int status;

		private @Nullable Object next;

		private byte[] batch = new byte[0];

		private int offset;

		private int received;

		private boolean ended;

		SseLines(Duration idleTimeout) {
			this.idleTimeout = idleTimeout;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			if (this.cancelled) {
				subscription.cancel();
			}
			else {
				subscription.request(1);
			}
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			long size = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
			if (size > MAX_RESPONSE_BYTES - this.received) {
				fail(new IllegalStateException("Codex response exceeds " + MAX_RESPONSE_BYTES + " bytes"));
				Flow.Subscription current = this.subscription;
				if (current != null) {
					current.cancel();
				}
				return;
			}
			this.received += (int) size;
			byte[] bytes = new byte[(int) size];
			int position = 0;
			for (ByteBuffer buffer : buffers) {
				int length = buffer.remaining();
				buffer.get(bytes, position, length);
				position += length;
			}
			this.queue.offer(bytes);
		}

		@Override
		public void onError(Throwable error) {
			fail(error);
		}

		@Override
		public void onComplete() {
			this.queue.offer(END);
			this.body.complete(null);
		}

		void fail(Throwable error) {
			this.queue.offer(error);
			this.body.completeExceptionally(error);
		}

		void cancel() {
			this.cancelled = true;
			Flow.Subscription current = this.subscription;
			if (current != null) {
				current.cancel();
			}
			this.queue.clear();
			this.queue.offer(END);
			this.body.cancel(true);
		}

		@Override
		public CompletableFuture<Void> getBody() {
			return this.body;
		}

		@Override
		public boolean hasNext() {
			if (this.next == null) {
				this.next = readLine();
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

		private Object readLine() {
			ByteArrayOutputStream line = new ByteArrayOutputStream();
			if (this.ended) {
				return END;
			}
			while (!this.cancelled) {
				if (this.offset == this.batch.length) {
					Object item = take();
					if (item == END) {
						this.ended = true;
						return line.size() == 0 ? END : line.toString(StandardCharsets.UTF_8);
					}
					this.batch = (byte[]) item;
					this.offset = 0;
					Flow.Subscription current = this.subscription;
					if (current != null) {
						current.request(1);
					}
					continue;
				}
				byte value = this.batch[this.offset++];
				if (value == '\n') {
					String valueLine = line.toString(StandardCharsets.UTF_8);
					return valueLine.endsWith("\r") ? valueLine.substring(0, valueLine.length() - 1) : valueLine;
				}
				line.write(value);
			}
			return END;
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
