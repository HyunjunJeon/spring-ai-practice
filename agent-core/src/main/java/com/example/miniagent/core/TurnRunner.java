package com.example.miniagent.core;

import java.util.Map;
import com.example.miniagent.core.model.TurnContext;

/** Captures explicit attempt authority at the existing RunExecutor/Agent boundary. */
@FunctionalInterface
public interface TurnRunner {
	Map<String, Object> capture(TurnContext context);
}
