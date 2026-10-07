package com.example.miniagent.core.change;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import com.example.miniagent.core.model.TurnContext;

/** Publishes full immutable proposals, superseding only unapplied history. */
public final class ChangeSetRecorder {
	private final ChangeStore store;
	private final ChangeOwner owner;
	private final TurnContext turn;
	private final Clock clock;
	private Map<String, Fingerprint> last;

	public ChangeSetRecorder(ChangeStore store, ChangeOwner owner, TurnContext turn, Clock clock,
			Map<String, Fingerprint> baseline) {
		this.store = store;
		this.owner = owner;
		this.turn = turn;
		this.clock = clock;
		this.last = baseline;
	}

	static void supersede(ChangeStore.Lease lease, ChangeOwner owner) throws IOException {
		for (var snapshot : lease.read().changes()) {
			var change = snapshot.changeSet();
			if (change.owner().runId().equals(owner.runId())
					&& (change.status() == ChangeStatus.PENDING_APPROVAL || change.status() == ChangeStatus.APPROVED)) {
				lease.transition(change.changeSetId(), change.owner(), snapshot.digest(), ChangeStatus.SUPERSEDED);
			}
		}
	}

	public synchronized void record(PathPolicy stage, Map<String, Fingerprinter.Observation> originals,
			Map<String, Fingerprint> baseline, boolean sourceChanged) throws IOException {
		var reader = new Fingerprinter(stage);
		Map<String, Fingerprint> proposed = new java.util.TreeMap<>(reader.snapshot());
		long total = 0;
		for (var value : proposed.values()) {
			total += value.size();
			if (total > 256L * 1024 * 1024)
				throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, value.relativePath());
		}
		for (var entry : baseline.entrySet()) {
			if (entry.getValue().kind() == EntryKind.SYMBOLIC_LINK) {
				if (proposed.containsKey(entry.getKey()))
					throw new PathPolicy.Violation(PathPolicy.Code.SYMLINK, entry.getKey());
				proposed.put(entry.getKey(), entry.getValue());
			}
		}
		if (proposed.equals(this.last)) {
			if (sourceChanged) {
				try (var lease = this.store.acquireStaging()) { stale(lease); }
			}
			return;
		}
		var paths = new TreeSet<>(baseline.keySet());
		paths.addAll(proposed.keySet());
		var diffs = new ArrayList<Diff>();
		var renderer = new DiffRenderer(stage);
		for (String path : paths) {
			var before = baseline.getOrDefault(path, Fingerprint.missing(path));
			var after = proposed.getOrDefault(path, Fingerprint.missing(path));
			if (!before.equals(after)) {
				if (path.equals(".mini-agent") || path.startsWith(".mini-agent/"))
					throw new PathPolicy.Violation(PathPolicy.Code.EXCLUDED, path);
				byte[] old = originals.containsKey(path) ? originals.get(path).bytes() : new byte[0];
				byte[] next = after.kind() == EntryKind.MISSING ? new byte[0] : reader.read(path).bytes();
				diffs.add(renderer.render(before, old, after, next));
			}
		}
		try (var lease = this.store.acquireStaging()) {
			// Force the retained stage before publishing its proposal. Never follow links or
			// open excluded paths; the filtered, bounded snapshot above is the authority.
			for (var entry : proposed.values()) {
				if (entry.kind() == EntryKind.REGULAR_FILE) {
					try (var channel = java.nio.channels.FileChannel.open(stage.resolve(entry.relativePath()),
							java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
						channel.force(true);
					}
				}
				else if (entry.kind() == EntryKind.DIRECTORY)
					AtomicFiles.forceDirectory(stage.resolve(entry.relativePath()));
			}
			AtomicFiles.forceDirectory(stage.root());
			// A reverted stage supersedes its previous proposal but does not invent an empty change.
			if (!diffs.isEmpty()) {
				lease.propose(ChangeSet.propose(UUID.randomUUID(), this.owner, this.store.workspace().toString(),
						this.turn.binding().ref(), List.of(this.turn.binding().bindingId()), baseline, proposed,
						diffs, this.clock.instant()));
			}
			for (var snapshot : lease.read().changes()) {
				var c = snapshot.changeSet();
				if (c.owner().equals(this.owner) && (c.status() == ChangeStatus.PENDING_APPROVAL
						|| c.status() == ChangeStatus.APPROVED) && !c.proposed().equals(proposed))
					lease.transition(c.changeSetId(), c.owner(), snapshot.digest(), ChangeStatus.SUPERSEDED);
			}
			if (sourceChanged) stale(lease);
		}
		this.last = proposed;
	}

	private void stale(ChangeStore.Lease lease) throws IOException {
		for (var snapshot : lease.read().changes()) {
			var c = snapshot.changeSet();
			if (c.owner().equals(this.owner) && (c.status() == ChangeStatus.PENDING_APPROVAL
					|| c.status() == ChangeStatus.APPROVED))
				lease.transition(c.changeSetId(), c.owner(), snapshot.digest(), ChangeStatus.STALE);
		}
	}
}
