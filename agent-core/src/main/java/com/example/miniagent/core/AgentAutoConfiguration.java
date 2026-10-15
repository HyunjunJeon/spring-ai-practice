package com.example.miniagent.core;

import java.time.LocalDate;
import java.time.Clock;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.model.FileSessionModelStore;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelRegistry;
import com.example.miniagent.core.model.ModelSelection;
import com.example.miniagent.core.model.SessionModelStore;
import com.example.miniagent.core.run.RunBudgetInterceptor;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ConsoleApprovalBroker;
import com.example.miniagent.core.model.ModelCommand;
import com.example.miniagent.core.model.ModelsCommand;
import com.example.miniagent.core.change.ChangeProperties;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.UndoEngine;
import com.example.miniagent.core.change.RecoveryScanner;
import com.example.miniagent.core.change.PathPolicy;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 코어 자동 구성: 플러그인이 등록한 빈들을 모아 {@link Agent} 하나를 조립한다.
 *
 * <p>플러그인과 코어는 서로를 모른다. 플러그인은 Spring AI 타입(ToolCallbackProvider, Advisor)이나
 * 코어 SPI 타입(ToolInterceptor, SystemPromptContributor, SlashCommand)의 빈을 등록할 뿐이고,
 * 코어는 {@link ObjectProvider} 로 "있는 만큼" 가져온다. 이것이 Spring Boot 방식의 플러그인이다.
 */
@AutoConfiguration(afterName = "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration")
@EnableConfigurationProperties({ AgentProperties.class, ChangeProperties.class,
		com.example.miniagent.core.run.RunProperties.class,
		com.example.miniagent.core.delegation.DelegationProperties.class })
public class AgentAutoConfiguration {

	@Bean
	@Lazy
	@ConditionalOnMissingBean(ChangeStore.class)
	ChangeStore changeStore(AgentProperties properties, ChangeProperties limits) throws IOException {
		return new FileChangeStore(properties.getHome(), properties.getCwd(), limits);
	}

