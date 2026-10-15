package com.example.miniagent.core.delegation.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.example.miniagent.core.Agent;
import com.example.miniagent.core.delegation.ChildEvidence;
import com.example.miniagent.core.delegation.ChildRole;
import com.example.miniagent.core.delegation.ChildSpec;
import com.example.miniagent.core.delegation.DelegationPlan;
import com.example.miniagent.core.delegation.ToolScope;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunBudget;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Model input contains child requests, never parent grants or evidence. */
public record DelegationInput(String planId, List<Child> children) {
	static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
	public static final String SCHEMA = """
			{"type":"object","additionalProperties":false,"required":["planId","children"],
			"properties":{"planId":{"type":"string"},"children":{"type":"array","items":{
			"type":"object","additionalProperties":false,"required":["childId","goal","role","tools"],
			"properties":{"childId":{"type":"string"},"goal":{"type":"string"},
			"role":{"type":"string","enum":["RESEARCH","IMPLEMENT","VERIFY"]},"model":{"type":["string","null"]},
			"dependsOn":{"type":"array","items":{"type":"string"}},"tools":{"type":"array","items":{"type":"string"}},
			"writeRoots":{"type":"array","items":{"type":"string"}},"generatedRoots":{"type":"array","items":{"type":"string"}},
			"wallSeconds":{"type":"integer","minimum":1},"toolCallCap":{"type":"integer","minimum":0},
			"tokenCap":{"type":["integer","null"],"minimum":1},"required":{"type":"boolean"}}}}}}
			""";
	public record Child(String childId, String goal, ChildRole role, @Nullable String model,
			@Nullable Set<String> dependsOn, Set<String> tools, @Nullable List<String> writeRoots,
			@Nullable List<String> generatedRoots, @Nullable Long wallSeconds,
			@Nullable Integer toolCallCap, @Nullable Long tokenCap, @Nullable Boolean required) {}

	public static DelegationInput parse(String input) { return JSON.readValue(input, DelegationInput.class); }
	public DelegationPlan plan(Path cwd) {
		return new DelegationPlan(this.planId, this.children.stream().map(c -> {
			ModelRef ref = null;
			if (c.model() != null) {
				int slash = c.model().indexOf('/');
				if (slash < 1) throw new IllegalArgumentException("Expected provider/model");
				ref = new ModelRef(c.model().substring(0, slash), c.model().substring(slash + 1));
			}
			var scope = new ToolScope(c.tools(), Set.of(cwd),
					c.writeRoots() == null && c.role() == ChildRole.IMPLEMENT ? Set.of(cwd) : roots(c.writeRoots(), cwd),
					roots(c.generatedRoots(), cwd), c.role() == ChildRole.IMPLEMENT
							|| c.generatedRoots() != null && !c.generatedRoots().isEmpty(), false, false, false);
			return new ChildSpec(c.childId(), c.goal(), c.role(), ref,
					c.dependsOn() == null ? Set.of() : c.dependsOn(), scope,
					new RunBudget(Duration.ofSeconds(c.wallSeconds() == null ? 300 : c.wallSeconds()),
							c.tokenCap(), c.toolCallCap() == null ? 10 : c.toolCallCap()), 1,
					c.required() == null || c.required());
		}).toList(), 1);
	}
	private static Set<Path> roots(@Nullable List<String> paths, Path cwd) {
		return paths == null ? Set.of() : paths.stream().map(p -> cwd.resolve(p).normalize()).collect(Collectors.toSet());
	}
	/** Trusted console/parent Java boundary; requests can only reduce this authority. */
	public static ToolScope authority(Agent agent, Path cwd) {
		return authority(agent.toolDefinitions().stream().map(d -> d.name()).collect(Collectors.toSet()), cwd);
	}
	static ToolScope authority(Set<String> tools, Path cwd) {
		return new ToolScope(tools, Set.of(cwd), Set.of(cwd), Set.of(cwd.resolve("target"), cwd.resolve("build")),
				true, false, true, true);
	}
	static String outcome(DefaultDelegationRuntime.Outcome outcome) {
		if (outcome instanceof DefaultDelegationRuntime.Pending pending) return JSON.writeValueAsString(pending);
		var result = ((DefaultDelegationRuntime.Completed) outcome).result();
		return JSON.writeValueAsString(Map.of("planId", result.planId(), "status", result.status(),
				"usage", result.usage(), "children", result.children().stream().map(child ->
						Map.of("childId", child.childId(), "runId", child.runId(), "attemptId", child.attemptId(),
								"model", child.model(), "status", child.status(),
								"failure", child.failure() == null ? "" : child.failure().code(),
								"evidence", child.evidence().stream().map(e -> e instanceof ChildEvidence.Collected collected
										? Map.of("role", collected.role(), "revision", collected.revision(),
												"configurationDigest", collected.configurationDigest(), "facts", collected.facts())
										: e).toList())).toList()));
	}
}
