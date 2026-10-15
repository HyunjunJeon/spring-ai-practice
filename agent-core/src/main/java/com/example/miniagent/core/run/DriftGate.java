package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import com.example.miniagent.core.Agent;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Exact source/tool/config acknowledgement; identity incompatibilities remain executor refusals. */
public final class DriftGate {
	private final ToolEvidenceStore evidence;
	private final List<SideEffectClass.Policy> policies;
	private final Supplier<String> configuration;
	private Supplier<String> stagingConfiguration = () -> "staged-v1";

	public DriftGate(ToolEvidenceStore evidence) { this(evidence, List.of(), () -> "default"); }
	public DriftGate(ToolEvidenceStore evidence, List<SideEffectClass.Policy> policies,
			Supplier<String> configuration) {
		this.evidence = Objects.requireNonNull(evidence);
		this.policies = List.copyOf(policies);
		this.configuration = Objects.requireNonNull(configuration);
	}
	Path home() { return this.evidence.home(); }

	public void stagingConfiguration(Supplier<String> configuration) {
		this.stagingConfiguration = Objects.requireNonNull(configuration);
	}

	public record Definition(String digest, SideEffectClass effect) {}
	public record Snapshot(int schemaVersion, String specDigest, WorkspaceFingerprint workspace,
			Map<String, Definition> tools, String configurationDigest) {
		public Snapshot {
			if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported snapshot schema");
			RunSpec.requireDigest(specDigest);
			RunSpec.requireDigest(configurationDigest);
			tools = Collections.unmodifiableMap(new TreeMap<>(tools));
			for (var definition : tools.values()) {
				RunSpec.requireDigest(definition.digest());
				Objects.requireNonNull(definition.effect());
			}
			Objects.requireNonNull(workspace);
		}
	}
	public record Difference(String expectedDigest, String actualDigest) {
		public boolean changed() { return !expectedDigest.equals(actualDigest); }
	}
	public record Acknowledgement(String expectedDigest, String actualDigest, String operatorId, String evidenceId) {
		public Acknowledgement {
			RunSpec.requireDigest(expectedDigest); RunSpec.requireDigest(actualDigest);
			RunId.requireSafe(operatorId); RunId.requireSafe(evidenceId);
		}
	}

	Snapshot capture(Agent agent, RunSpec spec) throws IOException {
		Map<String, String> trusted = new HashMap<>();
		for (var policy : this.policies) trusted.putAll(policy.readOnlyDefinitions());
		Map<String, Definition> tools = new TreeMap<>();
		for (ToolDefinition tool : agent.toolDefinitions()) {
			String digest = SideEffectClass.definitionDigest(tool);
			if (tools.put(tool.name(), new Definition(digest, digest.equals(trusted.get(tool.name()))
					? SideEffectClass.READ_ONLY : SideEffectClass.MUTATING)) != null)
				throw new IOException("Duplicate snapshot tool");
		}
		String config = this.configuration.get() + "\0" + this.stagingConfiguration.get() + "\0" + agent.systemPrompt() + "\0"
				+ agent.toolCallingAdvisorOrder() + "\0" + agent.advisors().stream()
				.map(a -> a.getClass().getName() + ":" + a.getOrder()).toList();
		return new Snapshot(1, specDigest(spec), WorkspaceFingerprint.capture(Path.of(spec.cwd()),
				this.evidence.home()), tools, RunRequestStore.digest(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	Snapshot load(String digest, RunSpec spec) throws IOException {
		Snapshot snapshot = this.evidence.get(digest, Snapshot.class);
		if (!snapshot.specDigest().equals(specDigest(spec)) || !snapshot.workspace().cwd().equals(spec.cwd()))
			throw new IOException("Snapshot identity mismatch");
		return snapshot;
	}

	String latest(RunStore runs, RunRecord record, String baseline) throws IOException {
		if (baseline == null) throw new IOException("Missing owner baseline; start a new run");
		String latest = baseline;
		for (RunEvent event : FileRunStore.events(runs, record.spec().runId())) {
			if (event instanceof RunEvent.ToolFinished finished) {
				var result = this.evidence.get(finished.resultDigest(), ToolEvidenceStore.ResultEvidence.class);
				validateResult(result, record.spec(), finished);
				latest = this.evidence.put(result.after());
			}
			else if (event instanceof RunEvent.DriftAccepted accepted) {
				if (!latest.equals(accepted.expectedDigest())) throw new IOException("Drift baseline chain mismatch");
				load(accepted.actualDigest(), record.spec());
				latest = accepted.actualDigest();
			}
		}
		load(latest, record.spec());
		return latest;
	}

	Difference inspect(Agent agent, RunStore runs, RunRecord record, String baseline) throws IOException {
		return new Difference(latest(runs, record, baseline), this.evidence.put(capture(agent, record.spec())));
	}

	void require(Difference difference, Acknowledgement acknowledgement) throws IOException {
		if (!difference.changed()) {
			if (acknowledgement != null) throw new IOException("Stale drift acknowledgement");
			return;
		}
		if (acknowledgement == null || !difference.expectedDigest().equals(acknowledgement.expectedDigest())
				|| !difference.actualDigest().equals(acknowledgement.actualDigest()))
			throw new IOException("Exact source/tool/config drift acknowledgement required");
	}

	boolean authoritativeRead(RunEvent.ToolStarted tool, RunSpec spec) throws IOException {
		if (tool.snapshotDigest() == null || tool.effect() != RunEvent.Effect.READ_ONLY) return false;
		Definition definition = load(tool.snapshotDigest(), spec).tools().get(tool.toolName());
		return definition != null && definition.effect() == SideEffectClass.READ_ONLY;
	}

	static String specDigest(RunSpec spec) { return RunRequestStore.digest(ToolEvidenceStore.JSON.writeValueAsBytes(spec)); }

	static void validateResult(ToolEvidenceStore.ResultEvidence result, RunSpec spec,
			RunEvent.ToolFinished finished) throws IOException {
		if (result.schemaVersion() != 1 || !result.runId().equals(spec.runId().value())
				|| !result.attemptId().equals(finished.attemptId()) || !result.toolCallKey().equals(finished.toolCallKey())
				|| !result.after().specDigest().equals(specDigest(spec)))
			throw new IOException("Result evidence owner mismatch");
	}
}
