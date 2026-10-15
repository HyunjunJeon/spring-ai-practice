package com.example.miniagent.core.delegation.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import com.example.miniagent.core.ControlInput;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.delegation.DelegationResult;
import com.example.miniagent.core.execution.RunExecutor;
import com.example.miniagent.core.run.RunProperties;
import com.example.miniagent.core.run.RunState;

/** Authenticated console controls; model callbacks cannot fabricate console identity. */
public final class DelegateCommand implements SlashCommand {
	private final Supplier<DelegationController> controller;
	private final Supplier<RunExecutor> executor;
	private final RunProperties properties;
	private final Path cwd;
	private final String operator = "console-" + UUID.randomUUID();
	public DelegateCommand(Supplier<DelegationController> controller, Supplier<RunExecutor> executor,
			RunProperties properties, Path cwd) {
		this.controller = controller; this.executor = executor; this.properties = properties;
		try { this.cwd = cwd.toRealPath(); }
		catch (IOException ex) { throw new java.io.UncheckedIOException(ex); }
	}
	@Override public String name() { return "delegate"; }
	@Override public String description() {
		return "start <plan JSON> | list | show/cancel <plan> | approve/reject/defer <plan> <request UUID>"
				+ " | resume <plan> <child> [run resume options] | apply <plan> <child> <proposal UUID>"
				+ " | recover <child run> [run resume options] -- <one-child plan JSON>";
	}
	public static boolean isControlInput(String input) { return ControlInput.isDelegateControl(input); }
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }
	@Override public boolean executeWithStatus(String args, CommandContext context) {
		var parts = args.strip().split("\\s+");
		try {
			var service = this.controller.get();
			String conversation = context.session().id();
			if (parts[0].equals("recover")) {
				int separator = args.indexOf(" -- ");
				if (separator < 0) throw new IllegalArgumentException("Expected -- before exact one-child plan JSON");
				var prefix = args.substring(0, separator).strip().split("\\s+");
				if (prefix.length < 2) throw new IllegalArgumentException("Missing child run ID");
				var plan = DelegationInput.parse(args.substring(separator + 4)).plan(this.cwd);
				if (plan.children().size() != 1) throw new IllegalArgumentException("Recover accepts one exact child, not a reconstructed DAG");
				var result = service.recover(new com.example.miniagent.core.run.RunId(prefix[1]), plan.planId(),
						plan.children().getFirst(), conversation, DelegationInput.authority(context.agent(), this.cwd),
						com.example.miniagent.core.run.command.RunCommand.resumeOptions(prefix, this.operator));
				context.console().println("recovered execution run=" + result.record().spec().runId().value()
						+ " state=" + result.record().state());
				if (result.reply() != null) context.console().reply(result.reply().text());
				return result.record().state() == RunState.COMPLETED;
			}
			if (parts[0].equals("start")) {
				var start = args.strip().split("\\s+", 2);
				if (start.length != 2) throw new IllegalArgumentException("Missing plan JSON");
				var input = DelegationInput.parse(start[1]);
				var result = service.executeOperator(this.executor.get(), conversation, context.agent().selectedModel(),
						this.cwd, input.plan(this.cwd), DelegationInput.authority(context.agent(), this.cwd),
						this.properties.budget());
				context.console().println("parent run=" + result.record().spec().runId().value()
						+ " state=" + result.record().state());
				return result.value() != null && result.record().state() == RunState.COMPLETED
						&& print(result.value(), context);
			}
			if (parts[0].equals("list") && parts.length == 1) {
				service.list(conversation).forEach(view -> show(view, context));
				return true;
			}
			if (parts.length < 2) throw new IllegalArgumentException("Missing plan ID");
			switch (parts[0]) {
				case "show" -> { exact(parts, 2); show(service.show(conversation, parts[1]), context); }
				case "cancel" -> {
					exact(parts, 2);
					if (!service.cancel(conversation, parts[1])) throw new IllegalArgumentException("No active owned plan");
					context.console().println("CANCELLED " + parts[1]);
				}
				case "approve", "reject", "defer" -> {
					exact(parts, 3);
					var view = service.show(conversation, parts[1]);
					if (!(view.outcome() instanceof DefaultDelegationRuntime.Pending pending))
						throw new IllegalArgumentException("No retained pending approval");
					UUID id = UUID.fromString(parts[2]);
					var request = pending.approvals().stream().filter(r -> r.requestId().equals(id))
							.findFirst().orElseThrow(() -> new IllegalArgumentException("Foreign/stale request"));
					var decision = switch (parts[0]) {
						case "approve" -> ApprovalDecision.APPROVED;
						case "reject" -> ApprovalDecision.REJECTED;
						default -> ApprovalDecision.DEFERRED;
					};
					var record = service.resolve(conversation, parts[1], request, decision);
					context.console().println("child run=" + record.spec().runId().value() + " state=" + record.state());
					return decision != ApprovalDecision.DEFERRED;
				}
				case "resume" -> {
					if (parts.length < 3) throw new IllegalArgumentException("Missing child ID");
					return print(service.resume(conversation, parts[1], parts[2],
							com.example.miniagent.core.run.command.RunCommand.resumeOptions(
									java.util.Arrays.copyOfRange(parts, 1, parts.length), this.operator)), context);
				}
				case "apply" -> {
					exact(parts, 4);
					var view = service.show(conversation, parts[1]);
					if (view.parent() == null) throw new IllegalArgumentException("Recovered child has no current managed parent apply authority");
					return service.apply(view.parent(), parts[1], parts[2], UUID.fromString(parts[3]), context);
				}
				default -> throw new IllegalArgumentException("Unknown delegation operation");
			}
			return true;
		}
		catch (IOException | RuntimeException ex) {
			context.console().error(ex.getClass().getSimpleName() + ": " + ex.getMessage());
			context.console().info("Usage: /delegate " + description());
			return false;
		}
	}
	private static void exact(String[] parts, int length) {
		if (parts.length != length) throw new IllegalArgumentException("Unexpected arguments");
	}
	private static void show(DelegationController.View view, CommandContext context) {
		context.console().println("plan=" + view.plan().planId() + " busy=" + view.busy());
		if (view.outcome() != null) context.console().println(DelegationInput.outcome(view.outcome()));
	}
	private static boolean print(DefaultDelegationRuntime.Outcome outcome, CommandContext context) {
		context.console().println(DelegationInput.outcome(outcome));
		return outcome instanceof DefaultDelegationRuntime.Completed completed
				&& completed.result().status() == DelegationResult.Status.COMPLETE;
	}
}
