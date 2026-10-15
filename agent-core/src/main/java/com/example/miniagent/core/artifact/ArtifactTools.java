package com.example.miniagent.core.artifact;

import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Retrieval accepts opaque IDs only; authority never comes from model arguments. */
public final class ArtifactTools {
	private final ArtifactStore store;

	public ArtifactTools(ArtifactStore store) { this.store = store; }

	@Tool(name = "read_artifact", description = "Read a stored tool result in this session and workspace. "
			+ "Offsets are zero-based Unicode code points; limit defaults to 1024, maximum 2048. "
			+ "Follow nextOffset while truncated is true. Content preserves the original callback text.")
	public ArtifactStore.Page readArtifact(
			@ToolParam(description = "Opaque artifactId from a tool-result descriptor") String artifactId,
			@ToolParam(description = "Zero-based code-point offset, default 0", required = false) @Nullable Integer offset,
			@ToolParam(description = "Code points to return, 1..2048, default 1024", required = false) @Nullable Integer limit,
			ToolContext toolContext) {
		return this.store.read(requireAuthority(toolContext), artifactId, offset == null ? 0 : offset,
				limit == null ? ArtifactStore.DEFAULT_READ_LIMIT : limit);
	}

	@Tool(name = "search_artifact", description = "Search this session/workspace artifact for a case-sensitive literal "
			+ "substring, not a regular expression. Returns non-overlapping matches with code-point offsets and "
			+ "bounded previews. Follow nextOffset while truncated is true. Maximum 20 matches per page.")
	public ArtifactStore.SearchPage searchArtifact(
			@ToolParam(description = "Opaque artifactId from a tool-result descriptor") String artifactId,
			@ToolParam(description = "Literal nonempty query, at most 256 UTF-16 characters") String query,
			@ToolParam(description = "Zero-based code-point offset, default 0", required = false) @Nullable Integer offset,
			@ToolParam(description = "Matches to return, 1..20, default 10", required = false) @Nullable Integer limit,
			ToolContext toolContext) {
		return this.store.search(requireAuthority(toolContext), artifactId, query, offset == null ? 0 : offset,
				limit == null ? ArtifactStore.DEFAULT_SEARCH_LIMIT : limit);
	}

	static @Nullable TurnContext authority(@Nullable ToolContext context) {
		return context != null && context.getContext().get(ToolContextKeys.TURN_CONTEXT) instanceof TurnContext turn
				? turn : null;
	}

	static TurnContext requireAuthority(@Nullable ToolContext context) {
		TurnContext turn = authority(context);
		if (turn == null) throw new ArtifactStore.Failure(ArtifactStore.Code.AUTHORITY_REQUIRED,
				"retrieval requires captured TurnContext");
		return turn;
	}
}
