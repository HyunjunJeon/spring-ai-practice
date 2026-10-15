package com.example.miniagent.app.machine;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Shared condition evaluated before either runner or the default console is created. */
public final class MachineMode implements Condition {
	public record Output(java.io.PrintStream stream) { }

	public static boolean enabled(org.springframework.core.env.Environment environment) {
		String ui = environment.getProperty("agent.ui", "");
		return ui.equals("json") || ui.equals("rpc");
	}

	static boolean enabled(ConditionContext context) {
		return enabled(context.getEnvironment());
	}
	@Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return enabled(context);
	}
	public static final class Human implements Condition {
		@Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return !enabled(context);
		}
	}
}
