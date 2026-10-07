# Task16 verification runner contract

`VerificationRunner(ChangeStore, LaunchPolicy, Clock)` owns isolated verification.
`run(Request, CompletableFuture<Void> cancellation)` returns `Result` and declares
`IOException`. Parent owns the runner bean and command integration.

`Request(UUID changeSetId, ChangeOwner owner, String digest, String sourceRevision,
List<String> argv, Duration timeout, int maxOutputBytes)` fixes the exact proposal,
proposed-tree revision, original argv boundaries, wall limit and aggregate raw log
limit. No actual-workspace execution option exists.

`LaunchPolicy.prepare(Context, List<String>)` returns `Launch(List<String> command,
Map<String,String> environment, String policyDigest)` or typed `Refusal`.
`Context(Path root, Path temp, Path liveRoot, Path agentHome)` contains canonical
private clone/temp and excluded original authorities. The bash plugin contributes
this SPI using a deny-default macOS Seatbelt profile and a real denial probe.
Unsupported OS refuses before requested command launch.

`Code` outcomes are SUCCEEDED, NON_ZERO_EXIT, CANCELLED, TIMED_OUT, OUTPUT_LIMIT,
STALE, SOURCE_CHANGED, UNSUPPORTED_SANDBOX, INVALID_CACHE_ROOT, IO, CLEANUP_FAILED.
`Result(Code code, VerificationEvidence evidence)` has nullable evidence for
pre-launch refusals. `accepted(revision, configuration)` requires SUCCEEDED,
evidence.successful(), and evidence.validFor both exact values.

The runner validates owner/digest/state, live baseline, and retained proposed
stage before copying and again before launching. Filtered symlink identities are
retained in revision hashing but links are never materialized in the clone.
Source revision is the reviewed proposed-tree digest, not Git HEAD. A changed live
baseline or retained stage after execution returns STALE even with exit zero.
Pending/approved proposals require live baseline equality; APPLIED proposals
require live proposed-tree equality. All other proposal states refuse as STALE.
For naturally completed but stale verification, the evidence retains the honest
zero exit and `cancelled=false`; consumers must use `Result.accepted`, not
`evidence.successful()` alone. Staleness is an outcome, not fabricated cancellation.
Configuration hashes original argv, timeout/output limits, source-filter protocol,
OS identity, and policy digest (canonical Java runtime/cache scope/profile version).
The running Java version is also bound into configuration.
It excludes credentials and raw environment.

Evidence `cwd` is the actual canonical executed clone; `sourceCwd` is the canonical
store workspace. Exit is observed only after process exit, never synthesized.
Cancellation, timeout, overflow and reader/cleanup failures set cancelled so they
cannot masquerade as successful tests. Timestamps use the injected clock.

Logs use binary framing: big-endian stdout length, exact retained stdout bytes,
big-endian stderr length, exact retained stderr bytes. The SHA-256 covers that exact
framing passed to `recordEvidence`. The aggregate payload is bounded;
overflow retains a bounded prefix and returns OUTPUT_LIMIT, never accepted.
Readers drain separate real pipes; completion requires process exit and both EOFs.

The environment is cleared and replaced with fixed PATH/locale and private
HOME/TMPDIR. Java runtime reads derive only from running `java.home`. Configured
cache roots must be existing canonical scoped directories outside source,
agent home, and user home (including descendants). Only top-level clone `target`,
`build`, `dist`, `.gradle`, and private temp/cache roots are writable. All other
clone source and live/home/auth contents are denied at the OS boundary.

Cancellation subscribes before launch and races exit/EOF/overflow under a bounded
deadline. Reachable descendants are killed deepest first, then parent; output
streams and reader threads are joined under one five-second cleanup budget.
Interrupt status is restored after recording and cleanup. Detached/reparented
processes are not claimed to be tracked. The private clone/temp tree is deleted
after recording; evidence and byte logs remain in ChangeStore. Cleanup failure is
typed and leaves the private path for inspection rather than claiming success.
Pre-launch private-tree cleanup failure is a typed `Refusal(CLEANUP_FAILED)`;
post-process cleanup failure returns `Result(CLEANUP_FAILED, evidence)`.

Maven is explicitly UNRUN in this unit; parent owns compilation and all follow-up.
