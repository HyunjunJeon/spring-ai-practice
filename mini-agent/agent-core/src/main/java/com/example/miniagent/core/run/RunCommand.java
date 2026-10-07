package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.TraceEvent;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.approval.ApprovalRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/** Operator-only controls. No command is registered as a model tool. */
public final class RunCommand implements SlashCommand {
	private final RunExecutor executor;
	private final RunStore runs;
	private final RunRequestStore requests;
	private final ApprovalBroker broker;
	private final RunProperties properties;
	private final Path cwd;
	private final JournalResumeContributor evidence;
	private final ApplicationEventPublisher events;
	private final String operator = "console-" + UUID.randomUUID();
	private final ThreadLocal<RunId> dispatched = new ThreadLocal<>();

	public RunCommand(RunExecutor executor, RunStore runs, RunRequestStore requests, ApprovalBroker broker,
			RunProperties properties, Path cwd, JournalResumeContributor evidence, ApplicationEventPublisher events) {
		this.executor = executor; this.runs = runs; this.requests = requests; this.broker = broker;
		this.properties = properties; this.cwd = cwd; this.evidence = evidence; this.events = events;
	}
	@Override public String name() { return "run"; }
	@Override public String description() {
		return "start <goal> | show/status/cancel/inspect-drift <id> | approve/reject/defer <id> <request UUID>"
				+ " | resume <id> [--resolve <attempt> <key> READ_RETRY|MUTATING_RETRY|SKIP <evidence-id>]"
				+ " [--ack-drift <expected-sha256> <actual-sha256> <evidence-id>]";
	}
	@Override public void execute(String args, CommandContext context) { executeWithStatus(args, context); }

	public static boolean isControlInput(String input) {
		String[] parts = input.strip().split("\\s+");
		return parts[0].equals("/runs") || parts.length > 1 && parts[0].equals("/run")
				&& List.of("show", "status", "cancel", "approve", "reject", "defer", "inspect-drift").contains(parts[1]);
	}

	@Override public boolean executeWithStatus(String args, CommandContext context) {
		String[] parts = args.strip().split("\\s+");
		try {
			if (parts[0].equals("start")) {
				String[] start = args.strip().split("\\s+", 2);
				if (start.length != 2 || start[1].isBlank()) throw new IllegalArgumentException("Missing goal");
				return result(this.executor.start(start[1], context.session().id(), context.agent().selectedModel(),
						this.cwd, this.properties.budget()), context.console());
			}
			if (parts.length < 2) throw new IllegalArgumentException("Missing run ID");
			RunId id = new RunId(parts[1]);
			if (parts[0].equals("resume")) {
				var record = this.runs.read(id);
				var stored = this.requests.load(record.spec());
				return result(this.executor.resume(id, stored.selection(), Path.of(record.spec().cwd()),
						options(parts)), context.console());
			}
			if (List.of("approve", "reject", "defer").contains(parts[0])) {
				if (parts.length != 3) throw new IllegalArgumentException("Expected request UUID");
				UUID requestId = UUID.fromString(parts[2]);
				// Restore only authoritative identities, never a request assembled from user/model fields.
				if (this.broker.pending().stream().noneMatch(r -> r.runId().equals(id)))
					this.broker.restore(id, this.runs.export(id).eventsLog());
				ApprovalRequest request = this.broker.pending().stream()
						.filter(r -> r.runId().equals(id) && r.requestId().equals(requestId)).findFirst()
						.orElseThrow(() -> new IllegalArgumentException("No matching pending approval"));
				ApprovalDecision decision = switch (parts[0]) {
					case "approve" -> ApprovalDecision.APPROVED;
					case "reject" -> ApprovalDecision.REJECTED;
					default -> ApprovalDecision.DEFERRED;
				};
				if (this.runs.read(id).state() == RunState.WAITING_APPROVAL) {
					observed(this.executor.resolveApproval(request, decision), context.console());
				}
				else if (!this.broker.respond(request, decision)) throw new IllegalArgumentException("Stale approval");
				return decision != ApprovalDecision.DEFERRED;
			}
			if (parts.length != 2) throw new IllegalArgumentException("Unexpected arguments");
			switch (parts[0]) {
				case "show", "status" -> {
					RunRecord record = this.runs.read(id);
					summary(record, context.console());
					context.console().println(this.evidence.verifiedEvidence(record, ""));
					if (!record.state().terminal() && this.broker.pending().stream().noneMatch(r -> r.runId().equals(id)))
						this.broker.restore(id, record.reason() == RunEvent.Reason.PROCESS_LOST
								? "" : this.runs.export(id).eventsLog());
					this.broker.pending().stream().filter(r -> r.runId().equals(id))
							.forEach(r -> context.console().println("pending approval " + r.requestId()
									+ " attempt=" + r.attemptId() + " tool=" + r.toolCallKey()));
				}
				case "cancel" -> observed(this.executor.cancel(id), context.console());
				case "inspect-drift" -> {
					var difference = this.executor.inspectDrift(id);
					context.console().println("expected=" + difference.expectedDigest()
							+ " actual=" + difference.actualDigest());
				}
				default -> throw new IllegalArgumentException("Unknown run operation");
			}
			return true;
		}
		catch (IOException | RuntimeException ex) {
			RunId dispatchedId = this.dispatched.get();
			if (dispatchedId != null) {
				boolean interrupted = Thread.interrupted();
				try { observed(this.runs.read(dispatchedId), context.console()); }
				catch (IOException reporting) { ex.addSuppressed(reporting); }
				finally { if (interrupted) Thread.currentThread().interrupt(); }
			}
			context.console().error(failure(ex));
			context.console().info("Usage: /run " + description());
			return false;
		}
		finally { this.dispatched.remove(); }
	}

