package com.example.miniagent.core.delegation.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.delegation.ChildEvidence;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.RolePolicy;
import com.example.miniagent.core.execution.StagingToolInterceptor;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.run.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;

/** A trusted process boundary, not a shell tool or cross-owner evidence conversion. */
public final class DelegationVerificationTool {
	private static final JsonMapper JSON = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private static final ToolDefinition DEFINITION = ToolDefinition.builder().name("verify_staged")
		.description("Verify one exact IMPLEMENT dependency in a source-read-only sandbox")
		.inputSchema("{\"type\":\"object\",\"properties\":{\"childId\":{\"type\":\"string\"},"
			+ "\"argv\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"timeoutMillis\":{\"type\":\"integer\"},"
			+ "\"maxOutputBytes\":{\"type\":\"integer\"}},\"required\":[\"childId\",\"argv\",\"timeoutMillis\","
			+ "\"maxOutputBytes\"],\"additionalProperties\":false}").build();
	private final ChangeStore changes;
	private final VerificationRunner.LaunchPolicy sandbox;
	private final Clock clock;
	private final ToolCallback callback = new ToolCallback() {
		@Override public ToolDefinition getToolDefinition() { return DEFINITION; }
		@Override public String call(String input) { throw new IllegalStateException("Captured child required"); }
		@Override public String call(String input, ToolContext tools) {
			try { return invoke(input, tools); }
			catch (IOException ex) { throw new UncheckedIOException(ex); }
		}
	};

