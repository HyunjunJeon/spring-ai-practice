package com.example.miniagent.app.machine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentInputHandler;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalRequest;
import com.example.miniagent.core.model.*;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real MachineRunner over a scripted provider and a synthetic stdin.
 * Copied from MachineRunnerTest.Fixture, which keeps its own private copy.
 */
final class MachineFixture implements AutoCloseable {
	final Output output = new Output();
	final MachineProtocol protocol = new MachineProtocol(this.output);
	final RpcConsole console = new RpcConsole(this.protocol);
	final AgentSession session = new AgentSession();
	final MachineRunner runner;

	/** Builds the input handler; tests replace it only to reach a branch the real one never throws from. */
	interface HandlerFactory {
		AgentInputHandler create(Agent agent, AgentSession session, RpcConsole console, List<SlashCommand> commands);
	}

	MachineFixture(Path home, String mode, InputStream input, Consumer<Prompt> before,
			List<SlashCommand> commands) throws IOException {
		this(home, mode, input, before, commands, AgentInputHandler::new);
	}

	MachineFixture(Path home, String mode, InputStream input, Consumer<Prompt> before,
			List<SlashCommand> commands, HandlerFactory handlerFactory) throws IOException {
		var ref = new ModelRef("fake", "machine");
		ModelProvider provider = new ModelProvider() {
			@Override public String providerId() { return "fake"; }
			@Override public List<ModelEntry> models() { return List.of(new ModelEntry(ref, "fake", null)); }
			@Override public ProviderAuth auth() { return () -> true; }
			@Override public ChatModel create(ModelRef model, Map<String, String> options) {
				return new ScriptedChatModel() {
					@Override public Flux<ChatResponse> stream(Prompt prompt) {
						before.accept(prompt);
						return super.stream(prompt);
					}
				};
			}
		};
		var registry = new ModelRegistry(List.of(provider), model -> ChatClient.builder(model).build());
		var agent = new Agent(registry, new FileSessionModelStore(home), new ModelSelection(ref, Map.of()),
				this.session, home, "", List.of(), List.of(), List.of(), List.of(), 0, this.console::onTrace);
		var handler = handlerFactory.create(agent, this.session, this.console, commands);
		this.runner = new MachineRunner(handler, this.session, this.console, this.protocol, input, mode);
	}

	static ByteArrayInputStream input(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	static ApprovalRequest approvalRequest() {
		return new ApprovalRequest(UUID.randomUUID(), new RunId(UUID.randomUUID().toString()),
				"attempt", null, null, "tool", ApprovalRequest.digest("args"), ApprovalRequest.digest("diff"));
	}

	void send(String id, String method, Map<String, ?> payload) {
		var fields = new LinkedHashMap<String, Object>(payload);
		fields.put("v", 1); fields.put("id", id); fields.put("method", method);
		this.runner.accept(this.output.json.writeValueAsString(fields));
	}

	/** Sends a request that must be refused on the dispatch thread and returns its error code. */
	String refusalCode(String id, String method, Map<String, ?> payload) {
		int before = this.output.frames.size();
		send(id, method, payload);
		assertThat(this.output.frames).as("a refusal emits exactly one frame: the error").hasSize(before + 1);
		JsonNode frame = this.output.frames.getLast();
		assertThat(frame.path("type").asString()).isEqualTo("error");
		assertThat(frame.path("requestId").asString("")).isEqualTo(id);
		return frame.path("code").asString();
	}

	@Override public void close() { this.runner.close(); }

	/** Serves a fixed script, then either idles until closed or fails once released. */
	static final class ScriptedInput extends InputStream {
		private final byte[] script;
		private final CountDownLatch release;
		private final boolean failAtEnd;
		private int position;

		private ScriptedInput(byte[] script, CountDownLatch release, boolean failAtEnd) {
			this.script = script;
			this.release = release;
			this.failAtEnd = failAtEnd;
		}

		/** After the script read() blocks until close(), then reports EOF. */
		static ScriptedInput idleAfter(byte[] script) {
			return new ScriptedInput(script, new CountDownLatch(1), false);
		}

		/** After the script read() waits for the release latch, then throws IOException. */
		static ScriptedInput failingAfter(byte[] script, CountDownLatch release) {
			return new ScriptedInput(script, release, true);
		}

		int unread() { return this.script.length - this.position; }

		@Override public int read() throws IOException {
			if (this.position < this.script.length) return this.script[this.position++] & 0xff;
			try { this.release.await(); }
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException();
			}
			if (this.failAtEnd) throw new IOException("stdin failed");
			return -1;
		}

		@Override public void close() { this.release.countDown(); }
	}

	/** A subscription to actual flushed frames, not timing or file polling. */
	static final class Output extends PrintStream {
		final ByteArrayOutputStream bytes;
		final JsonMapper json = JsonMapper.builder().build();
		final List<JsonNode> frames = new CopyOnWriteArrayList<>();
		final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
		final List<JsonNode> unmatched = new ArrayList<>();
		Output() { this(new ByteArrayOutputStream()); }
		private Output(ByteArrayOutputStream bytes) {
			super(bytes, true, StandardCharsets.UTF_8); this.bytes = bytes;
		}
		@Override public synchronized void println(String line) {
			super.println(line);
			JsonNode frame = this.json.readTree(line);
			this.frames.add(frame);
			this.events.add(frame);
		}
		String text() { return this.bytes.toString(StandardCharsets.UTF_8); }
		List<JsonNode> ofType(String type) {
			return this.frames.stream().filter(frame -> frame.path("type").asString().equals(type)).toList();
		}
		List<String> errorCodes() {
			return ofType("error").stream().map(frame -> frame.path("code").asString()).toList();
		}
		JsonNode await(String type, String id) {
			return awaitFrame(frame -> frame.path("type").asString().equals(type)
					&& frame.path("requestId").asString("").equals(id));
		}
		JsonNode awaitEvent(String event) {
			return awaitFrame(frame -> frame.path("event").asString("").equals(event));
		}
		private JsonNode awaitFrame(Predicate<JsonNode> predicate) {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			try {
				var iterator = this.unmatched.iterator();
				while (iterator.hasNext()) {
					JsonNode frame = iterator.next();
					if (predicate.test(frame)) { iterator.remove(); return frame; }
				}
				while (true) {
					JsonNode frame = this.events.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
					assertThat(frame).as("expected frame before deadline").isNotNull();
					if (predicate.test(frame)) return frame;
					this.unmatched.add(frame);
				}
			}
			catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
		}
	}
}
