package com.example.miniagent.plugin.bash;

import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Executes the production Seatbelt contribution and real shell/Java fixtures. */
class VerificationRunnerTest {
	@TempDir Path work;
	@TempDir Path state;
	@TempDir Path cache;

	private record Prepared(FileChangeStore store, ChangeSet set, Path stage) {}

	private Prepared prepare() throws Exception {
		Files.writeString(this.work.resolve("source.txt"), "baseline");
		var store = new FileChangeStore(this.state, this.work);
		var owner = new ChangeOwner("session", new RunId("run"), "attempt", "turn");
		var live = new PathPolicy(this.work, this.state, List.of());
		var baseline = new Fingerprinter(live).snapshot();
		Path stage;
		try (var lease = store.acquire()) { stage = lease.stagingDirectory(owner); }
		for (var f : baseline.values()) {
			Path target = stage.resolve(f.relativePath());
			if (f.kind() == EntryKind.DIRECTORY) Files.createDirectories(target);
			else {
				Files.createDirectories(target.getParent());
				Files.copy(this.work.resolve(f.relativePath()), target, StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
		Files.writeString(stage.resolve("source.txt"), "proposal");
		// A separate test-only home authority permits observation of the allocated stage.
		var policy = new PathPolicy(stage, this.cache.resolve("unused-home"), List.of());
		var proposed = new Fingerprinter(policy).snapshot();
		var diff = new DiffRenderer(live).render(baseline.get("source.txt"), "baseline".getBytes(StandardCharsets.UTF_8),
				proposed.get("source.txt"), "proposal".getBytes(StandardCharsets.UTF_8));
		var set = ChangeSet.propose(UUID.randomUUID(), owner, store.workspace().toString(), new ModelRef("fake", "test"),
				List.of(UUID.randomUUID()), baseline, proposed, List.of(diff), Clock.systemUTC().instant());
		try (var lease = store.acquire()) { lease.propose(set); }
		return new Prepared(store, set, stage);
	}
	private VerificationRunner runner(Prepared p) {
		return new VerificationRunner(p.store(), BashSandbox.verificationPolicy(List.of(this.cache)), Clock.systemUTC());
	}
	private VerificationRunner.Request request(Prepared p, List<String> argv, int limit, Duration timeout) {
		var set = p.set();
		return new VerificationRunner.Request(set.changeSetId(), set.owner(), set.digest(),
				Fingerprinter.treeDigest(set.proposed()), argv, timeout, limit);
	}
	private VerificationRunner.Request shell(Prepared p, String script, int limit) {
		return request(p, List.of("/bin/bash", "--noprofile", "--norc", "-c", script), limit, Duration.ofSeconds(20));
	}
	private byte[][] logs(Prepared p, VerificationRunner.Result result) throws Exception {
		var e = result.evidence();
		assertThat(e).isNotNull();
		byte[] log;
		try (var lease = p.store().acquire()) {
			log = lease.readLog(p.set().changeSetId(), p.set().owner(), p.set().digest(), e.evidenceId());
		}
		assertThat(Fingerprinter.sha256(log)).isEqualTo(e.logSha256());
		var data = new DataInputStream(new ByteArrayInputStream(log));
		byte[] stdout = data.readNBytes(data.readInt());
		byte[] stderr = data.readNBytes(data.readInt());
		assertThat(data.read()).isEqualTo(-1);
		return new byte[][] { stdout, stderr };
	}
	private static String quote(Path path) { return "'" + path.toString().replace("'", "'\\''") + "'"; }

	@Test void originalArgvActualCwdAndJavaBytesAreRecorded() throws Exception {
		Files.writeString(this.work.resolve("Fixture.java"), """
				import java.nio.file.Path;
				class Fixture {
				  public static void main(String[] args) throws Exception {
				    System.out.println(Path.of("").toRealPath());
				    for (String arg : args) System.out.println(arg.length() + ":" + arg);
				    System.out.write(new byte[] { 0, (byte) 255 });
				    System.err.write(new byte[] { 1, (byte) 254 });
				  }
				}
				""");
		var p = prepare();
		var argv = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toRealPath().toString(),
				"Fixture.java", "argument with spaces", "", "$literal;not shell");
		var req = request(p, argv, 4096, Duration.ofSeconds(20));
		var result = runner(p).run(req, new CompletableFuture<>());
		assertThat(result.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		var e = result.evidence();
		assertThat(e.argv()).containsExactlyElementsOf(argv);
		assertThat(e.cwd()).isNotEqualTo(p.set().cwd());
		assertThat(e.sourceCwd()).isEqualTo(p.store().workspace().toString());
		assertThat(e.exitCode()).isZero();
		assertThat(e.cancelled()).isFalse();
		assertThat(e.finishedAt()).isAfterOrEqualTo(e.startedAt());
		byte[][] logs = logs(p, result);
		assertThat(new String(logs[0], StandardCharsets.ISO_8859_1))
				.startsWith(e.cwd() + "\n").contains("20:argument with spaces\n", "0:\n", "18:$literal;not shell\n");
		assertThat(logs[0]).endsWith((byte) 0, (byte) 255);
		assertThat(logs[1]).containsExactly((byte) 1, (byte) 254);
		assertThat(Path.of(e.cwd()).getParent()).doesNotExist();
		assertThat(Files.readString(this.work.resolve("source.txt"))).isEqualTo("baseline");
		assertThat(Files.readString(p.stage().resolve("source.txt"))).isEqualTo("proposal");
		assertThat(result.accepted(req.sourceRevision(), e.configurationDigest())).isTrue();
		assertThat(result.accepted("b".repeat(64), e.configurationDigest())).isFalse();
	}

	@Test void nonZeroExitRecordedAsFailed() throws Exception {
		var p = prepare();
		var result = runner(p).run(shell(p, "printf stdout; printf stderr >&2; exit 19", 100), new CompletableFuture<>());
		assertThat(result.code()).isEqualTo(VerificationRunner.Code.NON_ZERO_EXIT);
		assertThat(result.evidence().exitCode()).isEqualTo(19);
		assertThat(result.evidence().successful()).isFalse();
		byte[][] logs = logs(p, result);
		assertThat(logs[0]).isEqualTo("stdout".getBytes(StandardCharsets.UTF_8));
		assertThat(logs[1]).isEqualTo("stderr".getBytes(StandardCharsets.UTF_8));
	}

	@Test void generatedWritesAllowedButSourceLiveHomeAndAuthAccessDenied() throws Exception {
		Path auth = Files.writeString(this.state.resolve("auth.json"), "do not expose");
		var p = prepare();
		String script = "mkdir target build dist .gradle; printf generated > target/result; "
				+ "test -f target/result || exit 10; "
				+ "if printf mutated > source.txt; then exit 11; fi; "
				+ "if printf mutated > " + quote(this.work.resolve("source.txt")) + "; then exit 12; fi; "
				+ "if cat " + quote(auth) + "; then exit 13; fi; "
				+ "if cat " + quote(Path.of(System.getProperty("user.home"), ".zshrc")) + "; then exit 14; fi; "
				+ "if printf new > new-source; then exit 15; fi; "
				+ "test \"$HOME\" = \"$TMPDIR\" || exit 16; "
				+ "test -z \"$JAVA_TOOL_OPTIONS$BASH_ENV$OPENAI_API_KEY$ANTHROPIC_API_KEY\" || exit 17";
		var result = runner(p).run(shell(p, script, 4096), new CompletableFuture<>());
		assertThat(result.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		assertThat(Files.readString(this.work.resolve("source.txt"))).isEqualTo("baseline");
		assertThat(Files.readString(p.stage().resolve("source.txt"))).isEqualTo("proposal");
		assertThat(this.work.resolve("target")).doesNotExist();
		assertThat(p.stage().resolve("target")).doesNotExist();
		assertThat(new String(logs(p, result)[1], StandardCharsets.UTF_8)).contains("Operation not permitted");
	}

	private CompletableFuture<Long> subscribePid(Path fifo) throws Exception {
		Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", fifo.toString()).start();
		assertThat(mkfifo.onExit().get(5, TimeUnit.SECONDS).exitValue()).isZero();
		var pid = new CompletableFuture<Long>();
		Thread.ofVirtual().start(() -> {
			try { pid.complete(Long.parseLong(Files.readString(fifo).strip())); }
			catch (Exception ex) { pid.completeExceptionally(ex); }
		});
		return pid;
	}
	private static void gone(long pid) throws Exception {
		var handle = ProcessHandle.of(pid);
		if (handle.isPresent()) handle.get().onExit().get(5, TimeUnit.SECONDS);
		assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
	}
	private String waitingChild(Path ready) throws Exception {
		Path hold = this.cache.resolve("hold");
		Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", hold.toString()).start();
		assertThat(mkfifo.onExit().get(5, TimeUnit.SECONDS).exitValue()).isZero();
		// The child blocks on FIFO open, not elapsed time; cancellation must terminate it.
		return "/bin/bash --noprofile --norc -c 'read value < \"$1\"' fixture " + quote(hold)
				+ " & printf '%s' $! > " + quote(ready) + "; wait";
	}

	@Test void cancellationKillsReachableChildAndRetainsRealExit() throws Exception {
		var p = prepare();
		Path ready = this.cache.resolve("ready");
		var pid = subscribePid(ready); // Subscribe before process launch/cancel.
		var cancellation = new CompletableFuture<Void>();
		var result = new CompletableFuture<VerificationRunner.Result>();
		String script = waitingChild(ready);
		Thread.ofVirtual().start(() -> {
			try { result.complete(runner(p).run(shell(p, script, 100), cancellation)); }
			catch (Exception ex) { result.completeExceptionally(ex); }
		});
		long child = pid.get(10, TimeUnit.SECONDS);
		cancellation.complete(null);
		var ended = result.get(10, TimeUnit.SECONDS);
		assertThat(ended.code()).isEqualTo(VerificationRunner.Code.CANCELLED);
		assertThat(ended.evidence().cancelled()).isTrue();
		assertThat(ended.evidence().successful()).isFalse();
		gone(child);
	}

	@Test void changedRevisionInvalidatesAnOtherwiseZeroExit() throws Exception {
		var p = prepare();
		Path ready = this.cache.resolve("ready");
		Path release = this.cache.resolve("release");
		var pid = subscribePid(ready);
		Process mkfifo = new ProcessBuilder("/usr/bin/mkfifo", release.toString()).start();
		assertThat(mkfifo.onExit().get(5, TimeUnit.SECONDS).exitValue()).isZero();
		var result = new CompletableFuture<VerificationRunner.Result>();
		Thread.ofVirtual().start(() -> {
			try { result.complete(runner(p).run(shell(p, "printf '%s' $$ > " + quote(ready)
					+ "; read value < " + quote(release) + "; exit 0", 100), new CompletableFuture<>())); }
			catch (Exception ex) { result.completeExceptionally(ex); }
		});
		pid.get(10, TimeUnit.SECONDS);
		Files.writeString(this.work.resolve("source.txt"), "external edit");
		Files.writeString(release, "continue\n");
		var ended = result.get(10, TimeUnit.SECONDS);
		assertThat(ended.code()).isEqualTo(VerificationRunner.Code.STALE);
		assertThat(ended.evidence().exitCode()).isZero();
		assertThat(ended.accepted(ended.evidence().sourceRevision(), ended.evidence().configurationDigest())).isFalse();
		assertThat(Files.readString(this.work.resolve("source.txt"))).isEqualTo("external edit");
	}

	@Test void boundedLogsCannotBecomeSuccessfulEvidence() throws Exception {
		var p = prepare();
		var result = runner(p).run(shell(p, "/usr/bin/yes x >&2", 1024), new CompletableFuture<>());
		assertThat(result.code()).isEqualTo(VerificationRunner.Code.OUTPUT_LIMIT);
		assertThat(result.evidence().cancelled()).isTrue();
		byte[][] log = logs(p, result);
		assertThat(log[0].length + log[1].length).isEqualTo(1024);
		assertThat(Path.of(result.evidence().cwd()).getParent()).doesNotExist();
	}

	@Test void sourceRevisionMismatchNeverLaunchesRequestedProcess() throws Exception {
		var p = prepare();
		var req = shell(p, "printf launched > " + quote(this.cache.resolve("launched")), 100);
		var mismatch = new VerificationRunner.Request(req.changeSetId(), req.owner(), req.digest(),
				"b".repeat(64), req.argv(), req.timeout(), req.maxOutputBytes());
		var result = runner(p).run(mismatch, new CompletableFuture<>());
		assertThat(result.code()).isEqualTo(VerificationRunner.Code.STALE);
		assertThat(result.evidence()).isNull();
		assertThat(this.cache.resolve("launched")).doesNotExist();
	}

	@Test void timeoutRecordsObservedExitAndKillsChild() throws Exception {
		var p = prepare();
		Path ready = this.cache.resolve("ready");
		var pid = subscribePid(ready);
		var req = request(p, List.of("/bin/bash", "--noprofile", "--norc", "-c",
				waitingChild(ready)), 100, Duration.ofSeconds(1));
		var result = new CompletableFuture<VerificationRunner.Result>();
		Thread.ofVirtual().start(() -> {
			try { result.complete(runner(p).run(req, new CompletableFuture<>())); }
			catch (Exception ex) { result.completeExceptionally(ex); }
		});
		long child = pid.get(10, TimeUnit.SECONDS);
		var ended = result.get(10, TimeUnit.SECONDS);
		assertThat(ended.code()).isEqualTo(VerificationRunner.Code.TIMED_OUT);
		assertThat(ended.evidence().cancelled()).isTrue();
		assertThat(ended.evidence().exitCode()).isNotNull();
		gone(child);
	}

	@Test void interruptionPreservesFlagAfterEvidenceAndProcessCleanup() throws Exception {
		var p = prepare();
		Path ready = this.cache.resolve("ready");
		var pid = subscribePid(ready);
		var result = new CompletableFuture<VerificationRunner.Result>();
		var interruptPreserved = new CompletableFuture<Boolean>();
		String script = waitingChild(ready);
		Thread thread = Thread.ofVirtual().unstarted(() -> {
			try { result.complete(runner(p).run(shell(p, script, 100), new CompletableFuture<>())); }
			catch (Exception ex) { result.completeExceptionally(ex); }
			finally { interruptPreserved.complete(Thread.currentThread().isInterrupted()); }
		});
		thread.start();
		long child = pid.get(10, TimeUnit.SECONDS);
		thread.interrupt();
		var ended = result.get(10, TimeUnit.SECONDS);
		assertThat(ended.code()).isEqualTo(VerificationRunner.Code.CANCELLED);
		assertThat(ended.evidence().cancelled()).isTrue();
		assertThat(interruptPreserved.get(5, TimeUnit.SECONDS)).isTrue();
		gone(child);
	}

	@Test void exactArgvAndLimitsBindConfigurationAndCacheHomeIsRefused() throws Exception {
		var p = prepare();
		var argv = List.of("/bin/bash", "--noprofile", "--norc", "-c", "exit 0", "one argument");
		var first = runner(p).run(request(p, argv, 100, Duration.ofSeconds(20)), new CompletableFuture<>());
		var second = runner(p).run(request(p, argv, 101, Duration.ofSeconds(20)), new CompletableFuture<>());
		var third = runner(p).run(request(p, List.of("/bin/bash", "--noprofile", "--norc", "-c", "exit 0",
				"one", "argument"), 100, Duration.ofSeconds(20)), new CompletableFuture<>());
		assertThat(first.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		assertThat(second.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		assertThat(third.code()).isEqualTo(VerificationRunner.Code.SUCCEEDED);
		assertThat(first.evidence().configurationDigest()).isNotEqualTo(second.evidence().configurationDigest())
				.isNotEqualTo(third.evidence().configurationDigest());
		var badCache = new VerificationRunner(p.store(),
				BashSandbox.verificationPolicy(List.of(Path.of(System.getProperty("user.home")))), Clock.systemUTC());
		var refused = badCache.run(shell(p, "printf launched > " + quote(this.cache.resolve("launched")), 100),
				new CompletableFuture<>());
		assertThat(refused.code()).isEqualTo(VerificationRunner.Code.INVALID_CACHE_ROOT);
		assertThat(refused.evidence()).isNull();
		assertThat(this.cache.resolve("launched")).doesNotExist();
	}
}
