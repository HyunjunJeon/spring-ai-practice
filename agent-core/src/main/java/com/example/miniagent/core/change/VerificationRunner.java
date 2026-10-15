package com.example.miniagent.core.change;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/** Real process evidence for a private, filtered, source-read-only proposal clone. */
public final class VerificationRunner {
	public enum Code {
		SUCCEEDED, NON_ZERO_EXIT, CANCELLED, TIMED_OUT, OUTPUT_LIMIT, STALE,
		SOURCE_CHANGED, UNSUPPORTED_SANDBOX, INVALID_CACHE_ROOT, IO, CLEANUP_FAILED
	}
	public static final class Refusal extends IOException {
		private final Code code;
		public Refusal(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
		public Code code() { return this.code; }
	}
	public record Request(UUID changeSetId, ChangeOwner owner, String digest, String sourceRevision,
			List<String> argv, Duration timeout, int maxOutputBytes) {
		public Request {
			Objects.requireNonNull(changeSetId);
			Objects.requireNonNull(owner);
			Fingerprint.requireDigest(digest);
			Fingerprint.requireDigest(sourceRevision);
			argv = List.copyOf(argv);
			if (argv.isEmpty() || argv.getFirst().isEmpty() || argv.stream().anyMatch(a -> a.indexOf('\0') >= 0)
					|| timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofDays(1)) > 0
					|| maxOutputBytes < 1 || maxOutputBytes > 16 * 1024 * 1024 - 8)
				throw new IllegalArgumentException("Invalid verification request");
		}
	}
	public record Context(Path root, Path temp, Path liveRoot, Path agentHome) {}
	public record Launch(List<String> command, Map<String, String> environment, String policyDigest) {
		public Launch {
			command = List.copyOf(command);
			environment = Map.copyOf(environment);
			Fingerprint.requireDigest(policyDigest);
		}
	}
	@FunctionalInterface
	public interface LaunchPolicy {
		Launch prepare(Context context, List<String> argv) throws IOException;
	}
	public record Result(Code code, @Nullable VerificationEvidence evidence) {
		public boolean accepted(String revision, String configuration) {
			return code == Code.SUCCEEDED && evidence != null && evidence.successful()
					&& evidence.validFor(revision, configuration);
		}
	}
	private final ChangeStore store;
	private final LaunchPolicy policy;
	private final Clock clock;

	public VerificationRunner(ChangeStore store, LaunchPolicy policy, Clock clock) {
		this.store = Objects.requireNonNull(store);
		this.policy = Objects.requireNonNull(policy);
		this.clock = Objects.requireNonNull(clock);
	}

	/** Trusted pre-process evaluation, computed from the operator request and actual launch policy. */
	public record Binding(String revision, String configuration) {}
	public record TargetResult(Result result, byte[] log) {
		public TargetResult { log = log.clone(); }
		@Override public byte[] log() { return log.clone(); }
	}
	private record Source(UUID changeSetId, ChangeOwner owner, Map<String, Fingerprint> baseline,
			Map<String, Fingerprint> proposed, ChangeStatus status) {
		static Source of(ChangeSet set) {
			return new Source(set.changeSetId(), set.owner(), set.baseline(), set.proposed(), set.status());
		}
	}

	/** Call after record(); ambiguity and recovery are refusals, not no-change success. */
	public VerificationTarget capture(StagedWorkspace stage) throws IOException {
		var view = this.store.read();
		if (view.recoveryRequired()) throw new IOException("Change recovery required");
		var matches = view.changes().stream().filter(s -> s.changeSet().owner().equals(stage.owner())
				&& eligible(s.changeSet())).toList();
		if (matches.size() > 1) throw new IOException("Ambiguous attempt proposals");
		if (!matches.isEmpty()) {
			var s = matches.getFirst();
			var target = new VerificationTarget(s.changeSet().changeSetId(), s.changeSet().owner(),
					s.digest(), Fingerprinter.treeDigest(s.changeSet().proposed()), false);
			if (!current(target)) throw new IOException("Stale proposal");
			return target;
		}
		PathPolicy live = live();
		var baseline = new Fingerprinter(live).snapshot();
		if (!tree(stage.policy(), baseline).equals(baseline)) throw new IOException("Unpublished stage changes");
		String revision = Fingerprinter.treeDigest(baseline);
		return new VerificationTarget(UUID.randomUUID(), stage.owner(), revision, revision, true);
	}

	private PathPolicy live() throws IOException {
		return new PathPolicy(this.store.workspace(), this.store.directory().getParent().getParent(), List.of());
	}

