package com.example.miniagent.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import com.example.miniagent.core.*;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.core.run.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = { "agent.ui=plain", "agent.repl.enabled=false",
		"agent.plugins.memory.enabled=false", "agent.plugins.permission.enabled=false",
		"agent.plugins.trace.level=off" })
@Import({ AgentTestSupport.class, ImmediateToolMigrationTest.Fixture.class })
class ImmediateToolMigrationTest {
	@TempDir static Path workspace;
	@TempDir static Path state;
	@DynamicPropertySource static void paths(DynamicPropertyRegistry p) {
		p.add("agent.home", () -> state.resolve("home").toString());
		p.add("agent.cwd", () -> workspace.toString());
	}
	@Autowired Agent agent;
	@Autowired RunExecutor executor;
	@Autowired ChangeStore changes;
	@Autowired RunStore runs;
	@Autowired Counters counters;

	@Test void managedSpringToolLeavesLiveBytesAndModeUnchangedAndPersistsDiff() throws Exception {
		Path source = Files.writeString(workspace.resolve("managed.sh"), "echo before\n");
		var mode = PosixFilePermissions.fromString("rwxr-x---");
		Files.setPosixFilePermissions(source, mode);
		var result = executor.start("!tool write_file {\"path\":\"" + source
				+ "\",\"content\":\"echo after\\n\"}", "parent", agent.selectedModel(), workspace, RunBudget.defaults());
		assertThat(result.record().state()).isEqualTo(RunState.COMPLETED);
		assertThat(Files.readString(source)).isEqualTo("echo before\n");
		assertThat(Files.getPosixFilePermissions(source)).isEqualTo(mode);
		var set = changes.read().changes().stream().map(ChangeStore.Snapshot::changeSet)
				.filter(c -> c.owner().runId().equals(result.record().spec().runId())).findFirst().orElseThrow();
		assertThat(set.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
		assertThat(set.owner().attemptId()).isEqualTo(result.record().attempts().getLast().attemptId());
		assertThat(set.bindingIds()).containsExactly(result.record().attempts().getLast().bindingId());
		assertThat(set.diffs()).extracting(Diff::relativePath).containsExactly("managed.sh");
		Path stage;
		try (var lease = changes.acquire()) { stage = lease.stagingDirectory(set.owner()); }
		assertThat(Files.readString(stage.resolve("managed.sh"))).isEqualTo("echo after\n");
		assertThat(Files.getPosixFilePermissions(stage.resolve("managed.sh"))).isEqualTo(mode);
		assertThat(runs.export(result.record().spec().runId()).eventsLog())
				.contains("ToolStarted", "ToolFinished");
	}

	@Test void legacyOrdinaryTurnStillWritesImmediatelyWithSameToolArguments() throws Exception {
		Path source = Files.writeString(workspace.resolve("legacy.txt"), "before");
		agent.run("!tool write_file {\"path\":\"legacy.txt\",\"content\":\"after\"}");
		assertThat(Files.readString(source)).isEqualTo("after");
	}

	@Test void outsideExcludedAndLinkMutationsAreDeniedBeforePermission() throws Exception {
		Path outside = Files.writeString(state.resolve("outside.txt"), "external");
		Path target = Files.writeString(workspace.resolve("link-target"), "original");
		Files.createSymbolicLink(workspace.resolve("managed-link"), target);
		for (String path : java.util.List.of(outside.toString(), ".env", "target/generated", "managed-link")) {
			int before = counters.permission.get();
			String args = tools.jackson.databind.json.JsonMapper.builder().build()
					.writeValueAsString(java.util.Map.of("path", path, "content", "forbidden"));
			assertThatThrownBy(() -> executor.start("!tool write_file " + args, "parent",
					agent.selectedModel(), workspace, RunBudget.defaults())).isInstanceOf(RuntimeException.class);
			assertThat(counters.permission).hasValue(before);
		}
		assertThat(Files.readString(outside)).isEqualTo("external");
		assertThat(Files.readString(target)).isEqualTo("original");
		assertThat(workspace.resolve(".env")).doesNotExist();
	}

	@Test void unknownMcpIsDeniedBeforePermissionBudgetJournalOrEffect() throws Exception {
		int permission = counters.permission.get();
		int effects = counters.effects.get();
		assertThatThrownBy(() -> executor.start("!tool unclassified_mcp {}",
				"parent", agent.selectedModel(), workspace, RunBudget.defaults()))
				.satisfies(ex -> {
					Throwable cause = ex;
					while (cause.getCause() != null) cause = cause.getCause();
					assertThat(cause).isInstanceOf(StagingToolInterceptor.Refusal.class);
				});
		assertThat(counters.permission).hasValue(permission);
		assertThat(counters.effects).hasValue(effects);
		var record = runs.list().stream().map(id -> {
			try { return runs.read(id); }
			catch (Exception ex) { throw new RuntimeException(ex); }
		}).filter(r -> r.spec().conversationId() != null && r.state() == RunState.FAILED).findFirst().orElseThrow();
		assertThat(record.usage().toolCalls()).isZero();
		assertThat(record.uncertainTools()).isEmpty();
	}

	@TestConfiguration static class Fixture {
		@Bean Counters counters() { return new Counters(); }
		@Bean ToolCallbackProvider unclassifiedProvider(Counters counters) {
			return MethodToolCallbackProvider.builder().toolObjects(new UnknownTools(counters)).build();
		}
		@Bean ToolInterceptor permissionBoundary(Counters counters) {
			return new ToolInterceptor() {
				@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 20; }
				@Override public String intercept(ToolInvocation invocation, Chain chain) {
					counters.permission.incrementAndGet();
					return chain.proceed(invocation);
				}
			};
		}
	}
	static class Counters { final AtomicInteger permission = new AtomicInteger(); final AtomicInteger effects = new AtomicInteger(); }
	static class UnknownTools {
		private final Counters counters;
		UnknownTools(Counters counters) { this.counters = counters; }
		@Tool(name = "unclassified_mcp", description = "Unclassified external effect")
		public String call() { this.counters.effects.incrementAndGet(); return "effect"; }
	}
}
