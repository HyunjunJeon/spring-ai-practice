package com.example.miniagent.core;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * {@code /plan} 은 답변 본문의 JSON 을 파싱하지 않는다.
 * 그 턴에만 {@code submit_plan} 도구를 붙이고, 모델이 그 도구를 호출해야 계획이 된다.
 * 도구 인자는 제공자 스키마라서 마크다운 본문보다 형식이 고정된다.
 */
public final class StructuredPlan {

	private StructuredPlan() {
	}

	public record Plan(String goal, List<String> steps) {
	}

	public static Plan request(ChatModel model, String goal) {
		PlanTool tool = new PlanTool();
		var callbacks = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks();
		String text = ChatClient.builder(model)
			.defaultToolCallbacks(callbacks)
			.build()
			.prompt()
			.user("""
					Call the submit_plan tool exactly once. That tool is the only way to answer.
					Do not write a heading, a list, or any other assistant text.
					Use the goal below as the goal argument, and 2 to 5 concrete steps.
					Goal: %s""".formatted(goal))
			.call()
			.content();
		if (tool.plan == null) {
			throw new IllegalStateException("모델이 submit_plan 도구를 호출하지 않았습니다.");
		}
		if (text == null) {
			throw new IllegalStateException("submit_plan 결과가 비어 있습니다.");
		}
		return tool.plan;
	}

	public static String render(Plan plan) {
		StringBuilder text = new StringBuilder();
		text.append(plan.goal()).append('\n');
		List<String> steps = (plan.steps() != null) ? plan.steps() : List.of();
		for (int i = 0; i < steps.size(); i++) {
			text.append(i + 1).append(". ").append(steps.get(i)).append('\n');
		}
		return text.toString().stripTrailing();
	}

	static final class PlanTool {

		private Plan plan;

		@Tool(name = "submit_plan", returnDirect = true, description = """
				Submit the plan. Call this once. The goal and steps are the whole answer.
				Do not also write the plan as assistant text.""")
		public String submitPlan(
				@ToolParam(description = "The user's goal, kept as a single sentence") String goal,
				@ToolParam(description = "Concrete steps, usually 2 to 5") List<String> steps) {
			if (goal == null || goal.isBlank()) {
				throw new IllegalArgumentException("goal is required");
			}
			List<String> cleaned = (steps == null) ? List.of() : steps.stream()
				.filter(step -> step != null && !step.isBlank())
				.map(String::strip)
				.toList();
			if (cleaned.isEmpty() || cleaned.size() > 8) {
				throw new IllegalArgumentException("steps must contain 1 to 8 non-blank strings");
			}
			this.plan = new Plan(goal.strip(), cleaned);
			return render(this.plan);
		}

	}

}