	public boolean current(VerificationTarget target) throws IOException {
		try (var lease = this.store.acquire()) {
			var view = lease.read();
			if (view.recoveryRequired()) return false;
			var live = live();
			var stage = PathPolicy.stagedView(lease.stagingDirectory(target.owner()), live);
			if (target.readOnly()) {
				var baseline = new Fingerprinter(live).snapshot();
				return Fingerprinter.treeDigest(baseline).equals(target.revision())
						&& tree(stage, baseline).equals(baseline);
			}
			var s = view.get(target.id());
			return s.changeSet().owner().equals(target.owner()) && s.digest().equals(target.digest())
					&& eligible(s.changeSet()) && current(Source.of(s.changeSet()), live, stage)
					&& Fingerprinter.treeDigest(s.changeSet().proposed()).equals(target.revision());
		}
	}

	public Result run(Request request, CompletableFuture<Void> cancellation) throws IOException {
		return run(new VerificationTarget(request.changeSetId(), request.owner(), request.digest(),
				request.sourceRevision(), false), request.argv(), request.timeout(), request.maxOutputBytes(),
				cancellation, binding -> {}).result();
	}

	/** Same clone, sandbox, capture and cleanup for proposals and distinct read-only snapshots. */
	public TargetResult run(VerificationTarget target, List<String> argv, Duration timeout, int maxOutputBytes,
			CompletableFuture<Void> cancellation, java.util.function.Consumer<Binding> prepared) throws IOException {
		var request = new Request(target.id(), target.owner(), target.digest(), target.revision(),
				argv, timeout, maxOutputBytes);
		byte[][] captured = { new byte[0] };
		Result result = runTarget(target, request, cancellation, prepared, captured);
		return new TargetResult(result, captured[0]);
	}

