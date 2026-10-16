package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.change.ChangeSet;
import com.example.miniagent.core.change.ChangeStatus;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.delegation.runtime.ApplyStagedTool;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunEvent;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.core.run.RunStore;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The model-facing apply_staged tool, driven by a model that reads the delegate result and asks for publication. */
@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/b16-script", "agent.plugins.memory.enabled=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=write_file,delegate,apply_staged" })
@Import(ApplyStagedToolTest.Fixture.class)
class ApplyStagedToolTest {
	static final JsonMapper JSON = JsonMapper.builder().build();
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired RunExecutor executor;
	@Autowired RunStore runs;
	@Autowired ChangeStore changes;
	@Autowired ApprovalBroker approvals;
	@Autowired ChangeCommandsE2ETest.Screen screen;
	@Autowired ScriptedModel model;
	@Autowired ApplyStagedTool applyStaged;
	private ExecutorService worker;
	private Path file;

	@BeforeEach void freshSession() throws Exception {
		this.worker = Executors.newSingleThreadExecutor();
		this.screen.setInteractive(false);
		this.screen.questions.clear();
		this.screen.lines.clear();
		this.model.contexts.clear();
		this.model.applyAfterDelegate = true;
		this.session.startNew();
		this.file = workspace.resolve("staged-" + UUID.randomUUID() + ".txt");
		Files.writeString(this.file, "before");
	}
	@AfterEach void cleanup() throws Exception {
		for (var request : this.approvals.pending()) this.approvals.cancel(request);
		this.worker.shutdownNow();
		assertThat(this.worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		assertThat(this.approvals.pending()).isEmpty();
	}

	private String delegateGoal() {
		String write = "!tool write_file " + JSON.writeValueAsString(Map.of("path", this.file.getFileName().toString(),
				"content", "after"));
		return "!tool delegate " + JSON.writeValueAsString(Map.of("planId", "publish", "children", List.of(Map.of(
				"childId", "implement", "goal", write, "role", "IMPLEMENT", "tools", List.of("write_file")))));
	}
	private Future<RunExecutor.Result> start() {
		return this.worker.submit(() -> this.executor.start(delegateGoal(), this.session.id(),
				this.agent.selectedModel(), workspace, RunBudget.defaults()));
	}
	private ChangeSet proposal() throws Exception {
		return this.changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
				.filter(set -> set.diffs().stream().anyMatch(diff -> diff.relativePath().equals(this.file.getFileName().toString())))
				.findFirst().orElseThrow();
	}
	private List<String> toolNames(RunId run) throws Exception {
		return FileRunStore.events(this.runs, run).stream().filter(RunEvent.ToolStarted.class::isInstance)
				.map(RunEvent.ToolStarted.class::cast).map(RunEvent.ToolStarted::toolName).toList();
	}

	@Test void unattendedRequestReportsPendingApprovalAndPublishesNothing() throws Exception {
		var result = start().get(30, TimeUnit.SECONDS);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(result.reply().text()).isEqualTo("PENDING_APPROVAL");
		assertThat(toolNames(result.record().spec().runId())).containsExactly("delegate", "apply_staged");
		assertThat(Files.readString(this.file)).isEqualTo("before");
		assertThat(this.approvals.pending()).hasSize(1);
		assertThat(this.changes.read().get(proposal().changeSetId()).changeSet().status())
				.isEqualTo(ChangeStatus.PENDING_APPROVAL);
	}

	@Test void approvalFromTheConsolePublishesAndTheToolReportsApplied() throws Exception {
		this.screen.setInteractive(true);
		var run = start();
		var question = this.screen.questions.poll(10, TimeUnit.SECONDS);
		assertThat(question).isNotNull();
		// The approval question names the proposal; the operator then confirms exclusive ownership of it.
		UUID proposalId = UUID.fromString(question.toolCallKey().substring("apply-".length()));
		var staged = this.changes.read().get(proposalId).changeSet();
		assertThat(staged).isEqualTo(proposal());
		assertThat(question.diffDigest()).isEqualTo(staged.digest());
		assertThat(Files.readString(this.file)).isEqualTo("before");
		this.screen.lines.add("exclusive " + proposalId + " " + staged.digest());
		assertThat(this.approvals.respond(question, ApprovalDecision.APPROVED)).isTrue();
		var result = run.get(30, TimeUnit.SECONDS);
		assertThat(result.reply().text()).isEqualTo("APPLIED");
		assertThat(Files.readString(this.file)).isEqualTo("after");
		assertThat(this.changes.read().get(proposalId).changeSet().status()).isEqualTo(ChangeStatus.APPLIED);
	}

	@Test void aChildRunContextCannotInvokeTheTool() throws Exception {
		this.model.applyAfterDelegate = false;
		var result = start().get(30, TimeUnit.SECONDS);
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		var child = this.model.contexts.values().stream().filter(context -> context.spec().childId() != null)
				.findFirst().orElseThrow();
		var staged = proposal();
		var runsBefore = this.runs.list();
		String input = JSON.writeValueAsString(Map.of("planId", "publish", "childId", "implement",
				"proposalId", staged.changeSetId().toString()));
		var asChild = new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, child,
				ToolContextKeys.TURN_CONTEXT, child.turnContext()));
		assertThatThrownBy(() -> this.applyStaged.call(input, asChild))
				.isInstanceOf(IllegalStateException.class).hasMessage("Parent-only operation");
		assertThat(this.runs.list()).isEqualTo(runsBefore);
		assertThat(this.approvals.pending()).isEmpty();
		assertThat(Files.readString(this.file)).isEqualTo("before");
		assertThat(this.changes.read().get(staged.changeSetId()).changeSet().status())
				.isEqualTo(ChangeStatus.PENDING_APPROVAL);
	}

	@Test void callsWithoutABoundParentAreRefused() {
		assertThatThrownBy(() -> this.applyStaged.call("{}"))
				.isInstanceOf(IllegalStateException.class).hasMessage("Bound parent execution required");
		assertThatThrownBy(() -> this.applyStaged.call("{}", new ToolContext(Map.of())))
				.isInstanceOf(IllegalStateException.class).hasMessage("Managed parent required");
	}

	/** Plays the delegate tool, then asks to publish the proposal named in its result, and answers with the raw verdict. */
	static final class ScriptedModel implements ChatModel {
		private final ScriptedChatModel scripted = new ScriptedChatModel();
		final Map<RunId, RunContext> contexts = new ConcurrentHashMap<>();
		volatile boolean applyAfterDelegate;
		@Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
		@Override public ChatResponse call(Prompt prompt) {
			RunContext.current().ifPresent(context -> this.contexts.putIfAbsent(context.spec().runId(), context));
			if (prompt.getInstructions().getLast() instanceof ToolResponseMessage tools) {
				var response = tools.getResponses().getFirst();
				if (response.name().equals("delegate") && this.applyAfterDelegate)
					return reply(applyCall(response.responseData()), "TOOL_CALLS");
				if (response.name().equals("apply_staged"))
					return reply(new AssistantMessage(response.responseData()), "STOP");
			}
			return this.scripted.call(prompt);
		}
		@Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
		private static AssistantMessage applyCall(String delegateResult) {
			var outcome = JSON.readTree(delegateResult);
			var proposal = outcome.findValue("changeSetId");
			if (proposal == null) throw new IllegalStateException("delegate returned no proposal: " + delegateResult);
			String arguments = JSON.writeValueAsString(Map.of("planId", outcome.path("planId").asString(),
					"childId", outcome.path("children").path(0).path("childId").asString(),
					"proposalId", proposal.asString()));
			return AssistantMessage.builder().content("").toolCalls(List.of(
					new AssistantMessage.ToolCall("call_apply", "function", "apply_staged", arguments))).build();
		}
		private static ChatResponse reply(AssistantMessage output, String finishReason) {
			return new ChatResponse(List.of(new Generation(output,
					ChatGenerationMetadata.builder().finishReason(finishReason).build())),
					ChatResponseMetadata.builder().model("b16-script").usage(new DefaultUsage(1, 1)).build());
		}
	}
	@TestConfiguration static class Fixture {
		@Bean ChangeCommandsE2ETest.Screen agentConsole() { return new ChangeCommandsE2ETest.Screen(); }
		@Bean ScriptedModel scriptedModel() { return new ScriptedModel(); }
		@Bean ModelProvider fakeModelProvider(ScriptedModel model) {
			return new ModelProvider() {
				public String providerId() { return "fake"; }
				public ProviderAuth auth() { return () -> true; }
				public List<ModelEntry> models() { return List.of(new ModelEntry(new ModelRef("fake", "b16-script"), "fake", null)); }
				public ChatModel create(ModelRef ref, Map<String, String> options) { return model; }
			};
		}
	}
}
