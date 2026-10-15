package com.example.miniagent.core.lesson;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.example.miniagent.core.ToolContextKeys;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static com.example.miniagent.core.lesson.LessonRecord.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LessonAdvisorTest {
	@TempDir Path root;
	private static ChatClientRequest request(String session, Path cwd, List<Message> messages) {
		return new ChatClientRequest(new Prompt(messages, ToolCallingChatOptions.builder().build()),
				Map.of(ToolContextKeys.SESSION_ID, session, ToolContextKeys.CWD, cwd.toString()));
	}
	@Test void lateAcceptanceIsVisibleOnNextRequestWithoutReplacingFirstSystemMessage() throws Exception {
		var f = new LessonFixture(root);
		var advisor = new LessonAdvisor(f.service);
		var base = new SystemMessage("base");
		var project = new SystemMessage("project");
		var user = new UserMessage("parser issue");
		var input = request(f.actor.session(), Path.of(f.actor.workspace()), List.of(base, project, user));
		assertThat(advisor.prepare(input).context().get(LessonAdvisor.SELECTED)).isEqualTo(List.of());
		var accepted = f.accepted("parser preserve byte offsets");
		var result = advisor.prepare(input);
		assertThat(result.prompt().getInstructions().getFirst()).isSameAs(base);
		assertThat(result.prompt().getInstructions().get(1)).isSameAs(project);
		assertThat(result.prompt().getInstructions().getLast()).isSameAs(user);
		assertThat(result.context().get(LessonAdvisor.SELECTED)).isEqualTo(
				List.of(Map.of("id", accepted.id(), "version", 2, "scope", "SESSION")));
		var child = request("child", Path.of(f.actor.workspace()), List.of(base, user));
		assertThat(advisor.prepare(child).context().get(LessonAdvisor.SELECTED)).isEqualTo(List.of());
	}

	@Test void selectionAndEscapedInjectionStayWithinBothCaps() throws Exception {
		var f = new LessonFixture(root);
		for (int i = 0; i < 7; i++) f.accepted("parser " + i + " quotes: \"\n".repeat(150));
		var input = request(f.actor.session(), Path.of(f.actor.workspace()), List.of(new SystemMessage("base"),
				new UserMessage("parser")));
		var prepared = new LessonAdvisor(f.service).prepare(input);
		var ids = (List<?>) prepared.context().get(LessonAdvisor.SELECTED);
		assertThat(ids.size()).isBetween(1, 5);
		int injected = prepared.prompt().getInstructions().stream()
				.filter(m -> !input.prompt().getInstructions().contains(m)).mapToInt(m -> m.getText().length()).sum();
		assertThat(injected).isLessThanOrEqualTo(LessonAdvisor.MAX_INJECTION_CHARS);
		assertThat(f.service.lookup(f.actor, "parser")).hasSize(5);
	}

	@Test void capturedChildTurnWinsOverAnyParentSessionFields() throws Exception {
		var f = new LessonFixture(root);
		f.accepted("parser parent private");
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("codex", "gpt-6.1-sol"),
				LessonFixture.HASH, "responses", mock(ChatModel.class), mock(ChatClient.class));
		var child = new TurnContext("child", "child-turn", Path.of(f.actor.workspace()), binding);
		var input = new ChatClientRequest(new Prompt(List.of(new SystemMessage("base"), new UserMessage("parser")),
				ToolCallingChatOptions.builder().build()), Map.of(ToolContextKeys.TURN_CONTEXT, child,
						ToolContextKeys.SESSION_ID, f.actor.session(), ToolContextKeys.CWD, f.actor.workspace()));
		assertThat(new LessonAdvisor(f.service).prepare(input).context().get(LessonAdvisor.SELECTED)).isEqualTo(List.of());
	}

	@Test void childContributionRetrievesProjectAndGlobalButNotParentSessionLessons() throws Exception {
		var f = new LessonFixture(root);
		var session = f.accepted("parser parent session only");
		var project = f.accepted("parser project guidance");
		project = f.service.promote(project.id(), project.version(), Scope.PROJECT, f.actor);
		var global = f.accepted("parser global guidance");
		global = f.service.promote(global.id(), global.version(), Scope.GLOBAL, f.actor);
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("codex", "gpt-6.1-sol"),
			LessonFixture.HASH, "responses", mock(ChatModel.class), mock(ChatClient.class));
		var child = new TurnContext("child-conversation", "child-turn", Path.of(f.actor.workspace()), binding);
		var contributions = new LessonAutoConfiguration().childLessonContribution(f.service)
			.create(binding.chatModel(), () -> child);
		assertThat(contributions).hasSize(1);
		var input = new ChatClientRequest(new Prompt(List.of(new UserMessage("parser")),
			ToolCallingChatOptions.builder().build()), Map.of(ToolContextKeys.TURN_CONTEXT, child,
				ToolContextKeys.SESSION_ID, f.actor.session(), ToolContextKeys.CWD, f.actor.workspace()));
		var prepared = ((LessonAdvisor) contributions.getFirst()).prepare(input);
		assertThat((List<?>) prepared.context().get(LessonAdvisor.SELECTED)).hasSize(2);
		@SuppressWarnings("unchecked")
		var selected = (List<Map<String, Object>>) prepared.context().get(LessonAdvisor.SELECTED);
		assertThat(selected).extracting(v -> v.get("id")).containsExactlyInAnyOrder(project.id(), global.id())
			.doesNotContain(session.id());
	}

	@Test void refinementStripsOnlyRequestToolsAndBlocksActualEffectDispatch() throws Exception {
		var f = new LessonFixture(root);
		var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("codex", "gpt-6.1-sol"),
				LessonFixture.HASH, "responses", mock(ChatModel.class), mock(ChatClient.class));
		var turn = new TurnContext(f.actor.session(), "refine", Path.of(f.actor.workspace()), binding);
		var capability = new LessonRefinementAdvisor.Capability(turn);
		var tool = mock(ToolCallback.class);
		var definition = ToolDefinition.builder().name("modify").description("modify source").inputSchema("{}").build();
		when(tool.getToolDefinition()).thenReturn(definition);
		var tools = new ArrayList<ToolCallback>(List.of(tool));
		var options = ToolCallingChatOptions.builder().model("gpt-6.1-sol").temperature(0.3)
				.toolCallbacks(tools).build();
		var context = Map.<String, Object>of(LessonRefinementAdvisor.KEY, capability, ToolContextKeys.TURN_CONTEXT, turn);
		var input = new ChatClientRequest(new Prompt("refine", options), context);
		var chain = mock(CallAdvisorChain.class);
		var seen = new AtomicReference<ChatClientRequest>();
		when(chain.nextCall(any())).thenAnswer(invocation -> { seen.set(invocation.getArgument(0)); return null; });
		new LessonRefinementAdvisor(0).adviseCall(input, chain);
		var stripped = (ToolCallingChatOptions) seen.get().prompt().getOptions();
		assertThat(stripped.getToolCallbacks()).isEmpty();
		assertThat(stripped.getModel()).isEqualTo("gpt-6.1-sol");
		assertThat(stripped.getTemperature()).isEqualTo(0.3);
		assertThat(options.getToolCallbacks()).containsExactly(tool);
		assertThat(new LessonAdvisor(f.service).prepare(input)).isSameAs(input);
		var invoked = new AtomicBoolean();
		var invocation = new ToolInterceptor.ToolInvocation(definition, "{}", new ToolContext(stripped.getToolContext()));
		assertThatThrownBy(() -> new LessonRefinementAdvisor.EffectGuard().intercept(invocation, call -> {
			invoked.set(true); return "";
		})).isInstanceOf(IllegalStateException.class);
		assertThat(invoked).isFalse();
	}

	@Test void globalReadersCannotRollbackToPrivateHistoricalRevisions() throws Exception {
		var f = new LessonFixture(root);
		var local = f.accepted("parser private historical content");
		f.service.update(local.id(), 2, "verified-goal", f.content("parser shareable content"), f.actor);
		var global = f.service.promote(local.id(), 3, Scope.GLOBAL, f.actor);
		var other = new Actor("other", root.toRealPath().toString());
		assertThat(f.service.history(global.id(), other)).hasSize(1);
		assertThatThrownBy(() -> f.service.rollback(global.id(), 4, 2, other))
				.isInstanceOf(java.io.IOException.class);
	}
}
