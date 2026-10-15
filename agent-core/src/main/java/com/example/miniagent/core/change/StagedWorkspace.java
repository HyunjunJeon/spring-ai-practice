package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import com.example.miniagent.core.model.ToolContextKeys;
import com.example.miniagent.core.model.TurnContext;
import com.example.miniagent.core.run.RunId;
import org.springframework.ai.chat.model.ToolContext;

/** Captured attempt-owned workspace. Close retains proposals and stage for review/recovery. */
public final class StagedWorkspace implements AutoCloseable {
	private static final ThreadLocal<StagedWorkspace> CURRENT = new ThreadLocal<>();
	private final PathPolicy source;
	private final PathPolicy policy;
	private final Path temp;
	private final ChangeOwner owner;
	private final Map<String, Fingerprint> baseline;
	private final Map<String, Fingerprinter.Observation> originals;
	private final ChangeSetRecorder recorder;
	private final ExternalChangeScanner external;
	private boolean closed;

	private StagedWorkspace(ChangeStore store, TurnContext turn, Clock clock) throws IOException {
		this.owner = new ChangeOwner(turn.sessionId(), new RunId(turn.runId()), turn.attemptId(), turn.turnId());
		// directory is <home>/changes/<workspaceHash>.
		this.source = new PathPolicy(store.workspace(), store.directory().getParent().getParent(), List.of());
		if (this.source.root().startsWith(this.source.agentHome()))
			throw new PathPolicy.Violation(PathPolicy.Code.EXCLUDED, this.source.root().toString());
		if (!turn.cwd().toRealPath().equals(store.workspace()))
			throw new PathPolicy.Violation(PathPolicy.Code.OUTSIDE_ROOT, turn.cwd().toString());
		Path stage;
		try (var lease = store.acquireStaging()) {
			stage = lease.stagingDirectory(this.owner);
			try (var entries = Files.list(stage)) {
				if (entries.findAny().isPresent()) throw new IOException("Owner stage already contains source");
			}
			ChangeSetRecorder.supersede(lease, this.owner);
		}
		this.policy = PathPolicy.stagedView(stage, this.source);
		var reader = new Fingerprinter(this.source);
		this.baseline = reader.snapshot();
		var captured = new TreeMap<String, Fingerprinter.Observation>();
		long total = 0;
		for (var entry : this.baseline.entrySet()) {
			String relative = entry.getKey();
			var observation = reader.read(relative);
			if (!observation.fingerprint().equals(entry.getValue()))
				throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, relative);
			total += observation.bytes().length;
			if (total > 256L * 1024 * 1024) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, relative);
			captured.put(relative, observation);
			Path target = stage.resolve(relative);
			switch (entry.getValue().kind()) {
				case DIRECTORY -> Files.createDirectories(target);
				case REGULAR_FILE -> {
					Files.createDirectories(target.getParent());
					Files.write(target, observation.bytes());
					Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(this.source.resolve(relative),
							LinkOption.NOFOLLOW_LINKS));
				}
				// Links are retained as fingerprint evidence, never copied into an executable/readable stage.
				case SYMBOLIC_LINK -> {}
				case MISSING -> throw new IOException("Missing snapshot entry");
			}
		}
		// Directory modes are set last so read-only source directories do not block copying.
		for (var entry : this.baseline.entrySet().stream().sorted(Map.Entry.<String, Fingerprint>comparingByKey().reversed()).toList()) {
			if (entry.getValue().kind() == EntryKind.DIRECTORY)
				Files.setPosixFilePermissions(stage.resolve(entry.getKey()),
						Files.getPosixFilePermissions(this.source.resolve(entry.getKey()), LinkOption.NOFOLLOW_LINKS));
		}
		this.originals = Map.copyOf(captured);
		this.recorder = new ChangeSetRecorder(store, this.owner, turn, clock, this.baseline);
		this.external = new ExternalChangeScanner(this.source, this.baseline);
		this.temp = Files.createTempDirectory(stage.getParent(), "temp-",
				PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
	}

	public static StagedWorkspace open(ChangeStore store, TurnContext turn, Clock clock) throws IOException {
		return new StagedWorkspace(store, turn, clock);
	}
	public Path root() { return this.policy.root(); }
	public Path liveRoot() { return this.source.root(); }
	public Path tempDirectory() { return this.temp; }
	public PathPolicy policy() { return this.policy; }
	public ChangeOwner owner() { return this.owner; }
	public static Optional<StagedWorkspace> current() { return Optional.ofNullable(CURRENT.get()); }
	public static Optional<StagedWorkspace> from(ToolContext context) {
		return context == null ? Optional.empty()
				: Optional.ofNullable((StagedWorkspace) context.getContext().get(ToolContextKeys.STAGED_WORKSPACE));
	}

	public Path resolve(String path, boolean mutation, List<Path> externalReadRoots) throws IOException {
		if (this.closed) throw new IOException("Stage closed");
		Path input = Path.of(path);
		String relative;
		if (input.isAbsolute()) {
			Path normalized = input.normalize();
			String liveRelative = relativeToRoot(normalized, liveRoot());
			String stageRelative = liveRelative == null ? relativeToRoot(normalized, root()) : null;
			if (liveRelative != null) relative = liveRelative;
			else if (stageRelative != null) relative = stageRelative;
			else {
				if (mutation) throw new PathPolicy.Violation(PathPolicy.Code.OUTSIDE_ROOT, path);
				for (Path root : externalReadRoots) {
					Path actual = root.toAbsolutePath().normalize();
					if (normalized.startsWith(actual) && !normalized.startsWith(this.source.agentHome())) {
						var external = new PathPolicy(actual, this.source.agentHome(), List.of());
						String name = actual.relativize(normalized).toString();
						if (name.isEmpty()) return external.root();
						var observation = external.observe(name);
						if (observation.leaf() != null && observation.leaf().isSymbolicLink())
							throw new PathPolicy.Violation(PathPolicy.Code.SYMLINK, path);
						return observation.target();
					}
				}
				throw new PathPolicy.Violation(PathPolicy.Code.OUTSIDE_ROOT, path);
			}
		}
		else relative = path;
		if (relative.isEmpty() || relative.equals(".")) {
			if (mutation) throw new PathPolicy.Violation(PathPolicy.Code.INVALID_PATH, path);
			return root();
		}
		PathPolicy.validateRelative(relative);
		for (var entry : this.baseline.entrySet()) {
			if (entry.getValue().kind() == EntryKind.SYMBOLIC_LINK
					&& (relative.equals(entry.getKey()) || relative.startsWith(entry.getKey() + "/")))
				throw new PathPolicy.Violation(PathPolicy.Code.SYMLINK, path);
		}
		// Preserve omitted link identity: a missing stage link is not permission to replace it.
		var original = this.baseline.get(relative);
		if (original != null && original.kind() == EntryKind.SYMBOLIC_LINK)
			throw new PathPolicy.Violation(PathPolicy.Code.SYMLINK, path);
		var observed = this.policy.observe(relative);
		if (observed.leaf() != null && observed.leaf().isSymbolicLink())
			throw new PathPolicy.Violation(PathPolicy.Code.SYMLINK, path);
		return mutation ? this.policy.resolveMutation(relative) : observed.target();
	}

	private static String relativeToRoot(Path absolute, Path root) throws IOException {
		if (absolute.startsWith(root)) return root.relativize(absolute).toString();
		Path prefix = absolute.getRoot();
		for (Path part : absolute) {
			prefix = prefix.resolve(part);
			try {
				if (prefix.toRealPath().equals(root)) return prefix.relativize(absolute).toString();
			}
			catch (java.nio.file.NoSuchFileException ex) { return null; }
		}
		return null;
	}

	public synchronized void record() throws IOException {
		if (this.closed) throw new IOException("Stage closed");
		this.recorder.record(this.policy, this.originals, this.baseline, this.external.changed());
	}

	/** Restore acknowledged contents, not tool effects, approval, or verification authority. */
	public synchronized void restoreProposal(ChangeStore store, java.util.UUID id, ChangeOwner savedOwner,
			String digest, String revision) throws IOException {
		if (this.closed || !this.owner.runId().equals(savedOwner.runId())
				|| !this.owner.sessionId().equals(savedOwner.sessionId())
				|| this.owner.attemptId().equals(savedOwner.attemptId()) || !store.workspace().equals(liveRoot()))
			throw new IOException("Invalid restore lineage");
		try (var lease = store.acquireStaging()) {
			var view = lease.read();
			if (view.recoveryRequired()) throw new IOException("Change recovery required");
			var saved = view.get(id);
			var set = saved.changeSet();
			if (!set.owner().equals(savedOwner) || !saved.digest().equals(digest)
					|| !Fingerprinter.treeDigest(set.proposed()).equals(revision)
					|| !(set.status() == ChangeStatus.SUPERSEDED || set.status() == ChangeStatus.PENDING_APPROVAL
						|| set.status() == ChangeStatus.APPROVED)
					|| !this.baseline.equals(set.baseline()) || this.external.changed())
				throw new IOException("Restore identity or baseline changed");
			PathPolicy retained = PathPolicy.stagedView(lease.stagingDirectory(savedOwner), this.source);
			if (!VerificationRunner.tree(retained, set.proposed()).equals(set.proposed()))
				throw new IOException("Retained proposal changed");
			// copy() rechecks each fingerprint before writing and enforces the total byte cap.
			try (var paths = Files.walk(root())) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					if (path.equals(root())) continue;
					if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
						Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
				}
			}
			try (var paths = Files.walk(root())) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
					if (!path.equals(root())) Files.delete(path);
			}
			VerificationRunner.copy(retained, root(), set.proposed());
			if (!VerificationRunner.tree(this.policy, set.proposed()).equals(set.proposed())
					|| this.external.changed()) throw new IOException("Restore changed during copy");
		}
		record(); // A new owner always publishes a new proposal; old evidence is never copied.
	}

	public List<String> linkPaths() {
		return this.baseline.values().stream().filter(f -> f.kind() == EntryKind.SYMBOLIC_LINK)
				.map(Fingerprint::relativePath).toList();
	}

	/** File-tool limits are checked before publication, not after a large staged effect. */
	public void validateWrite(Path file, String content) throws IOException {
		Path target = resolve(file.toString(), true, List.of());
		String relative = root().relativize(target).toString();
		long bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		if (bytes > 16L * 1024 * 1024) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, relative);
		var tree = new Fingerprinter(this.policy).snapshot();
		long total = bytes;
		for (var entry : tree.values()) if (!entry.relativePath().equals(relative)) total += entry.size();
		int count = tree.size() + linkPaths().size();
		Path next = Path.of(relative);
		while (next != null) {
			if (!tree.containsKey(next.toString())) count++;
			next = next.getParent();
		}
		if (total > 256L * 1024 * 1024 || count > 10000)
			throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, relative);
	}

	public Scope openScope() {
		StagedWorkspace previous = CURRENT.get();
		CURRENT.set(this);
		return new Scope(previous);
	}
	public final class Scope implements AutoCloseable {
		private final StagedWorkspace previous;
		private Scope(StagedWorkspace previous) { this.previous = previous; }
		@Override public void close() { if (this.previous == null) CURRENT.remove(); else CURRENT.set(this.previous); }
	}
	@Override public synchronized void close() throws IOException {
		if (this.closed) return;
		boolean interrupted = Thread.interrupted();
		try {
			record();
		}
		finally {
			this.closed = true;
			try (var paths = Files.walk(this.temp)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
			}
			finally { if (interrupted) Thread.currentThread().interrupt(); }
		}
	}
}
