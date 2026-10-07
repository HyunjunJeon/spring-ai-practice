package com.example.miniagent.core.change;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/** No journal repair, auto-apply, checkpoint completion or live-source writes. */
public final class RecoveryScanner {
	private final ChangeStore store;
	private final PathPolicy policy;

	public RecoveryScanner(ChangeStore store, PathPolicy policy) {
		this.store = java.util.Objects.requireNonNull(store);
		this.policy = java.util.Objects.requireNonNull(policy);
		if (!store.workspace().equals(policy.root())) throw new IllegalArgumentException("Workspace policy mismatch");
	}

	public ConflictReport scan(UUID id, ChangeOwner owner, String digest) throws IOException {
		EngineMutation.interrupted();
		ChangeStore.Lease lease = this.store.acquire();
		try {
			var view = lease.read(); // A damaged journal still exposes its validated prefix.
			return classify(this.policy, view, EngineMutation.owned(view, id, owner, digest));
		}
		finally { EngineMutation.close(lease); }
	}

	static ConflictReport classify(PathPolicy policy, ChangeStore.View view, ChangeStore.Snapshot snapshot) throws IOException {
		var checkpoints = new HashMap<String, Checkpoint>();
		for (var checkpoint : snapshot.checkpoints()) checkpoints.put(checkpoint.before().relativePath(), checkpoint);
		var entries = new ArrayList<ConflictReport.Entry>();
		var printer = new Fingerprinter(policy);
		for (Diff diff : snapshot.changeSet().diffs()) {
			EngineMutation.interrupted();
			Checkpoint checkpoint = checkpoints.get(diff.relativePath());
			Fingerprint current = null;
			ConflictReport.Position position = ConflictReport.Position.OTHER;
			ConflictReport.Code code;
			try {
				policy.resolveMutation(diff.relativePath());
				current = printer.fingerprint(diff.relativePath());
				position = current.equals(diff.before()) ? ConflictReport.Position.BEFORE
						: current.equals(diff.after()) ? ConflictReport.Position.AFTER : ConflictReport.Position.OTHER;
				code = checkpoint == null && position != ConflictReport.Position.BEFORE
						? ConflictReport.Code.UNJOURNALED_EFFECT
						: position == ConflictReport.Position.OTHER ? ConflictReport.Code.HUMAN_EDIT : ConflictReport.Code.NONE;
			}
			catch (PathPolicy.Violation ex) {
				if (ex.code() == PathPolicy.Code.LIMIT || ex.code() == PathPolicy.Code.UNSUPPORTED_FILESYSTEM) throw ex;
				code = ConflictReport.Code.UNSAFE_PATH;
			}
			entries.add(new ConflictReport.Entry(diff.relativePath(), checkpoint == null ? null : checkpoint.checkpointId(),
					checkpoint == null ? null : checkpoint.state(), diff.before(), diff.after(), current, position, code));
		}
		return new ConflictReport(snapshot.changeSet().changeSetId(), view.journalState(), snapshot.changeSet().status(), entries);
	}
}