	@Bean
	@Lazy
	@ConditionalOnMissingBean(VerificationRunner.class)
	VerificationRunner verificationRunner(ChangeStore store, ObjectProvider<VerificationRunner.LaunchPolicy> policies,
			ObjectProvider<Clock> clocks) {
		return new VerificationRunner(store, policies.getIfAvailable(() -> (context, argv) -> {
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
		}), clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@Lazy
	@ConditionalOnMissingBean(ApplyEngine.class)
	ApplyEngine applyEngine(ChangeStore store, AgentProperties properties, ObjectProvider<Clock> clocks)
			throws IOException {
		return new ApplyEngine(store, new PathPolicy(store.workspace(), properties.getHome(), List.of()),
				clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@Lazy
	@ConditionalOnMissingBean(UndoEngine.class)
	UndoEngine undoEngine(ChangeStore store, AgentProperties properties, ObjectProvider<Clock> clocks)
			throws IOException {
		return new UndoEngine(store, new PathPolicy(store.workspace(), properties.getHome(), List.of()),
				clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@Lazy
	@ConditionalOnMissingBean(RecoveryScanner.class)
	RecoveryScanner recoveryScanner(ChangeStore store, AgentProperties properties) throws IOException {
		return new RecoveryScanner(store, new PathPolicy(store.workspace(), properties.getHome(), List.of()));
	}

	@Bean
	RunBudgetInterceptor runBudgetInterceptor() {
		return new RunBudgetInterceptor();
	}

	@Bean
	com.example.miniagent.core.change.StagingToolInterceptor stagingToolInterceptor(
			ObjectProvider<com.example.miniagent.core.change.StagingToolInterceptor.Policy> policies) {
		return new com.example.miniagent.core.change.StagingToolInterceptor(() -> policies.orderedStream().toList());
	}

	private static final String TOOL_CALLING_ADVISOR_ORDER = "spring.ai.chat.client.tool-calling.advisor-order";

	@Bean
	@ConditionalOnMissingBean
	AgentConsole agentConsole() {
		return AgentConsole.system();
	}

	@Bean
	@ConditionalOnMissingBean(ApprovalBroker.class)
	ApprovalBroker approvalBroker(AgentConsole console) {
		return new ConsoleApprovalBroker(console);
	}

	@Bean
	@ConditionalOnMissingBean
	AgentSession agentSession() {
		return new AgentSession();
	}

	/** 작업 디렉터리와 날짜. 코어가 기본으로 주는 유일한 프롬프트 섹션이다. */
	@Bean
	SystemPromptContributor environmentPromptContributor(AgentProperties properties) {
		return new SystemPromptContributor() {

			@Override
			public String contribute() {
				return """
						# Environment
						- Working directory: %s
						- OS: %s
						- Today: %s""".formatted(
							properties.getCwd(), 
							System.getProperty("os.name"), 
							LocalDate.now()
						);
			}

			@Override
			public int getOrder() {
				return Ordered.HIGHEST_PRECEDENCE;
			}

		};
	}

	@Bean
	@ConditionalOnMissingBean(SessionModelStore.class)
	SessionModelStore sessionModelStore(AgentProperties properties) throws IOException {
		return new FileSessionModelStore(properties.getHome());
	}

	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty(
			name = "agent.project-instructions.enabled", matchIfMissing = true)
	ProjectInstructions projectInstructions(AgentProperties properties) throws IOException {
		return new ProjectInstructions(properties.getCwd());
	}

	@Bean
	SlashCommand contextCommand(ObjectProvider<ProjectInstructions> instructions) {
		return SlashCommand.of("context", "시작 시 읽은 프로젝트 지침 파일", (args, context) -> {
			ProjectInstructions snapshot = instructions.getIfAvailable();
			if (snapshot == null || snapshot.sources().isEmpty()) {
				context.console().println("프로젝트 지침 없음");
			}
			else {
				snapshot.sources().forEach(source -> context.console().println(source.path().toString()));
			}
		});
	}

	@Bean
	@ConditionalOnMissingBean
	Agent agent(ObjectProvider<ChatClient.Builder> chatClientBuilder, AgentProperties properties, AgentSession session,
			Environment environment, ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
			ObjectProvider<ToolCallback> toolCallbacks, ObjectProvider<Advisor> advisors,
			ObjectProvider<SystemPromptContributor> promptContributors,
			ObjectProvider<ToolInterceptor> toolInterceptors, ObjectProvider<AgentPlugin> plugins,
			ObjectProvider<ModelProvider> modelProviders, ObjectProvider<ObservationRegistry> observations,
			ObjectProvider<ChatClientObservationConvention> clientConvention,
			ObjectProvider<AdvisorObservationConvention> advisorConvention,
			ObjectProvider<ToolCallingManager> toolManager,
			ObjectProvider<ToolExecutionEligibilityChecker> eligibility,
			ObjectProvider<ChatClientCustomizer> clientCustomizers,
			ObjectProvider<ChatClientBuilderCustomizer> builderCustomizers,
			ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilders, ApplicationEventPublisher events,
			SessionModelStore selections) throws IOException {

		// 1) 도구: 모든 도구를 ToolInterceptor 체인으로 감싼다.
		List<ToolInterceptor> interceptors = toolInterceptors.orderedStream().toList();
		List<ToolCallback> callbacks = toolCallbacks.orderedStream()
			.map(callback -> InterceptingToolCallback.wrap(callback, interceptors))
			.toList();
		List<ToolCallbackProvider> providers = toolCallbackProviders.orderedStream()
			.map(provider -> (ToolCallbackProvider) new InterceptingToolCallbackProvider(provider, interceptors))
			.toList();

		// 2) 시스템 프롬프트: 기본 프롬프트 + 플러그인 섹션들(order 순).
		List<String> sections = new ArrayList<>();
		sections.add(properties.getSystemPrompt());
		promptContributors.orderedStream()
			.map(SystemPromptContributor::contribute)
			.filter(StringUtils::hasText)
			.forEach(sections::add);
		String systemPrompt = String.join("\n\n", sections);

		// 3) 어드바이저: order 순으로 정렬된다. ToolCallingAdvisor(+300)는 ChatClient 가 build() 때 자동으로 끼운다.
		List<Advisor> advisorList = advisors.orderedStream().toList();

		int toolCallingAdvisorOrder = environment.getProperty(TOOL_CALLING_ADVISOR_ORDER, Integer.class,
				ToolCallingAdvisor.DEFAULT_ORDER);
		// 도구 검색을 켜면 스타터가 ToolCallingAdvisor.Builder 빈을 하나 만든다. ToolAdvisor 는 클라이언트당 하나다.
		ToolCallingAdvisor.Builder<?> toolAdvisor = toolAdvisor(toolAdvisorBuilders, toolManager, eligibility,
				toolCallingAdvisorOrder);
		String toolAdvisorName = toolAdvisor.copy().build().getName();
		List<ModelProvider> contributedModels = modelProviders.orderedStream().toList();
		if (!contributedModels.isEmpty()) {
			ModelRegistry registry = new ModelRegistry(contributedModels, model -> {
				ChatClient.Builder configured = ChatClient.builder(model,
						observations.getIfAvailable(() -> ObservationRegistry.NOOP),
						clientConvention.getIfAvailable(), advisorConvention.getIfAvailable(), toolAdvisor.copy());
				clientCustomizers.orderedStream().forEach(customizer -> customizer.customize(configured));
				builderCustomizers.orderedStream().forEach(customizer -> customizer.customize(configured));
				configured.defaultSystem(systemPrompt);
				if (!callbacks.isEmpty()) {
					configured.defaultToolCallbacks(callbacks);
				}
				if (!providers.isEmpty()) {
					configured.defaultToolCallbacks(providers.toArray(ToolCallbackProvider[]::new));
				}
				if (!advisorList.isEmpty()) {
					configured.defaultAdvisors(advisorList);
				}
				return configured.build();
			});
			String configuredRef = environment.getProperty("agent.models.default");
			ModelRef defaultRef;
			if (StringUtils.hasText(configuredRef)) {
				String[] parts = configuredRef.split("/", 2);
				if (parts.length != 2) {
					throw new IllegalArgumentException("agent.models.default must be provider/model");
				}
				defaultRef = new ModelRef(parts[0], parts[1]);
			}
			else {
				String providerId = environment.getProperty("spring.ai.model.chat", "fake");
				if (environment.matchesProfiles("zai")) {
					providerId = "zai";
				}
				String selectedProvider = providerId;
				defaultRef = registry.models().stream()
					.filter(entry -> entry.ref().provider().equals(selectedProvider))
					.findFirst().orElseThrow(() -> new IllegalArgumentException(
							"No default model for provider " + selectedProvider)).ref();
			}
			return new Agent(registry, selections, new ModelSelection(defaultRef),
					session, properties.getCwd(), systemPrompt, callbacks, providers, advisorList,
					plugins.orderedStream().toList(), toolCallingAdvisorOrder, events::publishEvent, toolAdvisorName);
		}

		// Spring AI 2.0.1: "요청에 붙인 도구만 실행된다"(resolution fallback 기본 off).
		// 그래서 플러그인 도구는 반드시 여기서 defaultToolCallbacks 로 붙여야 한다.
		ChatClient.Builder builder = chatClientBuilder.getObject().defaultSystem(systemPrompt);
		if (!callbacks.isEmpty()) {
			builder.defaultToolCallbacks(callbacks);
		}
		if (!providers.isEmpty()) {
			builder.defaultToolCallbacks(providers.toArray(ToolCallbackProvider[]::new));
		}
		if (!advisorList.isEmpty()) {
			builder.defaultAdvisors(advisorList);
		}

		return new Agent(builder.build(), session, properties.getCwd(), systemPrompt, callbacks, providers,
				advisorList, plugins.orderedStream().toList(), toolCallingAdvisorOrder);
	}

	@Bean
	@ConditionalOnMissingBean
	AgentInputHandler agentInputHandler(Agent agent, AgentSession session, AgentConsole console,
			ObjectProvider<SlashCommand> commands) {
		return new AgentInputHandler(agent, session, console, commands.orderedStream().toList());
	}

	@Bean
	InputQueue inputQueue(AgentInputHandler handler) {
		return handler.inputQueue();
	}

	@Bean
	@ConditionalOnMissingBean
	@org.springframework.context.annotation.Lazy
	com.example.miniagent.core.delegation.StagedApplyService stagedApplyService(
			ObjectProvider<com.example.miniagent.core.run.RunStore> runs,
			ObjectProvider<com.example.miniagent.core.change.ChangeStore> changes,
			com.example.miniagent.core.delegation.ChildScopeFactory scopes,
			ObjectProvider<com.example.miniagent.core.change.command.ApplyCommand> apply) {
		return new com.example.miniagent.core.delegation.StagedApplyService(
				runs.getObject(), changes.getObject(), scopes, apply.getObject());
	}

	@Bean
	@ConditionalOnMissingBean
	@org.springframework.context.annotation.Lazy
	com.example.miniagent.core.delegation.DelegationRecovery delegationRecovery(
			ObjectProvider<com.example.miniagent.core.run.RunStore> runs) {
		return new com.example.miniagent.core.delegation.DelegationRecovery(runs.getObject());
	}

	@Bean
	@ConditionalOnMissingBean
	com.example.miniagent.core.delegation.SessionRunLock sessionRunLock() {
		return new com.example.miniagent.core.delegation.SessionRunLock();
	}

	@Bean
	@ConditionalOnMissingBean
	@org.springframework.context.annotation.Lazy
	com.example.miniagent.core.delegation.DefaultDelegationRuntime delegationRuntime(
			com.example.miniagent.core.delegation.ChildScopeFactory factory,
			com.example.miniagent.core.delegation.DelegationProperties properties,
			com.example.miniagent.core.delegation.SessionRunLock sessions,
			com.example.miniagent.core.delegation.DelegationEvidenceCollector evidence) {
		var runtime = new com.example.miniagent.core.delegation.DefaultDelegationRuntime(factory, properties, sessions);
		runtime.evidence(evidence);
		return runtime;
	}

	@Bean @Lazy
	com.example.miniagent.core.delegation.DelegationEvidenceCollector delegationEvidenceCollector(
			com.example.miniagent.core.run.RunStore runs, com.example.miniagent.core.run.RunRequestStore requests,
			ChangeStore changes) {
		return new com.example.miniagent.core.delegation.DelegationEvidenceCollector(runs, requests.evidence(), changes);
	}

	@Bean @Lazy
	com.example.miniagent.core.delegation.DelegationController delegationController(
			com.example.miniagent.core.delegation.DefaultDelegationRuntime runtime,
			com.example.miniagent.core.run.RunStore runs,
			com.example.miniagent.core.delegation.StagedApplyService apply,
			org.springframework.context.ApplicationEventPublisher events) {
		var controller = new com.example.miniagent.core.delegation.DelegationController(runtime, runs, apply,
				event -> com.example.miniagent.core.delegation.DelegationEvents.publish(event, events, runs));
		controller.observed(events::publishEvent);
		return controller;
	}

	@Bean
	com.example.miniagent.core.delegation.DelegateTool delegateTool(
			ObjectProvider<com.example.miniagent.core.delegation.DelegationController> controller) {
		return new com.example.miniagent.core.delegation.DelegateTool(controller::getObject);
	}

	@Bean
	com.example.miniagent.core.delegation.ApplyStagedTool applyStagedTool(
			ObjectProvider<com.example.miniagent.core.delegation.DelegationController> controller,
			ObjectProvider<Agent> agent, AgentSession session, AgentConsole console) {
		return new com.example.miniagent.core.delegation.ApplyStagedTool(controller::getObject,
				() -> new SlashCommand.CommandContext(agent.getObject(), session, console));
	}

	@Bean
	com.example.miniagent.core.change.StagingToolInterceptor.Policy delegationControlPolicy(
			com.example.miniagent.core.delegation.DelegateTool delegate,
			com.example.miniagent.core.delegation.ApplyStagedTool apply) {
		return () -> java.util.stream.Stream.of(delegate, apply).collect(java.util.stream.Collectors.toMap(
				tool -> tool.getToolDefinition().name(), tool -> new com.example.miniagent.core.change.StagingToolInterceptor.Declaration(
						com.example.miniagent.core.run.SideEffectClass.definitionDigest(tool.getToolDefinition()),
						com.example.miniagent.core.change.StagingToolInterceptor.Kind.CONTROL, List.of())));
	}

	@Bean
	com.example.miniagent.core.run.SideEffectClass.Policy delegationReadPolicy(
			com.example.miniagent.core.delegation.DelegateTool delegate) {
		return () -> java.util.Map.of("delegate",
				com.example.miniagent.core.run.SideEffectClass.definitionDigest(delegate.getToolDefinition()));
	}

	@Bean
	com.example.miniagent.core.delegation.DelegationVerificationTool delegationVerificationTool(
			@Lazy ChangeStore changes, ObjectProvider<VerificationRunner.LaunchPolicy> policies, ObjectProvider<Clock> clocks) {
		return new com.example.miniagent.core.delegation.DelegationVerificationTool(changes,
				policies.getIfAvailable(() -> (context, argv) -> {
					throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
				}), clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	ToolCallback delegationVerificationCallback(com.example.miniagent.core.delegation.DelegationVerificationTool tool) {
		return tool.callback();
	}

	@Bean
	com.example.miniagent.core.change.StagingToolInterceptor.Policy delegationVerificationPolicy(
			com.example.miniagent.core.delegation.DelegationVerificationTool tool) { return tool.stagingPolicy(); }

	@Bean
	com.example.miniagent.core.run.SideEffectClass.Policy delegationVerificationReadPolicy(
			com.example.miniagent.core.delegation.DelegationVerificationTool tool) { return tool.readPolicy(); }

	@Bean
	com.example.miniagent.core.delegation.DelegateCommand delegateCommand(
			ObjectProvider<com.example.miniagent.core.delegation.DelegationController> controller,
			ObjectProvider<com.example.miniagent.core.run.RunExecutor> executor,
			com.example.miniagent.core.run.RunProperties limits, AgentProperties properties) {
		return new com.example.miniagent.core.delegation.DelegateCommand(controller::getObject, executor::getObject,
				limits, properties.getCwd());
	}

	@Bean
	@ConditionalOnMissingBean
	@org.springframework.context.annotation.Lazy
	com.example.miniagent.core.delegation.ChildScopeFactory childScopeFactory(Agent agent,
			AgentProperties properties, ObjectProvider<com.example.miniagent.core.run.RunStore> runs,
			ObjectProvider<com.example.miniagent.core.run.RunRequestStore> requests,
			SessionModelStore selections, ObjectProvider<java.time.Clock> clock,
			ObjectProvider<com.example.miniagent.core.approval.ApprovalBroker> broker,
			ObjectProvider<ToolCallback> callbacks, ObjectProvider<ToolCallbackProvider> providers,
			ObjectProvider<ToolInterceptor> interceptors,
			ObjectProvider<com.example.miniagent.core.change.StagingToolInterceptor.Policy> stagePolicies,
			ObjectProvider<com.example.miniagent.core.run.SideEffectClass.Policy> readPolicies,
			ObjectProvider<com.example.miniagent.core.delegation.ChildScopeFactory.AdvisorContribution> childAdvisors) {
		return new com.example.miniagent.core.delegation.ChildScopeFactory(agent, properties.getHome(),
			runs.getObject(), requests.getObject(), selections, clock.getObject(), broker.getObject(),
			callbacks.orderedStream().toList(), providers.orderedStream().toList(),
			interceptors.orderedStream().toList(), stagePolicies.orderedStream().toList(),
			readPolicies.orderedStream().toList(), childAdvisors.orderedStream().toList());
	}

	// ---- 코어 명령: 지금 에이전트가 "무엇으로 조립되었는지" 들여다보는 도구들 ----

	@Bean
	SlashCommand modelsCommand() {
		return new ModelsCommand();
	}

	@Bean
	SlashCommand modelCommand() {
		return new ModelCommand();
	}

	@Bean
	SlashCommand helpCommand(ObjectProvider<SlashCommand> commands) {
		return SlashCommand.of("help", "명령 목록", (args, ctx) -> {
			commands.orderedStream()
				.sorted((a, b) -> a.name().compareTo(b.name()))
				.forEach(command -> ctx.console().println("  /%-12s %s".formatted(command.name(), command.description())));
			ctx.console().println("  /%-12s %s".formatted("exit", "종료"));
		});
	}

	@Bean
	SlashCommand toolsCommand() {
		return SlashCommand.of("tools", "모델에게 노출된 도구 목록", (args, ctx) -> {
			List<ToolDefinition> tools = ctx.agent().toolDefinitions();
			if (tools.isEmpty()) {
				ctx.console().println("도구가 없습니다. 도구 플러그인이 꺼져 있으면 모델은 대화만 할 수 있습니다.");
				return;
			}
			tools.forEach(tool -> {
				ctx.console().println("- " + tool.name() + ": " + firstLine(tool.description()));
				if ("-v".equals(args.trim())) {
					ctx.console().info("    schema: " + tool.inputSchema().replaceAll("\\s+", " "));
				}
			});
		});
	}

	@Bean
	SlashCommand pluginsCommand() {
		return SlashCommand.of("plugins", "붙어 있는 플러그인 목록", (args, ctx) -> ctx.agent()
			.plugins()
			.forEach(plugin -> ctx.console().println("- %-12s %s".formatted(plugin.id(), plugin.description()))));
	}

	@Bean
	SlashCommand systemCommand() {
		return SlashCommand.of("system", "조립된 시스템 프롬프트 보기", (args, ctx) -> ctx.console().println(ctx.agent().systemPrompt()));
	}

	@Bean
	SlashCommand planCommand() {
		return SlashCommand.of("plan", "목표를 구조화된 계획으로 받기 (/plan <목표>)", (args, ctx) -> {
			if (args.isBlank()) {
				ctx.console().error("사용법: /plan <목표>");
				return;
			}
			try {
				var binding = ctx.agent().modelRegistry().bind(ctx.agent().selectedModel());
				StructuredPlan.Plan plan = StructuredPlan.request(binding.chatModel(), args.strip());
				ctx.console().println(StructuredPlan.render(plan));
			}
			catch (RuntimeException ex) {
				ctx.console().error("계획을 구조화하지 못했습니다: " + ex.getMessage());
			}
		});
	}

	@Bean
	SlashCommand advisorsCommand() {
		return SlashCommand.of("advisors", "어드바이저 체인(실행 순서) 보기", (args, ctx) -> {
			Agent agent = ctx.agent();
			List<String[]> rows = new ArrayList<>();
			agent.advisors().forEach(a -> rows.add(new String[] { order(a.getOrder()), a.getName(), "" }));
			boolean customToolAdvisor = agent.advisors().stream().anyMatch(ToolAdvisor.class::isInstance);
			if (!customToolAdvisor) {
				rows.add(new String[] { order(agent.toolCallingAdvisorOrder()), agent.toolCallingAdvisorName(),
						"<- 도구 루프. 이보다 아래는 루프 안쪽(모델 호출마다 실행)" });
			}
			rows.sort((a, b) -> Integer.compare(parse(a[0]), parse(b[0])));
			rows.add(new String[] { "LOWEST", "ChatModelCallAdvisor", "<- 마지막: 실제 ChatModel.call()" });
			ctx.console().println("요청은 위에서 아래로, 응답은 아래에서 위로 흐른다.");
			rows.forEach(r -> ctx.console().println("  %-14s %-28s %s".formatted(r[0], r[1], r[2])));
		});
	}

	/**
	 * 스타터가 만든 빌더가 있으면 그것을 복사한다. 없으면 기본 {@code ToolCallingAdvisor} 다.
	 * 빌더 원본은 모델마다 {@code copy()} 하므로 한 번만 구성한다.
	 */
	private static ToolCallingAdvisor.Builder<?> toolAdvisor(ObjectProvider<ToolCallingAdvisor.Builder<?>> builders,
			ObjectProvider<ToolCallingManager> toolManager, ObjectProvider<ToolExecutionEligibilityChecker> eligibility,
			int order) {
		ToolCallingAdvisor.Builder<?> configured = builders.getIfAvailable(ToolCallingAdvisor::builder).copy()
			.advisorOrder(order);
		toolManager.ifAvailable(configured::toolCallingManager);
		eligibility.ifAvailable(configured::toolExecutionEligibilityChecker);
		return configured;
	}

	private static String order(int order) {
		long fromHighest = (long) order - Ordered.HIGHEST_PRECEDENCE;
		if (fromHighest < 1_000_000) {
			return "HIGHEST+" + fromHighest;
		}
		long fromLowest = (long) Ordered.LOWEST_PRECEDENCE - order;
		return (fromLowest < 1_000_000) ? "LOWEST-" + fromLowest : String.valueOf(order);
	}

	private static int parse(String order) {
		if (order.startsWith("HIGHEST+")) {
			return Ordered.HIGHEST_PRECEDENCE + Integer.parseInt(order.substring(8));
		}
		if (order.startsWith("LOWEST-")) {
			return Ordered.LOWEST_PRECEDENCE - Integer.parseInt(order.substring(7));
		}
		return Integer.parseInt(order);
	}

	private static String firstLine(String text) {
		return text.lines().findFirst().orElse("").strip();
	}

}
