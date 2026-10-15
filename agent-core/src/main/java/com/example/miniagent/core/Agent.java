package com.example.miniagent.core;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import reactor.core.publisher.Flux;

import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.SessionModelStore;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.model.registry.ModelRegistry;
import com.example.miniagent.core.run.DriftSource;
import com.example.miniagent.core.run.RunContext;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 에이전트 원형. 사용자 턴은 {@code ChatClient} 한 번으로 끝난다.
 *
 * <p>에이전트 루프(모델 호출 → 도구 실행 → 다시 모델 호출 …)는 이 클래스에 없다.
 * Spring AI 2.0 부터 {@code ChatClient} 가 {@code ToolCallingAdvisor} 를 등록하고,
 * 그 어드바이저가 루프를 돈다. 대화 화면은 {@link #stream} 으로 토큰을 받고,
 * 관리 실행과 자식 실행은 {@link #run} 의 {@code call()} 에 남긴다.
 * 2.0.x 는 스트리밍과 도구 호출이 겹치는 경로가 가장 불안정하므로 두 경로의 최종 답을 비교할 수 있게 둔다.
 */
public class Agent implements DriftSource {

	private final @Nullable ChatClient chatClient;

	private final @Nullable ModelRegistry modelRegistry;

	private final @Nullable SessionModelStore modelStore;

	private final @Nullable ModelSelection defaultSelection;

	private final AgentSession session;

	private final Path cwd;

	private final String systemPrompt;

	private final List<ToolCallback> toolCallbacks;

	private final List<ToolCallbackProvider> toolCallbackProviders;

	private final List<Advisor> advisors;

	private final List<AgentPlugin> plugins;

	private final int toolCallingAdvisorOrder;

	private final String toolAdvisorName;

	private Consumer<TraceEvent> events = event -> {};

	private final Map<String, TurnContext> displayedTurns = new ConcurrentHashMap<>();

	private @Nullable TurnRunner turnRunner;

	/** Install only on an independently assembled child Agent before execution. */
	public Agent capturedBy(TurnRunner runner) {
		if (this.turnRunner != null) throw new IllegalStateException("Capture adapter already installed");
		this.turnRunner = java.util.Objects.requireNonNull(runner);
		return this;
	}

	public Agent(ChatClient chatClient, AgentSession session, Path cwd, String systemPrompt,
			List<ToolCallback> toolCallbacks, List<ToolCallbackProvider> toolCallbackProviders, List<Advisor> advisors,
			List<AgentPlugin> plugins, int toolCallingAdvisorOrder) {
		this(chatClient, null, null, null, session, cwd, systemPrompt, toolCallbacks, toolCallbackProviders,
				advisors, plugins, toolCallingAdvisorOrder);
	}

	public Agent(ModelRegistry registry, SessionModelStore store, ModelSelection defaultSelection,
			AgentSession session, Path cwd, String systemPrompt, List<ToolCallback> toolCallbacks,
			List<ToolCallbackProvider> toolCallbackProviders, List<Advisor> advisors, List<AgentPlugin> plugins,
			int toolCallingAdvisorOrder) {
		this(null, registry, store, defaultSelection, session, cwd, systemPrompt, toolCallbacks,
				toolCallbackProviders, advisors, plugins, toolCallingAdvisorOrder);
	}

	public Agent(ModelRegistry registry, SessionModelStore store, ModelSelection defaultSelection,
			AgentSession session, Path cwd, String systemPrompt, List<ToolCallback> toolCallbacks,
			List<ToolCallbackProvider> toolCallbackProviders, List<Advisor> advisors, List<AgentPlugin> plugins,
			int toolCallingAdvisorOrder, Consumer<TraceEvent> events) {
		this(registry, store, defaultSelection, session, cwd, systemPrompt, toolCallbacks, toolCallbackProviders,
				advisors, plugins, toolCallingAdvisorOrder, events, "ToolCallingAdvisor");
	}

	public Agent(ModelRegistry registry, SessionModelStore store, ModelSelection defaultSelection,
			AgentSession session, Path cwd, String systemPrompt, List<ToolCallback> toolCallbacks,
			List<ToolCallbackProvider> toolCallbackProviders, List<Advisor> advisors, List<AgentPlugin> plugins,
			int toolCallingAdvisorOrder, Consumer<TraceEvent> events, String toolAdvisorName) {
		this(null, registry, store, defaultSelection, session, cwd, systemPrompt, toolCallbacks,
				toolCallbackProviders, advisors, plugins, toolCallingAdvisorOrder, toolAdvisorName);
		this.events = java.util.Objects.requireNonNull(events);
	}

	/** Only the console session's captured execution; child sessions never replace it. */
	public Optional<TurnContext> currentTurn() {
		return Optional.ofNullable(this.displayedTurns.get(this.session.id()));
	}

	private Agent(@Nullable ChatClient chatClient, @Nullable ModelRegistry registry,
			@Nullable SessionModelStore store, @Nullable ModelSelection defaultSelection, AgentSession session,
			Path cwd, String systemPrompt, List<ToolCallback> toolCallbacks,
			List<ToolCallbackProvider> toolCallbackProviders, List<Advisor> advisors, List<AgentPlugin> plugins,
			int toolCallingAdvisorOrder) {
		this(chatClient, registry, store, defaultSelection, session, cwd, systemPrompt, toolCallbacks,
				toolCallbackProviders, advisors, plugins, toolCallingAdvisorOrder, "ToolCallingAdvisor");
	}

	private Agent(@Nullable ChatClient chatClient, @Nullable ModelRegistry registry,
			@Nullable SessionModelStore store, @Nullable ModelSelection defaultSelection, AgentSession session,
			Path cwd, String systemPrompt, List<ToolCallback> toolCallbacks,
			List<ToolCallbackProvider> toolCallbackProviders, List<Advisor> advisors, List<AgentPlugin> plugins,
			int toolCallingAdvisorOrder, String toolAdvisorName) {
		this.chatClient = chatClient;
		this.modelRegistry = registry;
		this.modelStore = store;
		this.defaultSelection = defaultSelection;
		this.session = session;
		this.cwd = cwd;
		this.systemPrompt = systemPrompt;
		this.toolCallbacks = List.copyOf(toolCallbacks);
		this.toolCallbackProviders = List.copyOf(toolCallbackProviders);
		this.advisors = List.copyOf(advisors);
		this.plugins = List.copyOf(plugins);
		this.toolCallingAdvisorOrder = toolCallingAdvisorOrder;
		this.toolAdvisorName = java.util.Objects.requireNonNull(toolAdvisorName);
	}

	/** 사용자 입력 한 건을 처리한다. 도구 호출이 몇 번 일어나든 반환은 최종 답변 하나다. */
	public AgentReply run(String input) {
		String sessionId = this.session.id();
		String turnId = UUID.randomUUID().toString();
		if (this.modelRegistry != null) {
			ModelBinding binding = this.modelRegistry.bind(selection(sessionId));
			return run(input, new TurnContext(sessionId, turnId, this.cwd, binding));
		}
		return dispatch(this.chatClient, input, sessionId, turnId, this.cwd, Map.of(), false, delta -> { });
	}

	/**
	 * 대화 화면용 스트리밍 턴. 도구 루프는 {@code ToolCallingAdvisor.adviseStream} 이 돌리고,
	 * 사용자에게는 최종 답의 텍스트 조각만 전달한다. 반환 텍스트는 조각을 이어 붙인 값이다.
	 */
	public AgentReply stream(String input, Consumer<String> delta) {
		String sessionId = this.session.id();
		String turnId = UUID.randomUUID().toString();
		if (this.modelRegistry != null) {
			ModelBinding binding = this.modelRegistry.bind(selection(sessionId));
			return stream(input, new TurnContext(sessionId, turnId, this.cwd, binding), delta);
		}
		return dispatch(this.chatClient, input, sessionId, turnId, this.cwd, Map.of(), true, delta);
	}

	/** Interactive mailbox capability is explicit and never inherited by managed/child calls. */
	public AgentReply streamQueued(String input, Consumer<String> delta, SteeringAdvisor.Scope queue) {
		TurnContext captured = queue.captured();
		if (captured != null && !captured.sessionId().equals(queue.sessionId()))
			throw new IllegalStateException("QUEUE_STALE_TARGET");
		if (captured != null && (captured.runId() != null || captured.attemptId() != null || this.turnRunner != null))
			throw new IllegalStateException("QUEUE_MANAGED_COMMAND");
		if (this.turnRunner != null) throw new IllegalStateException("QUEUE_CHILD_EXECUTION");
		if (this.modelRegistry == null) {
			return dispatch(this.chatClient, input, queue.sessionId(), queue.turnId(), this.cwd,
					Map.of(SteeringAdvisor.CONTEXT_KEY, queue), true, delta);
		}
		TurnContext context = new TurnContext(queue.sessionId(), queue.turnId(), captured == null ? this.cwd : captured.cwd(),
				captured == null ? this.modelRegistry.bind(selection(queue.sessionId())) : captured.binding());
		queue.captured(context);
		return execute(input, context, true, delta, queue);
	}

	/** 캡처한 컨텍스트로 스트리밍한다. 관리 실행은 이 메서드를 쓰지 않는다. */
	public AgentReply stream(String input, TurnContext context, Consumer<String> delta) {
		return execute(input, context, true, delta);
	}

	/** 관리 실행과 자식 실행은 가변 콘솔 세션 대신 캡처한 컨텍스트를 전달한다. */
	public AgentReply run(String input, TurnContext context) {
		return execute(input, context, false, text -> { });
	}

	private AgentReply execute(String input, TurnContext context, boolean streaming, Consumer<String> delta) {
		return execute(input, context, streaming, delta, null);
	}

	private AgentReply execute(String input, TurnContext context, boolean streaming, Consumer<String> delta,
			SteeringAdvisor.@Nullable Scope queue) {
		Map<String, Object> values = new LinkedHashMap<>();
		if (queue != null) values.put(SteeringAdvisor.CONTEXT_KEY, queue);
		values.put(ToolContextKeys.MODEL_PROVIDER, context.binding().ref().provider());
		values.put(ToolContextKeys.MODEL_NAME, context.binding().ref().model());
		values.put(ToolContextKeys.MODEL_BINDING_ID, context.binding().bindingId().toString());
		values.put(ToolContextKeys.TURN_CONTEXT, context);
		com.example.miniagent.core.change.StagedWorkspace.current()
			.filter(stage -> stage.owner().turnId().equals(context.turnId()))
			.ifPresent(stage -> values.put(ToolContextKeys.STAGED_WORKSPACE, stage));
		RunContext.current().filter(run -> run.turnContext().equals(context))
			.ifPresent(run -> values.put(RunContext.TOOL_CONTEXT_KEY, run));
		if (context.runId() != null) {
			values.put(ToolContextKeys.RUN_ID, context.runId());
		}
		if (context.attemptId() != null) {
			values.put(ToolContextKeys.ATTEMPT_ID, context.attemptId());
		}
		if (this.turnRunner != null) values.putAll(this.turnRunner.capture(context));
		boolean displayed = this.displayedTurns.putIfAbsent(context.sessionId(), context) == null;
		try (TurnContext.Scope scope = context.open()) {
			this.events.accept(new TraceEvent.ModelBound(context.sessionId(), context.turnId(),
					context.binding().ref(), context.binding().bindingId(), context.binding().optionsFingerprint(),
					context.runId(), context.attemptId()));
			return dispatch(context.binding().client(), input, context.sessionId(), context.turnId(), context.cwd(),
					values, streaming, delta);
		}
		finally {
			if (displayed) {
				this.displayedTurns.remove(context.sessionId(), context);
			}
		}
	}

	private AgentReply dispatch(@Nullable ChatClient client, String input, String sessionId, String turnId,
			Path cwd, Map<String, Object> captured, boolean streaming, Consumer<String> delta) {
		if (client == null) {
			throw new IllegalStateException("No ChatClient is configured");
		}
		java.util.Objects.requireNonNull(delta);
		Map<String, Object> context = new LinkedHashMap<>(captured);
		context.put(ToolContextKeys.SESSION_ID, sessionId);
		context.put(ToolContextKeys.CWD, cwd.toString());
		context.put(ToolContextKeys.TURN_ID, turnId);
		// Advisor 컨텍스트: 메모리 어드바이저가 이 키로 대화를 찾는다. 없으면 IllegalArgumentException.
		// ToolContext: 모델에게는 안 보이고 도구 구현에만 전달된다. 턴 ID 는 파일 체크포인트(/undo)가 변경을 묶는 단위다.
		var spec = client.prompt()
			.user(input)
			.advisors(advisor -> {
				advisor.param(ChatMemory.CONVERSATION_ID, sessionId);
				context.forEach(advisor::param);
			})
			.toolContext(context);
		if (captured.containsKey(SteeringAdvisor.CONTEXT_KEY)) spec.advisors(new SteeringAdvisor());
		if (!streaming) {
			return reply(spec.call().chatClientResponse());
		}
		StringBuilder text = new StringBuilder();
		AtomicReference<Usage> usage = new AtomicReference<>();
		Flux<ChatClientResponse> chunks = spec.stream().chatClientResponse();
		chunks.doOnNext(response -> {
			String part = textOf(response);
			if (!part.isEmpty()) {
				text.append(part);
				delta.accept(part);
			}
			Usage round = usageOf(response);
			if (round != null) {
				usage.set(round);
			}
		}).blockLast();
		return new AgentReply(text.toString(), usage.get());
	}

	private static AgentReply reply(ChatClientResponse response) {
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null) {
			return new AgentReply("", null);
		}
		Generation result = chatResponse.getResult();
		String text = (result != null) ? result.getOutput().getText() : null;
		// Spring AI 2.0.1 부터 ToolCallingAdvisor 가 루프 전체의 토큰 사용량을 합산해 마지막 응답에 넣어 준다.
		return new AgentReply((text != null) ? text : "", chatResponse.getMetadata().getUsage());
	}

	private static String textOf(ChatClientResponse response) {
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null || chatResponse.getResult() == null) {
			return "";
		}
		String text = chatResponse.getResult().getOutput().getText();
		return (text != null) ? text : "";
	}

	private static @Nullable Usage usageOf(ChatClientResponse response) {
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null || chatResponse.getMetadata() == null) {
			return null;
		}
		return chatResponse.getMetadata().getUsage();
	}

	/** 지금 모델에게 노출되는 도구 목록. Provider 는 매번 다시 펼친다(MCP 처럼 바뀔 수 있으므로). */
	public List<ToolDefinition> toolDefinitions() {
		List<ToolDefinition> definitions = new ArrayList<>();
		this.toolCallbacks.forEach(callback -> definitions.add(callback.getToolDefinition()));
		this.toolCallbackProviders.forEach(provider -> Arrays.stream(provider.getToolCallbacks())
			.forEach(callback -> definitions.add(callback.getToolDefinition())));
		return definitions;
	}

	public ChatClient chatClient() {
		if (this.modelRegistry != null) {
			return this.modelRegistry.bind(selectedModel()).client();
		}
		if (this.chatClient == null) {
			throw new IllegalStateException("No ChatClient is configured");
		}
		return this.chatClient;
	}

	public ModelSelection selectedModel() {
		return selection(this.session.id());
	}

	public ModelRegistry modelRegistry() {
		if (this.modelRegistry == null) {
			throw new IllegalStateException("Runtime model selection is not configured");
		}
		return this.modelRegistry;
	}

	private ModelSelection selection(String sessionId) {
		if (this.modelStore == null || this.defaultSelection == null) {
			throw new IllegalStateException("Runtime model selection is not configured");
		}
		try {
			return this.modelStore.load(sessionId).orElse(this.defaultSelection);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot load model selection for " + sessionId, ex);
		}
	}

	public void selectModel(ModelSelection selection) {
		if (this.modelRegistry == null || this.modelStore == null) {
			throw new IllegalStateException("Runtime model selection is not configured");
		}
		this.modelRegistry.bind(selection);
		try {
			this.modelStore.save(this.session.id(), selection);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot save model selection", ex);
		}
	}

	public String systemPrompt() {
		return this.systemPrompt;
	}

	public List<Advisor> advisors() {
		return this.advisors;
	}

	public List<AgentPlugin> plugins() {
		return this.plugins;
	}

	public int toolCallingAdvisorOrder() {
		return this.toolCallingAdvisorOrder;
	}

	/** {@code /advisors} 에 보이는 도구 루프 이름. 도구 검색을 켜면 {@code ToolSearchToolCallingAdvisor} 다. */
	public String toolCallingAdvisorName() {
		return this.toolAdvisorName;
	}

	public record AgentReply(String text, @Nullable Usage usage) {}

}