	public DelegationVerificationTool(ChangeStore changes, VerificationRunner.LaunchPolicy sandbox, Clock clock) {
		this.changes = java.util.Objects.requireNonNull(changes);
		this.sandbox = java.util.Objects.requireNonNull(sandbox);
		this.clock = java.util.Objects.requireNonNull(clock);
	}
	public ToolCallback callback() {
		return this.callback;
	}
	public StagingToolInterceptor.Policy stagingPolicy() {
		return () -> Map.of(DEFINITION.name(), new StagingToolInterceptor.Declaration(
			SideEffectClass.definitionDigest(DEFINITION), StagingToolInterceptor.Kind.VERIFICATION, List.of()));
	}
	public SideEffectClass.Policy readPolicy() {
		return () -> Map.of(DEFINITION.name(), SideEffectClass.definitionDigest(DEFINITION));
	}
	record Input(String childId, List<String> argv, long timeoutMillis, int maxOutputBytes) {}
	static ChildScopeFactory.Dependency preflight(ChildScopeFactory.Scope scope, String input) throws IOException {
		scope.requireOpen();
		if (scope.metadata().role() != ChildRole.VERIFY)
			throw new RolePolicy.Denied(RolePolicy.Code.ROLE_MUTATION, DEFINITION.name());
		Input args = JSON.readValue(input, Input.class);
		var dependency = scope.dependencies().stream().filter(d -> d.spec().childId().equals(args.childId()))
			.findFirst().orElseThrow(() -> new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Foreign dependency"));
		var expected = dependency.evidence();
		if (dependency.spec().role() != ChildRole.IMPLEMENT || expected == null
				|| !dependency.result().verifiedSuccess(ChildRole.IMPLEMENT, expected.revision(), expected.configurationDigest())
				|| !expected.parentRunId().value().equals(scope.metadata().parentRunId())
				|| !expected.parentConversationId().toString().equals(scope.metadata().parentConversationId()))
			throw new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Unproven IMPLEMENT dependency");
		// Validate runner bounds before permission, budget or any stage effects.
		new VerificationRunner.Request(java.util.UUID.randomUUID(), expected.owner(), "0".repeat(64),
			expected.revision(), args.argv(), Duration.ofMillis(args.timeoutMillis()), args.maxOutputBytes());
		if (Duration.ofMillis(args.timeoutMillis()).compareTo(scope.runContext().spec().budget().wall()) > 0)
			throw new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Process exceeds child reservation");
		return dependency;
	}
	private String invoke(String input, ToolContext tools) throws IOException {
		var scope = (ChildScopeFactory.Scope) tools.getContext().get(ToolContextKeys.CHILD_SCOPE);
		if (scope == null || RunContext.fromToolContext(tools).orElse(null) != scope.runContext()
				|| RunContext.current().orElse(null) != scope.runContext())
			throw new RolePolicy.Denied(RolePolicy.Code.SCOPE_WIDENING, "Missing active VERIFY owner");
		var stage = StagedWorkspace.from(tools).orElseThrow();
		if (!stage.owner().runId().equals(scope.runContext().spec().runId())
				|| !stage.owner().attemptId().equals(scope.runContext().attemptId())
				|| !stage.owner().turnId().equals(scope.context().turnId()))
			throw new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, "Foreign stage");
		var dependency = preflight(scope, input);
		Input args = JSON.readValue(input, Input.class);
		var proof = dependency.result().evidence().stream().filter(ChildEvidence.Proposal.class::isInstance)
			.map(ChildEvidence.Proposal.class::cast).findFirst().orElseThrow();
		var live = new PathPolicy(this.changes.workspace(), this.changes.directory().getParent().getParent(), List.of());
		ChangeSet source;
		try (var lease = this.changes.acquireStaging()) {
			var view = lease.read();
			var snapshot = view.get(proof.changeSetId());
			source = snapshot.changeSet();
			if (view.recoveryRequired() || !source.owner().equals(dependency.evidence().owner())
					|| !snapshot.digest().equals(proof.digest())
					|| (source.status() != ChangeStatus.PENDING_APPROVAL && source.status() != ChangeStatus.APPROVED)
					|| !new Fingerprinter(live).snapshot().equals(source.baseline()))
				throw new IOException("Stale dependency proposal");
			var policy = dependency.evidence().stagePolicy();
			if (policy == null || !policy.root().equals(lease.stagingDirectory(source.owner())))
				throw new IOException("Foreign dependency stage");
			var tree = new TreeMap<>(new Fingerprinter(policy).snapshot());
			for (var f : source.proposed().values()) if (f.kind() == EntryKind.SYMBOLIC_LINK) {
				if (tree.containsKey(f.relativePath())) throw new IOException("Dependency link replaced");
				tree.put(f.relativePath(), f);
			}
			if (!tree.equals(source.proposed())) throw new IOException("Stale dependency stage");
			for (String path : source.proposed().keySet())
				if (scope.metadata().scope().readRoots().stream().noneMatch(live.root().resolve(path)::startsWith))
					throw new RolePolicy.Denied(RolePolicy.Code.PATH_DENIED, path);
			// Only this owned stage changes; the model cannot select a publication target.
			var existing = new Fingerprinter(stage.policy()).snapshot();
			for (var f : existing.values().stream().sorted(java.util.Comparator.comparing(
					Fingerprint::relativePath).reversed()).toList()) {
				if (!source.proposed().containsKey(f.relativePath())
						|| source.proposed().get(f.relativePath()).kind() != f.kind())
					Files.delete(stage.policy().resolveMutation(f.relativePath()));
			}
			var reader = new Fingerprinter(policy);
			for (var f : source.proposed().values()) {
				if (f.kind() == EntryKind.SYMBOLIC_LINK) continue;
				var observed = reader.read(f.relativePath());
				if (!observed.fingerprint().equals(f)) throw new IOException("Dependency changed during import");
				Path target = stage.resolve(f.relativePath(), true, List.of());
				if (f.kind() == EntryKind.DIRECTORY) Files.createDirectories(target);
				else {
					Files.createDirectories(target.getParent());
					Files.write(target, observed.bytes());
					Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(policy.resolve(f.relativePath()),
						LinkOption.NOFOLLOW_LINKS));
				}
			}
			for (var f : source.proposed().values().stream().sorted(java.util.Comparator.comparing(
					Fingerprint::relativePath).reversed()).toList())
				if (f.kind() == EntryKind.DIRECTORY)
					Files.setPosixFilePermissions(stage.policy().resolve(f.relativePath()),
						Files.getPosixFilePermissions(policy.resolve(f.relativePath()), LinkOption.NOFOLLOW_LINKS));
		}
		stage.record();
		var owned = this.changes.read().changes().stream().filter(s -> s.changeSet().owner().equals(stage.owner())
			&& s.changeSet().status() == ChangeStatus.PENDING_APPROVAL).toList();
		if (owned.size() != 1 || !owned.getFirst().changeSet().proposed().equals(source.proposed()))
			throw new IOException("VERIFY proposal does not equal dependency");
		var proposal = owned.getFirst();
		var request = new VerificationRunner.Request(proposal.changeSet().changeSetId(), stage.owner(),
			proposal.digest(), Fingerprinter.treeDigest(source.proposed()), args.argv(),
			Duration.ofMillis(Math.min(args.timeoutMillis(), Math.max(1,
				scope.runContext().spec().budget().wall().toMillis() - scope.runContext().usage().activeMillis()))),
			args.maxOutputBytes());
		var configuration = new AtomicReference<String>();
		var runner = new VerificationRunner(this.changes, (context, argv) -> {
			var launch = this.sandbox.prepare(context, argv);
			configuration.set(configuration(request, launch));
			return launch;
		}, this.clock);
		// Executor's exact worker interruption is Runner's cancellation signal and process cleanup.
		var result = runner.run(request, new CompletableFuture<>());
		scope.verified(request, result, configuration.get() == null ? "0".repeat(64) : configuration.get());
		return result.code().name();
	}
	private static String configuration(VerificationRunner.Request request, VerificationRunner.Launch launch)
			throws IOException {
		var bytes = new ByteArrayOutputStream();
		var out = new DataOutputStream(bytes);
		for (String field : List.of("verification-v1-filtered-source-no-links", System.getProperty("os.name"),
				System.getProperty("os.version"), System.getProperty("os.arch"), System.getProperty("java.version"),
				launch.policyDigest())) field(out, field);
		out.writeInt(request.argv().size());
		for (String arg : request.argv()) field(out, arg);
		out.writeLong(request.timeout().toNanos());
		out.writeInt(request.maxOutputBytes());
		return Fingerprinter.sha256(bytes.toByteArray());
	}
	private static void field(DataOutputStream out, String field) throws IOException {
		byte[] bytes = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		out.writeInt(bytes.length); out.write(bytes);
	}
}