	private RunExecutor.ResumeOptions options(String[] parts) {
		return resumeOptions(parts, this.operator);
	}

	public static RunExecutor.ResumeOptions resumeOptions(String[] parts, String operator) {
		List<RunExecutor.ToolResolutionInput> resolutions = new ArrayList<>();
		DriftGate.Acknowledgement drift = null;
		for (int i = 2; i < parts.length;) {
			if (parts[i].equals("--resolve") && i + 4 < parts.length) {
				resolutions.add(new RunExecutor.ToolResolutionInput(parts[i + 1], parts[i + 2],
						RunEvent.Resolution.valueOf(parts[i + 3]), operator, parts[i + 4]));
				i += 5;
			}
			else if (parts[i].equals("--ack-drift") && i + 3 < parts.length && drift == null) {
				drift = new DriftGate.Acknowledgement(parts[i + 1], parts[i + 2], operator, parts[i + 3]);
				i += 4;
			}
			else throw new IllegalArgumentException("Invalid resume option");
		}
		return new RunExecutor.ResumeOptions(resolutions, drift);
	}

	private boolean result(RunExecutor.Result result, AgentConsole console) {
		observed(result.record(), console);
		if (result.reply() != null) console.reply(result.reply().text());
		return result.record().state() == RunState.COMPLETED;
	}
	private void observed(RunRecord record, AgentConsole console) {
		summary(record, console);
		this.broker.pending().stream().filter(r -> r.runId().equals(record.spec().runId()))
				.forEach(r -> console.println("pending approval " + r.requestId()
						+ " attempt=" + r.attemptId() + " tool=" + r.toolCallKey()));
		publish(record);
	}
	private void publish(RunRecord record) {
		this.events.publishEvent(new TraceEvent.RunStateChanged(record.spec().runId().value(),
				record.spec().conversationId(), record.attempts().isEmpty() ? null
						: record.attempts().getLast().attemptId(), record.state(), record.spec().model()));
	}
	/** ModelBound proves dispatch entered; do not invent executor-internal transitions. */
	@EventListener public void onBound(TraceEvent.ModelBound event) {
		if (event.runId() == null) return;
		RunContext.current()
				.filter(context -> context.spec().runId().value().equals(event.runId())
						&& context.attemptId().equals(event.attemptId()))
				.ifPresent(context -> {
					this.dispatched.set(context.spec().runId());
					publish(context.writer().record());
				});
	}
	static void summary(RunRecord record, AgentConsole console) {
		var spec = record.spec(); var usage = record.usage();
		console.println("run=" + spec.runId().value() + " conversation=" + spec.conversationId()
				+ " parent=" + spec.parentConversationId() + " attempt="
				+ (record.attempts().isEmpty() ? "-" : record.attempts().getLast().attemptId())
				+ " state=" + record.state() + " reason=" + record.reason()
				+ " model=" + spec.model().provider() + "/" + spec.model().model()
				+ " tokens=" + usage.knownTokens() + " unknown=" + usage.tokensUnknown()
				+ " overshoot=" + usage.overshootTokens() + " tools=" + usage.toolCalls()
				+ " activeMillis=" + usage.activeMillis());
		for (var tool : record.uncertainTools())
			console.println("UNKNOWN attempt=" + tool.attemptId() + " key=" + tool.toolCallKey()
					+ " tool=" + tool.toolName() + " effect=" + tool.effect());
	}
	static String failure(Exception ex) {
		String code = ex instanceof RunStoreException store ? store.code().name()
				: ex instanceof RunExecutor.Failure execution ? execution.code().name() : ex.getClass().getSimpleName();
		Throwable cause = ex.getCause();
		return code + ": " + ex.getMessage() + (cause == null ? "" : " cause=" + cause.getClass().getSimpleName()
				+ ": " + cause.getMessage());
	}
}
