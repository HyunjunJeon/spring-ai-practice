package com.example.miniagent.core.delegation;

import java.nio.file.Path;
import java.util.Set;
import com.example.miniagent.core.AgentInputHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationInputTest {
	@TempDir Path cwd;

	@Test void forgedParentFieldsDuplicateIdsAndTrailingJsonAreRejected() {
		for (var input : new String[] {
				"{\"planId\":\"p\",\"planId\":\"other\",\"children\":[]}",
				"{\"planId\":\"p\",\"children\":[],\"parentAuthority\":{\"apply\":true}}",
				"{\"planId\":\"p\",\"children\":[]} {}"})
			assertThatThrownBy(() -> DelegationInput.parse(input)).isInstanceOf(RuntimeException.class);
	}

	@Test void wireSchemaAndParsedDefaultsDoNotGrantParentCapabilities() throws Exception {
		assertThat(DelegationInput.JSON.readTree(DelegationInput.SCHEMA).get("additionalProperties").asBoolean()).isFalse();
		var plan = DelegationInput.parse("""
				{"planId":"work","children":[{"childId":"implement","goal":"write","role":"IMPLEMENT","tools":["write_file"]}]}
				""").plan(this.cwd.toRealPath());
		var child = plan.children().getFirst();
		assertThat(child.scope().writeRoots()).containsExactly(this.cwd.toRealPath());
		assertThat(child.scope().apply()).isFalse();
		assertThat(child.scope().delegation()).isFalse();
		assertThat(child.budget().wall().toSeconds()).isEqualTo(300);
		assertThat(child.budget().toolCallCap()).isEqualTo(10);
	}
	@Test void suppliedSourceGeneratedRootCannotWidenVerifyAuthority() throws Exception {
		var root = this.cwd.toRealPath();
		var child = DelegationInput.parse("""
				{"planId":"work","children":[{"childId":"verify","goal":"check","role":"VERIFY",
				"tools":["write_file"],"generatedRoots":["."]}]}
				""").plan(root).children().getFirst();
		var authority = DelegationInput.authority(Set.of("write_file"), root);
		assertThatThrownBy(() -> new RolePolicy().validateScope(child.role(), child.scope(), authority))
				.isInstanceOf(RolePolicy.Denied.class);
	}
	@Test void onlyMetadataResolutionAndCancelBypassBusy() {
		for (String input : new String[] {"/delegate list", "/delegate show p", "/delegate cancel p",
				"/delegate approve p request", "/delegate reject p request", "/delegate defer p request"})
			assertThat(AgentInputHandler.isControlInput(input)).isTrue();
		for (String input : new String[] {"/delegate start {}", "/delegate apply p c proposal", "/delegate resume p c"})
			assertThat(AgentInputHandler.isControlInput(input)).isFalse();
	}
	@Test void parentToolsRejectContextFreeDispatchBeforeBackendLookup() {
		var delegate = new DelegateTool(() -> { throw new AssertionError("No backend lookup"); });
		var apply = new ApplyStagedTool(() -> { throw new AssertionError("No backend lookup"); },
				() -> { throw new AssertionError("No console lookup"); });
		assertThatThrownBy(() -> delegate.call("{}")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> apply.call("{}")).isInstanceOf(IllegalStateException.class);
	}
}
