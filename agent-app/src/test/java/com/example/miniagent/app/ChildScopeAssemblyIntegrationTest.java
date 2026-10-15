package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.AgentSession;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.ChildStatus;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.DelegationRecovery;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.delegation.runtime.ChildScopeFactory;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.model.ModelEntry;
import com.example.miniagent.core.model.ModelProvider;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ProviderAuth;
import com.example.miniagent.core.model.SessionModelStore;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunState;
import com.example.miniagent.plugin.fakemodel.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.models.default=fake/child-scope", "agent.plugins.memory.enabled=true",
		"agent.plugins.memory.compaction=false", "agent.plugins.memory.inside-loop=false",
		"agent.plugins.trace.level=off", "agent.plugins.permission.auto-allow-tools=write_file",
		"agent.plugins.permission.allow-non-interactive=true" })
@Import(ChildScopeAssemblyIntegrationTest.Fixture.class)
class ChildScopeAssemblyIntegrationTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry properties) {
		properties.add("agent.cwd", () -> workspace.toString());
		properties.add("agent.home", () -> state.resolve("home").toString());
	}
	@Autowired Agent agent;
	@Autowired AgentSession session;
	@Autowired ChildScopeFactory scopes;
	@Autowired ChatMemoryRepository memory;
	@Autowired ChangeStore changes;
	@Autowired SessionModelStore selections;
	@Autowired DefaultDelegationRuntime runtime;
	@Autowired DelegationRecovery recovery;

	private ToolScope authority(Set<String> names, boolean mutation, boolean delegate) {
		return new ToolScope(names, Set.of(workspace.toAbsolutePath().normalize()),
				mutation ? Set.of(workspace.toAbsolutePath().normalize()) : Set.of(),
				Set.of(), mutation, false, delegate, false);
	}

	@Test void springRuntimeExecutesIndependentChildrenWithoutClaimingVerification() throws Exception {
		var parent = new TurnContext(this.session.id(), UUID.randomUUID().toString(), workspace,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		var childScope = authority(Set.of(), false, false);
		var budget = new RunBudget(Duration.ofMinutes(1), null, 5);
		var plan = new DelegationPlan("spring-runtime", List.of(
				new ChildSpec("a", "child-a", ChildRole.RESEARCH, null, Set.of(), childScope, budget, 1, true),
				new ChildSpec("b", "child-b", ChildRole.RESEARCH, null, Set.of(), childScope, budget, 1, true)), 1);
		var result = this.runtime.execute(parent, plan, authority(Set.of(), false, true),
				RunBudget.defaults(), com.example.miniagent.core.run.RunUsage::empty, "revision", "config");
		assertThat(result.children()).extracting(child -> child.childId()).containsExactly("a", "b");
		assertThat(result.children()).extracting(child -> child.status()).containsOnly(ChildStatus.SUCCEEDED);
		assertThat(result.children()).allMatch(child -> !child.verifiedSuccess("revision", "config"));
		assertThat(this.runtime.executions()).isEmpty();
		assertThat(this.session.id()).isEqualTo(parent.sessionId());
	}

	@Test void realChildKeepsParentMemorySelectionAndDisplayUnchanged() throws Exception {
		var parent = new TurnContext(this.session.id(), UUID.randomUUID().toString(), workspace,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		this.agent.run("parent-marker-" + UUID.randomUUID(), parent);
		var parentDisplay = this.agent.currentTurn();
		var parentMemory = this.memory.findByConversationId(parent.sessionId());
		var parentSelection = this.selections.load(parent.sessionId());
		var spec = new ChildSpec("research", "child-marker", ChildRole.RESEARCH, null,
				Set.of(), authority(Set.of(), false, false),
				new RunBudget(Duration.ofMinutes(1), null, 5), 1, true);
		try (var scope = this.scopes.create(parent, spec, authority(Set.of(), false, true))) {
			var result = scope.call();
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			var stored = this.recovery.storedMetadata(result.record().spec().runId());
			assertThat(stored.runId()).isEqualTo(result.record().spec().runId());
			var storedSpec = tools.jackson.databind.json.JsonMapper.builder().build().readTree(stored.specJson());
			assertThat(storedSpec.get("childId").asString()).isEqualTo("research");
			assertThat(storedSpec.get("parentConversationId").asString()).isEqualTo(parent.sessionId());
			var child = scope.context();
			assertThat(child.sessionId()).isNotEqualTo(parent.sessionId());
			assertThat(child.binding().bindingId()).isNotEqualTo(parent.binding().bindingId());
			assertThat(child.binding().ref()).isEqualTo(parent.binding().ref());
			assertThat(this.memory.findByConversationId(child.sessionId())).isNotEmpty()
					.noneMatch(message -> message.getText() != null && message.getText().contains("parent-marker-"));
			assertThat(this.memory.findByConversationId(parent.sessionId())).isEqualTo(parentMemory);
			assertThat(this.selections.load(parent.sessionId())).isEqualTo(parentSelection);
			assertThat(this.agent.currentTurn()).isEqualTo(parentDisplay);
			assertThat(this.session.id()).isEqualTo(parent.sessionId());
		}
	}

	@Test void realImplementCallbackWritesOnlyItsOwnerStage() throws Exception {
		var file = workspace.resolve("child-" + UUID.randomUUID() + ".txt");
		Files.writeString(file, "before");
		var parent = new TurnContext(this.session.id(), UUID.randomUUID().toString(), workspace,
				this.agent.modelRegistry().bind(this.agent.selectedModel()));
		this.agent.run("parent-ready", parent);
		var parentDisplay = this.agent.currentTurn();
		var spec = new ChildSpec("implement", "!tool write_file {\"path\":\"" + file.getFileName()
				+ "\",\"content\":\"after\"}", ChildRole.IMPLEMENT, null, Set.of(),
				authority(Set.of("write_file"), true, false),
				new RunBudget(Duration.ofMinutes(1), null, 5), 1, true);
		try (var scope = this.scopes.create(parent, spec, authority(Set.of("write_file"), true, true))) {
			var result = scope.call();
			assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
			assertThat(Files.readString(file)).isEqualTo("before");
			var proposed = this.changes.read().changes().stream().map(snapshot -> snapshot.changeSet())
					.filter(set -> set.owner().runId().value().equals(scope.context().runId())).toList();
			assertThat(proposed).hasSize(1);
			assertThat(proposed.getFirst().diffs()).hasSize(1);
			assertThat(this.agent.currentTurn()).isEqualTo(parentDisplay);
		}
	}

	@TestConfiguration static class Fixture {
		@Bean ModelProvider fakeModelProvider() {
			return new ModelProvider() {
				public String providerId() { return "fake"; }
				public ProviderAuth auth() { return () -> true; }
				public List<ModelEntry> models() {
					return List.of(new ModelEntry(new ModelRef("fake", "child-scope"), "fake", null));
				}
				public ChatModel create(ModelRef ref, Map<String, String> options) { return new ScriptedChatModel(); }
			};
		}
	}
}
