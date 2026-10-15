package com.example.miniagent.core.artifact;

import java.util.Map;

import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.run.SideEffectClass;
import org.springframework.core.Ordered;

/** Inside journal acknowledgement, outside the original callback. No tool definition is rewritten. */
public final class ArtifactResultInterceptor implements ToolInterceptor {
	private final ArtifactStore store;
	private final int thresholdChars;
	private final Map<String, String> retrievalDefinitions;

	public ArtifactResultInterceptor(ArtifactStore store, ArtifactProperties properties,
			Map<String, String> retrievalDefinitions) {
		properties.validate();
		this.store = store;
		this.thresholdChars = properties.getThresholdChars();
		this.retrievalDefinitions = Map.copyOf(retrievalDefinitions);
	}

	@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 40; }

	@Override public String intercept(ToolInvocation invocation, Chain chain) {
		String result = chain.proceed(invocation);
		var authority = ArtifactTools.authority(invocation.context());
		if (result == null || result.length() <= this.thresholdChars || authority == null
				|| SideEffectClass.definitionDigest(invocation.definition())
						.equals(this.retrievalDefinitions.get(invocation.toolName())))
			return result;
		// Publication errors propagate. A completed effect must not be acknowledged with lost output.
		return ArtifactStore.JSON.writeValueAsString(this.store.put(authority, result));
	}
}
