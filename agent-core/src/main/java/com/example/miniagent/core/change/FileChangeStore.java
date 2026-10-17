package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** Workspace-scoped, private journal authority with atomic manifest projections. */
public final class FileChangeStore implements ChangeStore {
	// Never open a second descriptor for an in-JVM owned POSIX lock file.
	private static final Set<Path> JVM_LEASES = new HashSet<>();
	private final Path workspace;
	private final Path directory;
	private final String hash;
	private final ChangeProperties limits;
	private final PathPolicy policy;
	private final ChangeJournal journal;
	private final BlobStore blobs;

	public FileChangeStore(Path agentHome, Path workspace) throws Failure {
		this(agentHome, workspace, ChangeProperties.defaults());
	}

	public FileChangeStore(Path agentHome, Path workspace, ChangeProperties limits) throws Failure {
		this.limits = java.util.Objects.requireNonNull(limits);
		try {
			this.policy = new PathPolicy(workspace, agentHome, List.of());
			this.workspace = this.policy.root();
			Path home = this.policy.agentHome();
			if (home.startsWith(this.workspace))
				throw new Failure(IDENTITY_MISMATCH, "Change state must be outside the workspace");
			// Create missing ancestors privately without accepting symlink ancestors.
			ensureHome(home);
			this.hash = Fingerprinter.sha256(("workspace-v1\0" + this.workspace).getBytes(UTF_8));
			Path changes = home.resolve("changes");
			AtomicFiles.directory(changes);
			this.directory = changes.resolve(this.hash);
			AtomicFiles.directory(this.directory);
			this.journal = new ChangeJournal(this.directory.resolve("journal.log"),
					limits.maxJournalBytes(), limits.maxManifestBytes());
			this.blobs = new BlobStore(this.directory.resolve("blobs"), limits.maxBlobBytes());
		}
		catch (IOException | UnsupportedOperationException ex) { throw AtomicFiles.failure(ex); }
	}

