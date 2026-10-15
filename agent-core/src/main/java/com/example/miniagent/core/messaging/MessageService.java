package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.example.miniagent.core.delegation.DefaultDelegationRuntime;
import com.example.miniagent.core.run.RunContext;
import com.example.miniagent.core.run.RunState;
import static com.example.miniagent.core.messaging.MessageStore.*;

/**
 * Scoped at-least-once submissions, idempotent IDs, never automatic execution retry.
 * Lost RUNNING claims are UNCERTAIN and cannot execute again, even in another instance.
 */
public final class MessageService {
	private final MessageStore store;
	private final DefaultDelegationRuntime runtime;
	private final String instance = UUID.randomUUID().toString();
	private final Set<String> active = ConcurrentHashMap.newKeySet();
	public MessageService(MessageStore store, DefaultDelegationRuntime runtime) {
		this.store = store; this.runtime = runtime;
	}
	private static Key key(DefaultDelegationRuntime.MessageTarget t, String plan, String child) {
		return new Key(t.parentRun().value(), plan, child);
	}
	public Receipt send(RunContext actor, String plan, String child, String id, long expectedVersion, String body)
			throws IOException {
		var target = runtime.messageTarget(actor, plan, child);
		Key key = key(target, plan, child);
		var queued = new Receipt(id, target.sender(), body, State.QUEUED, target.childRun().value(), null, null, null);
		String claim = instance + ":" + UUID.randomUUID();
		active.add(claim);
		try {
			var claimed = store.transact(threads -> {
				var box = find(threads, key);
				var prior = box.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
				if (prior != null) {
					if (!prior.sender().equals(queued.sender()) || !prior.body().equals(body))
						throw new IOException("Idempotency ID reused with different content/sender");
					return visible(prior);
				}
				if (box.version() != expectedVersion) throw new IOException("Stale mailbox version");
				if (box.entries().size() >= MAX_ENTRIES) throw new IOException("Mailbox entry capacity");
				if (target.sender().equals("parent") && box.entries().stream()
						.anyMatch(e -> e.state() == State.RUNNING || e.state() == State.UNCERTAIN))
					throw new IOException("Thread busy or uncertain; no execution admitted");
				var entry = target.sender().equals("parent")
					? new Receipt(id, queued.sender(), body, State.RUNNING, queued.originalRun(), null, null, claim) : queued;
				var entries = new ArrayList<>(box.entries()); entries.add(entry);
				replace(threads, box, entries);
				return entry;
			});
			if (!claim.equals(claimed.claim())) return claimed;
			Receipt finished;
			try {
				var result = runtime.followUp(actor, plan, child, body);
				State state = result.record().state() == RunState.COMPLETED && result.record().uncertainTools().isEmpty()
					? State.DELIVERED : result.record().state() == RunState.CANCELLED ? State.FAILED : State.UNCERTAIN;
				String response = result.reply() == null ? null : result.reply().text();
				boolean responseTruncated = response != null && response.length() > MAX_BODY;
				if (responseTruncated) {
					int end = MAX_BODY;
					if (Character.isHighSurrogate(response.charAt(end - 1)) && Character.isLowSurrogate(response.charAt(end)))
						end--;
					response = response.substring(0, end);
				}
				finished = new Receipt(id, queued.sender(), body, state, queued.originalRun(),
					result.record().spec().runId().value(), response, responseTruncated, claim);
			}
			catch (RuntimeException ex) {
				var after = runtime.messageTarget(actor, plan, child);
				boolean started = !after.childRun().equals(target.childRun());
				finished = new Receipt(id, queued.sender(), body, started ? State.UNCERTAIN : State.BLOCKED,
					queued.originalRun(), started ? after.childRun().value() : null, null, claim);
			}
			final Receipt result = finished;
			return store.transact(threads -> {
				var box = find(threads, key);
				var entries = new ArrayList<>(box.entries());
				int index = -1;
				for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) index = i;
				if (index < 0 || !claim.equals(entries.get(index).claim()) || entries.get(index).state() != State.RUNNING)
					throw new IOException("Mailbox claim changed");
				entries.set(index, result); replace(threads, box, entries); return result;
			});
		}
		finally { active.remove(claim); }
	}
	public Page read(RunContext actor, String plan, String child, int offset) throws IOException {
		var target = runtime.messageTarget(actor, plan, child);
		var page = store.read(key(target, plan, child), offset);
		return new Page(page.version(), page.nextOffset(), page.entries().stream().map(this::visible).toList());
	}
	public RunContext operator(String originalConversation, String plan) {
		return runtime.messageOperator(originalConversation, plan);
	}
	/** Read-only console access survives a terminal parent or application restart. */
	public Page archive(String conversation, java.nio.file.Path workspace,
			com.example.miniagent.core.run.RunStore runs, String parentRun, String plan, String child, int offset)
			throws IOException {
		var parent = runs.read(new com.example.miniagent.core.run.RunId(parentRun));
		if (parent.spec().childId() != null || !parent.spec().parentConversationId().equals(conversation)
				|| !parent.spec().cwd().equals(workspace.toRealPath().toString()))
			throw new IllegalArgumentException("Foreign message archive");
		var page = store.read(new Key(parentRun, plan, child), offset);
		return new Page(page.version(), page.nextOffset(), page.entries().stream().map(this::visible).toList());
	}
	private Receipt visible(Receipt entry) {
		if (entry.state() != State.RUNNING || active.contains(entry.claim())) return entry;
		return new Receipt(entry.id(), entry.sender(), entry.body(), State.UNCERTAIN, entry.originalRun(),
			entry.executionRun(), null, entry.claim());
	}
}