	private Result runTarget(VerificationTarget target, Request request, CompletableFuture<Void> cancellation,
			java.util.function.Consumer<Binding> prepared, byte[][] captured) throws IOException {
		Objects.requireNonNull(cancellation);
		var cancelSignal = cancellation.thenApply(ignored -> Code.CANCELLED);
		boolean interrupted = Thread.interrupted();
		Path privateRoot = null;
		Result result = null;
		try {
			if (interrupted || cancellation.isDone()) return new Result(Code.CANCELLED, null);
			Path home = this.store.directory().getParent().getParent();
			PathPolicy live = new PathPolicy(this.store.workspace(), home, List.of());
			Source set;
			PathPolicy stage;
			try (var lease = this.store.acquire()) {
				var view = lease.read();
				if (target.readOnly() && view.recoveryRequired()) return new Result(Code.STALE, null);
				if (target.readOnly()) {
					var baseline = new Fingerprinter(live).snapshot();
					if (!Fingerprinter.treeDigest(baseline).equals(target.revision())) return new Result(Code.STALE, null);
					set = new Source(target.id(), target.owner(), baseline, baseline, ChangeStatus.PENDING_APPROVAL);
				}
				else {
					var snapshot = view.get(request.changeSetId());
					if (!snapshot.changeSet().owner().equals(request.owner()) || !snapshot.digest().equals(request.digest()))
						throw new ChangeStore.Failure(ChangeStore.Code.IDENTITY_MISMATCH, "Verification owner/digest mismatch");
					if (!eligible(snapshot.changeSet())) return new Result(Code.STALE, null);
					set = Source.of(snapshot.changeSet());
				}
				stage = PathPolicy.stagedView(lease.stagingDirectory(request.owner()), live);
				if (!current(set, live, stage)
						|| !Fingerprinter.treeDigest(set.proposed()).equals(request.sourceRevision()))
					return new Result(Code.STALE, null);
			}
			privateRoot = Files.createTempDirectory(stage.root().getParent(), "verify-",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
			Path root = Files.createDirectory(privateRoot.resolve("source"),
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
			Path temp = Files.createDirectory(privateRoot.resolve("temp"),
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
			PathPolicy clone = PathPolicy.stagedView(root, live);
			copy(stage, root, set.proposed());
			if (!current(set, live, stage) || !tree(clone, set.proposed()).equals(set.proposed()))
				return new Result(Code.STALE, null);
			Launch launch;
			try { launch = this.policy.prepare(new Context(root, temp, live.root(), home.toRealPath()), request.argv()); }
			catch (Refusal ex) { return new Result(ex.code(), null); }
			interrupted |= Thread.interrupted();
			if (interrupted) return new Result(Code.CANCELLED, null);
			String configuration = configuration(request, launch);
			prepared.accept(new Binding(request.sourceRevision(), configuration));
			if (!current(set, live, stage)) return new Result(Code.STALE, null);
			if (cancellation.isDone()) return new Result(Code.CANCELLED, null);
			var started = this.clock.instant();
			var builder = new ProcessBuilder(launch.command()).directory(root.toFile());
			builder.environment().clear();
			builder.environment().putAll(launch.environment());
			Process process;
			try { process = builder.start(); }
			catch (IOException ex) { return new Result(Code.IO, null); }
			long deadline = System.nanoTime() + request.timeout().toNanos();
			var capture = new Capture(request.maxOutputBytes());
			var out = new CompletableFuture<Void>();
			var err = new CompletableFuture<Void>();
			Thread stdout = reader(process.getInputStream(), capture, true, out);
			Thread stderr = reader(process.getErrorStream(), capture, false, err);
			var complete = CompletableFuture.allOf(process.onExit(), out, err);
			Code code;
			try {
				CompletableFuture.anyOf(complete, cancelSignal, capture.overflow)
						.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				if (capture.overflow.isDone()) code = Code.OUTPUT_LIMIT;
				else if (cancellation.isDone()) code = Code.CANCELLED;
				else code = process.exitValue() == 0 ? Code.SUCCEEDED : Code.NON_ZERO_EXIT;
			}
			catch (InterruptedException ex) { interrupted = true; code = Code.CANCELLED; }
			catch (TimeoutException ex) { code = Code.TIMED_OUT; }
			catch (ExecutionException ex) { code = Code.IO; }
			if (!cleanup(process, stdout, stderr)) code = Code.CLEANUP_FAILED;
			// Cleanup can receive another interrupt while awaiting exact exit/EOF signals.
			interrupted |= Thread.interrupted();
			Integer exit = process.isAlive() ? null : process.exitValue();
			boolean cancelled = code != Code.SUCCEEDED && code != Code.NON_ZERO_EXIT;
			if (code != Code.CLEANUP_FAILED) {
				if (!tree(clone, set.proposed()).equals(set.proposed())) code = Code.SOURCE_CHANGED;
				else if (!current(set, live, stage)) code = Code.STALE;
			}
			byte[] log = capture.log();
			captured[0] = log;
			var evidence = new VerificationEvidence(UUID.randomUUID(), set.changeSetId(), set.owner(),
					request.argv(), root.toString(), live.root().toString(), request.sourceRevision(),
					configuration, Fingerprinter.sha256(log), exit, cancelled, started, this.clock.instant());
			if (!target.readOnly()) try (var lease = this.store.acquire()) {
				var latest = lease.read().get(set.changeSetId());
				if (code != Code.CLEANUP_FAILED && (!eligible(latest.changeSet()) || !current(set, live, stage)))
					code = Code.STALE;
				lease.recordEvidence(set.changeSetId(), set.owner(), request.digest(), evidence, log);
			}
			result = new Result(code, evidence);
		}
		finally {
			try {
				if (privateRoot != null) {
					// The clone preserves reviewed modes, including read-only directories.
					try (var paths = Files.walk(privateRoot)) {
						for (Path path : paths.filter(p -> Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
							var permissions = Files.getPosixFilePermissions(path);
							permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
							permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
							Files.setPosixFilePermissions(path, permissions);
						}
					}
					try (var paths = Files.walk(privateRoot)) {
						for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
					}
				}
			}
			catch (IOException ex) {
				if (result == null) throw new Refusal(Code.CLEANUP_FAILED, ex);
				result = new Result(Code.CLEANUP_FAILED, result.evidence());
			}
			finally { if (interrupted) Thread.currentThread().interrupt(); }
		}
		return result;
	}

	private static boolean eligible(ChangeSet set) {
		return set.status() == ChangeStatus.PENDING_APPROVAL || set.status() == ChangeStatus.APPROVED
				|| set.status() == ChangeStatus.APPLIED;
	}
	private static boolean current(Source set, PathPolicy live, PathPolicy stage) throws IOException {
		return new Fingerprinter(live).snapshot().equals(set.status() == ChangeStatus.APPLIED ? set.proposed() : set.baseline())
				&& tree(stage, set.proposed()).equals(set.proposed());
	}
	static Map<String, Fingerprint> tree(PathPolicy policy, Map<String, Fingerprint> expected) throws IOException {
		var tree = new TreeMap<>(new Fingerprinter(policy).snapshot());
		for (var f : expected.values()) if (f.kind() == EntryKind.SYMBOLIC_LINK) {
			if (Files.exists(policy.root().resolve(f.relativePath()), java.nio.file.LinkOption.NOFOLLOW_LINKS))
				throw new PathPolicy.Violation(PathPolicy.Code.SYMLINK, f.relativePath());
			tree.put(f.relativePath(), f);
		}
		return tree;
	}
	static void copy(PathPolicy stage, Path root, Map<String, Fingerprint> expected) throws IOException {
		var reader = new Fingerprinter(stage);
		long total = 0;
		for (var f : expected.values()) {
			if (f.kind() == EntryKind.SYMBOLIC_LINK) continue;
			var observation = reader.read(f.relativePath());
			if (!observation.fingerprint().equals(f)) throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, f.relativePath());
			total += observation.bytes().length;
			if (total > 256L * 1024 * 1024) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, f.relativePath());
			Path target = root.resolve(f.relativePath());
			if (f.kind() == EntryKind.DIRECTORY) Files.createDirectories(target);
			else {
				Files.createDirectories(target.getParent());
				Files.write(target, observation.bytes());
				Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(stage.resolve(f.relativePath())));
			}
		}
		for (var f : expected.values().stream().sorted(Comparator.comparing(Fingerprint::relativePath).reversed()).toList())
			if (f.kind() == EntryKind.DIRECTORY)
				Files.setPosixFilePermissions(root.resolve(f.relativePath()), Files.getPosixFilePermissions(stage.resolve(f.relativePath())));
	}
	public static String configuration(Request request, Launch launch) throws IOException {
		var bytes = new ByteArrayOutputStream();
		var data = new DataOutputStream(bytes);
		for (String s : List.of("verification-v1-filtered-source-no-links", System.getProperty("os.name"),
				System.getProperty("os.version"), System.getProperty("os.arch"), System.getProperty("java.version"),
				launch.policyDigest()))
			DiffRenderer.field(data, s);
		data.writeInt(request.argv().size());
		for (String arg : request.argv()) DiffRenderer.field(data, arg);
		data.writeLong(request.timeout().toNanos());
		data.writeInt(request.maxOutputBytes());
		return Fingerprinter.sha256(bytes.toByteArray());
	}
	private static Thread reader(InputStream stream, Capture capture, boolean stdout, CompletableFuture<Void> done) {
		return Thread.ofVirtual().name("verification-output").start(() -> {
			try {
				byte[] buffer = new byte[8192];
				for (int n; (n = stream.read(buffer)) != -1;) {
					if (!capture.append(buffer, n, stdout)) break;
				}
				done.complete(null);
			}
			catch (IOException ex) { done.completeExceptionally(ex); }
		});
	}
	private static boolean cleanup(Process process, Thread stdout, Thread stderr) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		boolean interrupted = Thread.interrupted();
		boolean clean = true;
		var children = process.descendants().sorted(Comparator.comparingInt(VerificationRunner::depth).reversed()).toList();
		try {
			for (var child : children) child.destroyForcibly();
			for (var child : children) {
				try { child.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
				catch (InterruptedException ex) { interrupted = true; clean = false; }
				catch (ExecutionException | TimeoutException ex) { clean = false; }
			}
			if (process.isAlive()) process.destroyForcibly();
			var closed = new CompletableFuture<Void>();
			Thread.ofVirtual().name("verification-close").start(() -> {
				try {
					process.getInputStream().close();
					process.getErrorStream().close();
					process.getOutputStream().close();
					closed.complete(null);
				}
				catch (IOException ex) { closed.completeExceptionally(ex); }
			});
			try {
				process.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				closed.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
				stdout.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
				stderr.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
			}
			catch (InterruptedException ex) { interrupted = true; clean = false; }
			catch (ExecutionException | TimeoutException ex) { clean = false; }
			return clean && !stdout.isAlive() && !stderr.isAlive() && !process.isAlive()
					&& children.stream().noneMatch(ProcessHandle::isAlive);
		}
		finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
	private static int depth(ProcessHandle handle) {
		int depth = 0;
		for (var parent = handle.parent(); parent.isPresent(); parent = parent.get().parent()) depth++;
		return depth;
	}
	private static final class Capture {
		private final int limit;
		private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		private final CompletableFuture<Void> overflow = new CompletableFuture<>();
		Capture(int limit) { this.limit = limit; }
		synchronized boolean append(byte[] bytes, int count, boolean out) {
			int available = this.limit - this.stdout.size() - this.stderr.size();
			(out ? this.stdout : this.stderr).write(bytes, 0, Math.min(count, available));
			if (count > available) this.overflow.complete(null);
			return !this.overflow.isDone();
		}
		synchronized byte[] log() throws IOException {
			var bytes = new ByteArrayOutputStream();
			var data = new DataOutputStream(bytes);
			data.writeInt(this.stdout.size());
			this.stdout.writeTo(data);
			data.writeInt(this.stderr.size());
			this.stderr.writeTo(data);
			return bytes.toByteArray();
		}
	}
}