	private static void ensureHome(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			ensureHome(path.getParent());
			AtomicFiles.directory(path);
		}
		else {
			AtomicFiles.checkDirectory(path);
		}
	}

	@Override public Path workspace() { return this.workspace; }
	@Override public Path directory() { return this.directory; }
	@Override public String workspaceHash() { return this.hash; }

	@Override public View read() throws Failure {
		try (Lease lease = acquire()) { return lease.read(); }
	}

	@Override public Lease acquire() throws Failure {
		synchronized (JVM_LEASES) {
			if (JVM_LEASES.contains(this.workspace)) throw new Failure(LOCKED, "Workspace already leased");
			FileChannel channel = null;
			FileLock lock = null;
			try {
				AtomicFiles.checkDirectory(this.directory);
				Path path = this.directory.resolve("workspace.lock");
				AtomicFiles.createFile(path);
				channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
				try { lock = channel.tryLock(); }
				catch (OverlappingFileLockException ex) { throw new Failure(LOCKED, "Workspace already leased", ex); }
				if (lock == null) throw new Failure(LOCKED, "Workspace leased by another process");
				initialize();
				JVM_LEASES.add(this.workspace);
				return new WorkspaceLease(channel, lock);
			}
			catch (IOException | UnsupportedOperationException ex) {
				if (lock != null) try { lock.release(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
				if (channel != null) try { channel.close(); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
				throw AtomicFiles.failure(ex);
			}
		}
	}

	@Override public Lease acquireStaging() throws Failure {
		synchronized (JVM_LEASES) {
			while (JVM_LEASES.contains(this.workspace)) {
				try { JVM_LEASES.wait(); }
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new Failure(LOCKED, "Interrupted while awaiting stage journal lease", ex);
				}
			}
			return acquire();
		}
	}

	private record Identity(int schemaVersion, String workspaceHash, String cwd) {
		private Identity {
			Fingerprint.requireDigest(workspaceHash);
			VerificationEvidence.requireCwd(cwd);
		}
	}
	private record Manifest(int schemaVersion, String workspaceHash, Snapshot snapshot) {
		private Manifest { Fingerprint.requireDigest(workspaceHash); java.util.Objects.requireNonNull(snapshot); }
	}

	private void initialize() throws IOException {
		Path identity = this.directory.resolve("workspace.json");
		if (!Files.exists(identity, LinkOption.NOFOLLOW_LINKS)) {
			if (!holdsOnly(Set.of("workspace.lock")))
				throw new Failure(CORRUPT, "Incomplete workspace initialization; bytes preserved");
			AtomicFiles.write(identity, ChangeJournal.JSON.writeValueAsBytes(
					new Identity(1, this.hash, this.workspace.toString())), this.limits.maxManifestBytes());
		}
		Identity stored = decode(AtomicFiles.read(identity, this.limits.maxManifestBytes()), Identity.class);
		if (!stored.workspaceHash().equals(this.hash) || !stored.cwd().equals(this.workspace.toString()))
			throw new Failure(IDENTITY_MISMATCH, "Workspace identity mismatch");
		Path journalFile = this.directory.resolve("journal.log");
		// A crash between workspace.json and journal.log leaves nothing else behind. A journal lost later stays refused.
		if (holdsOnly(Set.of("workspace.json", "workspace.lock"))) AtomicFiles.createFile(journalFile);
		AtomicFiles.checkFile(journalFile);
		for (String child : List.of("manifests", "blobs", "staging", "evidence"))
			AtomicFiles.directory(this.directory.resolve(child));
	}

	private boolean holdsOnly(Set<String> names) throws IOException {
		try (var paths = Files.list(this.directory)) {
			return paths.allMatch(p -> names.contains(p.getFileName().toString()));
		}
	}

	private static <T> T decode(byte[] bytes, Class<T> type) throws Failure {
		try {
			var node = ChangeJournal.JSON.readTree(bytes);
			if (node == null || !node.isObject() || !node.path("schemaVersion").isInt())
				throw new Failure(CORRUPT, "Missing storage schema");
			int schema = node.path("schemaVersion").intValue();
			if (schema > 1) throw new Failure(FUTURE_SCHEMA, "Future change schema");
			if (schema != 1) throw new Failure(CORRUPT, "Invalid change schema");
			return ChangeJournal.JSON.treeToValue(node, type);
		}
		catch (RuntimeException ex) { throw new Failure(CORRUPT, "Invalid change serialization", ex); }
	}

	private View load() throws Failure {
		ChangeJournal.Scan scan = this.journal.scan();
		Map<UUID, Snapshot> latest = new LinkedHashMap<>();
		Map<Long, Snapshot> history = new LinkedHashMap<>();
		ChangeJournal.State state = scan.state();
		for (Snapshot snapshot : scan.records()) {
			try {
				validate(latest.get(snapshot.changeSet().changeSetId()), snapshot);
				validateBlobs(snapshot);
			}
			catch (Failure ex) {
				if (ex.code() != CORRUPT && ex.code() != IDENTITY_MISMATCH && ex.code() != INVALID_TRANSITION) throw ex;
				state = ChangeJournal.State.CORRUPT;
				break;
			}
			catch (RuntimeException ex) {
				state = ChangeJournal.State.CORRUPT;
				break;
			}
			latest.put(snapshot.changeSet().changeSetId(), snapshot);
			history.put(snapshot.sequence(), snapshot);
		}
		// Projections may lag after a crash, but cannot invent or replace authority.
		if (state == ChangeJournal.State.CLEAN) {
			try (var paths = Files.list(this.directory.resolve("manifests"))) {
				var iterator = paths.iterator();
				int count = 0;
				while (iterator.hasNext()) {
					if (++count > 10000) throw new Failure(LIMIT, "Too many manifest directory entries");
					Path path = iterator.next();
					// Failed temp files are retained, never promoted to manifests.
					if (path.getFileName().toString().startsWith(".pending-")) continue;
					Manifest manifest = decode(AtomicFiles.read(path, this.limits.maxManifestBytes()), Manifest.class);
					if (!manifest.workspaceHash().equals(this.hash)
							|| !path.getFileName().toString().equals(manifest.snapshot().changeSet().changeSetId() + ".json")
							|| !manifest.snapshot().equals(history.get(manifest.snapshot().sequence())))
						throw new Failure(CORRUPT, "Manifest differs from authoritative journal");
				}
			}
			catch (Failure ex) {
				if (ex.code() == FUTURE_SCHEMA) state = ChangeJournal.State.FUTURE_SCHEMA;
				else if (ex.code() == CORRUPT) state = ChangeJournal.State.CORRUPT;
				else throw ex;
			}
			catch (IOException ex) { throw AtomicFiles.failure(ex); }
		}
		return new View(state, List.copyOf(latest.values()));
	}

	private void validate(Snapshot previous, Snapshot next) throws Failure {
		ChangeSet set = next.changeSet();
		if (!set.cwd().equals(this.workspace.toString())) throw new Failure(IDENTITY_MISMATCH, "Change cwd mismatch");
		for (String path : set.baseline().keySet()) validatePath(path);
		for (String path : set.proposed().keySet()) validatePath(path);
		if (previous == null) {
			if (set.status() != ChangeStatus.PENDING_APPROVAL || set.approval() != null
					|| !next.checkpoints().isEmpty() || !next.evidence().isEmpty())
				throw new Failure(INVALID_TRANSITION, "Initial record must be an unapproved proposal");
			return;
		}
		if (!previous.digest().equals(next.digest()) || !previous.changeSet().owner().equals(set.owner())
				|| !previous.changeSet().createdAt().equals(set.createdAt()))
			throw new Failure(IDENTITY_MISMATCH, "Immutable proposal changed");
		if (!previous.changeSet().equals(set)) {
			ChangeSet expected;
			try {
				expected = !java.util.Objects.equals(previous.changeSet().approval(), set.approval())
						? previous.changeSet().decide(set.approval()) : previous.changeSet().transition(set.status());
			}
			catch (RuntimeException ex) { throw new Failure(INVALID_TRANSITION, "Invalid change state update", ex); }
			if (!expected.equals(set) || !previous.checkpoints().equals(next.checkpoints())
					|| !previous.evidence().equals(next.evidence()))
				throw new Failure(INVALID_TRANSITION, "State update changed checkpoint/evidence");
		}
		if (!next.evidence().containsAll(previous.evidence()))
			throw new Failure(INVALID_TRANSITION, "Evidence removed or changed");
		Set<UUID> evidenceIds = new HashSet<>();
		for (VerificationEvidence evidence : next.evidence()) {
			match(set, evidence.changeSetId(), evidence.owner());
			if (!evidence.sourceCwd().equals(set.cwd()) || !evidenceIds.add(evidence.evidenceId()))
				throw new Failure(IDENTITY_MISMATCH, "Evidence identity mismatch");
		}
		Map<UUID, Checkpoint> old = new LinkedHashMap<>();
		for (Checkpoint checkpoint : previous.checkpoints()) old.put(checkpoint.checkpointId(), checkpoint);
		Set<UUID> checkpointIds = new HashSet<>();
		Set<String> checkpointPaths = new HashSet<>();
		for (Checkpoint checkpoint : next.checkpoints()) {
			match(set, checkpoint.changeSetId(), checkpoint.owner());
			String path = checkpoint.before().relativePath();
			if (!checkpointIds.add(checkpoint.checkpointId()) || !checkpointPaths.add(path)
					|| checkpoint.recordedAt().isBefore(set.createdAt())
					|| !checkpoint.before().equals(set.baseline().getOrDefault(path, Fingerprint.missing(path)))
					|| !checkpoint.after().equals(set.proposed().getOrDefault(path, Fingerprint.missing(path))))
				throw new Failure(IDENTITY_MISMATCH, "Checkpoint differs from proposal");
			Checkpoint prior = old.remove(checkpoint.checkpointId());
			if (prior == null) {
				if (checkpoint.state() != Checkpoint.State.PREPARED || set.status() != ChangeStatus.APPLYING)
					throw new Failure(INVALID_TRANSITION, "Prepare requires APPLYING state");
			}
			else if (!prior.equals(checkpoint)) {
				boolean allowed = switch (prior.state()) {
					case PREPARED -> checkpoint.state() != Checkpoint.State.PREPARED;
					case APPLIED -> checkpoint.state() == Checkpoint.State.RESTORED || checkpoint.state() == Checkpoint.State.CONFLICT;
					case CONFLICT -> checkpoint.state() == Checkpoint.State.APPLIED || checkpoint.state() == Checkpoint.State.RESTORED;
					case RESTORED -> false;
				};
				if (!allowed || checkpoint.recordedAt().isBefore(prior.recordedAt())
						|| !new Checkpoint(prior.checkpointId(), prior.changeSetId(), prior.owner(), prior.before(),
								prior.after(), prior.beforeBlob(), prior.afterBlob(), checkpoint.state(), checkpoint.recordedAt()).equals(checkpoint))
					throw new Failure(INVALID_TRANSITION, "Invalid checkpoint completion");
			}
		}
		if (!old.isEmpty()) throw new Failure(INVALID_TRANSITION, "Checkpoint removed");
		if (set.status() == ChangeStatus.APPLIED && (next.checkpoints().size() != set.diffs().size()
				|| next.checkpoints().stream().anyMatch(c -> c.state() != Checkpoint.State.APPLIED)))
			throw new Failure(INVALID_TRANSITION, "APPLIED requires every checkpoint completed");
		if (set.status() == ChangeStatus.UNDONE && next.checkpoints().stream().anyMatch(c -> c.state() != Checkpoint.State.RESTORED))
			throw new Failure(INVALID_TRANSITION, "UNDONE requires restored checkpoints");
	}

	private void validatePath(String path) throws Failure {
		if (this.policy.excluded(path)) throw new Failure(IDENTITY_MISMATCH, "Excluded source path: " + path);
	}
	private static void match(ChangeSet set, UUID id, ChangeOwner owner) throws Failure {
		if (!set.changeSetId().equals(id) || !set.owner().equals(owner))
			throw new Failure(IDENTITY_MISMATCH, "Change owner or ID mismatch");
	}
	private void validateBlobs(Snapshot snapshot) throws Failure {
		for (Checkpoint checkpoint : snapshot.checkpoints()) {
			if (checkpoint.beforeBlob() != null) this.blobs.read(checkpoint.beforeBlob());
			if (checkpoint.afterBlob() != null) this.blobs.read(checkpoint.afterBlob());
		}
		for (VerificationEvidence evidence : snapshot.evidence()) readLogBytes(evidence);
	}
	private byte[] readLogBytes(VerificationEvidence evidence) throws Failure {
		byte[] bytes = AtomicFiles.read(this.directory.resolve("evidence").resolve(evidence.logSha256()), this.limits.maxBlobBytes());
		if (!Fingerprinter.sha256(bytes).equals(evidence.logSha256())) throw new Failure(CORRUPT, "Evidence log integrity mismatch");
		return bytes;
	}

	private final class WorkspaceLease implements Lease {
		private final FileChannel channel;
		private final FileLock lock;
		private boolean closed;
		private boolean poisoned;
		WorkspaceLease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

		private void open() throws Failure {
			if (this.closed || !this.lock.isValid()) throw new Failure(CLOSED, "Workspace lease closed");
		}
		private View writable() throws Failure {
			open();
			if (this.poisoned) throw new Failure(RECOVERY_REQUIRED, "Prior publication failed; reopen and inspect");
			View view = load();
			if (view.journalState() != ChangeJournal.State.CLEAN) throw ChangeJournal.blocked(view.journalState());
			return view;
		}
		private Snapshot owned(UUID id, ChangeOwner owner, String digest) throws Failure {
			Snapshot snapshot = writable().get(id);
			match(snapshot.changeSet(), id, owner);
			if (!snapshot.digest().equals(digest)) throw new Failure(IDENTITY_MISMATCH, "Change digest mismatch");
			return snapshot;
		}
		@Override public synchronized View read() throws Failure { open(); return load(); }

		@Override public synchronized Path stagingDirectory(ChangeOwner owner) throws Failure {
			writable();
			String key = Fingerprinter.sha256(ChangeJournal.JSON.writeValueAsBytes(owner));
			Path path = directory.resolve("staging").resolve(key);
			try { AtomicFiles.directory(path); }
			catch (IOException | UnsupportedOperationException ex) { throw AtomicFiles.failure(ex); }
			return path;
		}
		private Snapshot publish(Snapshot old, ChangeSet set, List<Checkpoint> checkpoints,
				List<VerificationEvidence> evidence) throws Failure {
			ChangeJournal.Scan scan = journal.scan();
			Snapshot snapshot = new Snapshot(scan.records().size() + 1L, set, set.digest(), checkpoints, evidence);
			validate(old, snapshot);
			validateBlobs(snapshot);
			byte[] manifest = ChangeJournal.JSON.writeValueAsBytes(new Manifest(1, hash, snapshot));
			if (manifest.length > limits.maxManifestBytes()) throw new Failure(LIMIT, "Manifest exceeds limit");
			try { journal.append(snapshot); }
			catch (Failure ex) {
				// A refusal raised before the first journal byte was written leaves the lease usable.
				if (journalGrew(scan)) this.poisoned = true;
				throw ex;
			}
			try {
				AtomicFiles.write(directory.resolve("manifests").resolve(set.changeSetId() + ".json"),
						manifest, limits.maxManifestBytes());
			}
			catch (Failure ex) { this.poisoned = true; throw ex; }
			return snapshot;
		}
		private boolean journalGrew(ChangeJournal.Scan before) {
			try {
				return Files.readAttributes(directory.resolve("journal.log"), BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS).size() != before.completeBytes();
			}
			catch (IOException ex) {
				// An unreadable journal size cannot prove that nothing was written.
				return true;
			}
		}
		@Override public synchronized Snapshot propose(ChangeSet proposal) throws Failure {
			View view = writable();
			if (view.changes().stream().anyMatch(s -> s.changeSet().changeSetId().equals(proposal.changeSetId())))
				throw new Failure(ALREADY_EXISTS, "Change set already exists");
			return publish(null, proposal, List.of(), List.of());
		}
		@Override public synchronized Snapshot decide(UUID id, ChangeOwner owner, String digest, Approval approval) throws Failure {
			Snapshot old = owned(id, owner, digest);
			try { return publish(old, old.changeSet().decide(approval), old.checkpoints(), old.evidence()); }
			catch (IllegalArgumentException ex) { throw new Failure(IDENTITY_MISMATCH, "Approval does not match proposal", ex); }
			catch (IllegalStateException ex) { throw new Failure(INVALID_TRANSITION, "Proposal is not awaiting approval", ex); }
		}
		@Override public synchronized Snapshot transition(UUID id, ChangeOwner owner, String digest, ChangeStatus next) throws Failure {
			Snapshot old = owned(id, owner, digest);
			if (next == ChangeStatus.APPLYING && writable().changes().stream().anyMatch(s ->
					!s.changeSet().changeSetId().equals(id)
					&& new View(ChangeJournal.State.CLEAN, List.of(s)).recoveryRequired()))
				throw new Failure(RECOVERY_REQUIRED, "Another change has unresolved workspace effects");
			try { return publish(old, old.changeSet().transition(next), old.checkpoints(), old.evidence()); }
			catch (IllegalStateException ex) { throw new Failure(INVALID_TRANSITION, "Invalid change transition", ex); }
		}
		@Override public synchronized Checkpoint prepare(UUID id, ChangeOwner owner, String digest, UUID checkpointId,
				String path, byte[] before, byte[] after, Instant at) throws Failure {
			Snapshot old = owned(id, owner, digest);
			Diff diff = old.changeSet().diffs().stream().filter(d -> d.relativePath().equals(path)).findFirst()
					.orElseThrow(() -> new Failure(IDENTITY_MISMATCH, "Path is not in proposal"));
			if (old.changeSet().status() != ChangeStatus.APPLYING
					|| old.checkpoints().stream().anyMatch(c -> c.checkpointId().equals(checkpointId) || c.before().relativePath().equals(path)))
				throw new Failure(INVALID_TRANSITION, "Checkpoint already exists or change is not APPLYING");
			checkBytes(diff.before(), before);
			checkBytes(diff.after(), after);
			Checkpoint checkpoint = new Checkpoint(checkpointId, id, owner, diff.before(), diff.after(),
					putContent(diff.before(), before), putContent(diff.after(), after), Checkpoint.State.PREPARED, at);
			List<Checkpoint> checkpoints = new ArrayList<>(old.checkpoints());
			checkpoints.add(checkpoint);
			publish(old, old.changeSet(), checkpoints, old.evidence());
			return checkpoint;
		}
		private Checkpoint.BlobRef putContent(Fingerprint fp, byte[] bytes) throws Failure {
			return fp.kind() == EntryKind.REGULAR_FILE || fp.kind() == EntryKind.SYMBOLIC_LINK ? blobs.put(bytes) : null;
		}
		private void checkBytes(Fingerprint fp, byte[] bytes) throws Failure {
			boolean content = fp.kind() == EntryKind.REGULAR_FILE || fp.kind() == EntryKind.SYMBOLIC_LINK;
			if (bytes == null || (content ? bytes.length != fp.size() || !Fingerprinter.sha256(bytes).equals(fp.sha256())
					|| fp.kind() == EntryKind.SYMBOLIC_LINK && !java.util.Arrays.equals(bytes, fp.linkText().getBytes(UTF_8))
					: bytes.length != 0))
				throw new Failure(IDENTITY_MISMATCH, "Checkpoint bytes differ from fingerprint");
		}
		@Override public synchronized Checkpoint complete(UUID id, ChangeOwner owner, String digest, UUID checkpointId,
				Checkpoint.State state, Instant at) throws Failure {
			Snapshot old = owned(id, owner, digest);
			List<Checkpoint> checkpoints = new ArrayList<>(old.checkpoints());
			int index = -1;
			for (int i = 0; i < checkpoints.size(); i++) if (checkpoints.get(i).checkpointId().equals(checkpointId)) index = i;
			if (index == -1) throw new Failure(NOT_FOUND, "Checkpoint not found");
			Checkpoint prior = checkpoints.get(index);
			Checkpoint completed = new Checkpoint(prior.checkpointId(), id, owner, prior.before(), prior.after(),
					prior.beforeBlob(), prior.afterBlob(), state, at);
			if (prior.equals(completed)) throw new Failure(INVALID_TRANSITION, "Checkpoint already recorded");
			checkpoints.set(index, completed);
			publish(old, old.changeSet(), checkpoints, old.evidence());
			return completed;
		}
		@Override public synchronized Snapshot recordEvidence(UUID id, ChangeOwner owner, String digest,
				VerificationEvidence evidence, byte[] log) throws Failure {
			Snapshot old = owned(id, owner, digest);
			match(old.changeSet(), evidence.changeSetId(), evidence.owner());
			if (!evidence.sourceCwd().equals(workspace.toString()) || !Fingerprinter.sha256(log).equals(evidence.logSha256()))
				throw new Failure(IDENTITY_MISMATCH, "Evidence cwd or log mismatch");
			if (old.evidence().stream().anyMatch(e -> e.evidenceId().equals(evidence.evidenceId())))
				throw new Failure(ALREADY_EXISTS, "Evidence already exists");
			new BlobStore(directory.resolve("evidence"), limits.maxBlobBytes()).put(log);
			List<VerificationEvidence> all = new ArrayList<>(old.evidence());
			all.add(evidence);
			return publish(old, old.changeSet(), old.checkpoints(), all);
		}
		@Override public synchronized byte[] readBlob(Checkpoint.BlobRef ref) throws Failure { open(); return blobs.read(ref); }
		@Override public synchronized byte[] readLog(UUID id, ChangeOwner owner, String digest, UUID evidenceId) throws Failure {
			open();
			Snapshot snapshot = load().get(id);
			match(snapshot.changeSet(), id, owner);
			if (!snapshot.digest().equals(digest)) throw new Failure(IDENTITY_MISMATCH, "Change digest mismatch");
			VerificationEvidence evidence = snapshot.evidence().stream().filter(e -> e.evidenceId().equals(evidenceId)).findFirst()
					.orElseThrow(() -> new Failure(NOT_FOUND, "Evidence not found"));
			return readLogBytes(evidence);
		}
		@Override public synchronized void close() throws Failure {
			if (this.closed) return;
			this.closed = true;
			synchronized (JVM_LEASES) {
				try { this.channel.close(); }
				catch (IOException ex) { throw AtomicFiles.failure(ex); }
				finally { JVM_LEASES.remove(workspace); JVM_LEASES.notifyAll(); }
			}
		}
	}
}
