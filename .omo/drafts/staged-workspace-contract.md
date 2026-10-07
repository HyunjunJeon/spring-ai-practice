# Task14 staged workspace ABI

Implementation complete; Maven is reserved by the parent. All new core types
are in `com.example.miniagent.core.change`. No apply/undo/recovery is implemented.

`StagedWorkspace.open(ChangeStore, TurnContext, Clock)` allocates an owner stage
through `Lease.stagingDirectory` before copying or proposing. Public accessors:
`root()`, `liveRoot()`, `tempDirectory()`, `policy()`, `owner()`.
`resolve(String, boolean mutation, List<Path> externalReadRoots)` maps relative
and live absolute workspace paths into the stage; external roots are read-only.
`validateWrite(Path, String)` enforces file/aggregate/entry bounds before file-tool
publication. Shell effects are bounded when scanned; OS confinement, not argv
analysis, prevents live effects.
`record()` forces retained stage files/directories, then publishes the actual
full baseline/proposed trees, diffs, model and binding UUID. `close()` records
remaining effects, deletes only its private temp, releases resources, and
retains stage/proposals. `openScope()` exposes a lexical thread-local scope;
`current()` and `from(ToolContext)` return Optional. Agent passes the captured
stage under `ToolContextKeys.STAGED_WORKSPACE`; mutable global lookup is not tool
authority.

`StagingToolInterceptor.Policy.declarations()` returns
`Map<String, Declaration>`. `Declaration(String digest, Kind kind,
List<Path> readRoots, Consumer<StagedWorkspace> preflight)` has a three-argument
constructor for declarations without preflight. `Kind` is READ, FILE_WRITE,
SHELL. `StagingToolInterceptor(Supplier<List<Policy>>)` has `capture(stage)`,
`release(stage)`, and the SPI `intercept`/`getOrder`. Refusal codes are
UNKNOWN_TOOL, MISSING_STAGE, DEFINITION_DRIFT. Captured local declarations match
exact callback digests; other read-only contributions are allowed only by
`ToolJournalInterceptor.declaredRead(RunContext, ToolDefinition)` against the
immutable attempt snapshot. Managed invocation checks these before
budget/permission/journal.
Unknown tools are refused, including unknown MCP. Production FS/bash factories
contribute their actual callback definitions, not authority based on names.

PathPolicy's package-private staged-view factory confines its home exception to
the allocated stage. It retains the real home and source exclusions; it cannot
authorize home/auth or sibling stages. Snapshot defaults: 16 MiB per file,
10,000 entries, 256 MiB aggregate copied content. Symlinks are fingerprinted as
link text but never copied as dereferenceable links. Link mutations are denied.
Workspace skill contents are copied for reads and are immutable to tools.

RunExecutor creates a fresh stage for every managed attempt. Earlier pending or
approved proposals for that run are superseded without deleting history. Tools
record after effects, and final cleanup records effects even on interruption.
Journal/drift remains a live-source view; staged writes do not advance live
source fingerprints or grant safe retry of UNKNOWN effects.
`ExternalChangeScanner(PathPolicy, Map<String,Fingerprint>).changed()` observes
only live source. Live changes transition current owner proposals to STALE.
`ChangeSetRecorder(ChangeStore, ChangeOwner, TurnContext, Clock,
Map<String,Fingerprint>)` exposes `record(PathPolicy,
Map<String,Fingerprinter.Observation>, Map<String,Fingerprint>, boolean
sourceChanged)` and performs no live mutation.

`RunExecutor.staging(StagingToolInterceptor, Supplier<ChangeStore>)` returns
the executor and is called by production auto-configuration before use. It
keeps ChangeStore initialization lazy; ordinary turns do not allocate a stage.
Direct executor construction still allocates stages using the request store's
real private home, but custom callers own their explicit interceptor chain.
`DriftGate.stagingConfiguration(Supplier<String>)` includes staged protocol and
cache scope configuration in exact drift snapshots. Home and source must not
overlap in either direction; a workspace below agent.home is refused rather
than silently snapshotted as an empty tree.

Managed bash always uses macOS sandbox-exec, regardless of legacy sandbox mode.
It verifies the OS sandbox using a bounded probe before the requested process.
Writes are confined to stage, private sibling temp, and explicitly configured
`agent.changes.shell-cache-roots` (empty by default). Cache roots must exist and
cannot overlap live source or agent.home or be the user's home itself. These
explicit cache roots grant read/write authority, so they must contain only the
cache content the operator intends to expose. Legacy sandbox writable roots do
not become managed cache roots. There are no generic home, /tmp, /dev, or
inherited writable-root grants; /dev/null is a literal exception. Environment is cleared and
only fixed PATH, private HOME/TMPDIR, and locale are supplied. Bash is invoked
without profile/rc files. The root directory itself has literal read access
required by this macOS runtime; that rule does not grant descendant reads.
Path metadata reads are allowed for OS alias resolution, including declared
cache paths reached through `/var`; file contents and writes stay scoped.
Network and file contents outside the filtered stage/system runtime are denied.
Excluded names use separate bounded regex literals because the original combined
literal failed Seatbelt parsing. Unsupported sandbox is a typed refusal before user-command
launch. Legacy bash retains its existing configuration and environment.
`BashSandbox.Refusal.code()` is UNSUPPORTED_SANDBOX or INVALID_CACHE_ROOT.
Production shell preflight validates the actual OS profile before budget and
permission; direct BashTool calls also validate before requested process launch.
The probe writes only a private temporary artifact and attempts a denied write
to an owner-state sibling, never live source.

Portable JDK path operations still assume cooperating writers; this is not an
atomic defense against hostile concurrent ancestor replacement. OS confinement
is the managed shell boundary. Stage history remains on disk for later commands.

## Delivered files and evidence

Core main:
- `core/change/{StagedWorkspace,ChangeSetRecorder,StagingToolInterceptor,ExternalChangeScanner}.java` (new)
- `core/change/PathPolicy.java`
- `core/{ToolContextKeys,Agent,AgentAutoConfiguration}.java`
- `core/run/{RunExecutor,RunCommandAutoConfiguration,DriftGate,ToolJournalInterceptor}.java`

Plugin main:
- `plugin-fs/.../fs/{FileTools,FsPluginAutoConfiguration}.java`
- `plugin-bash/.../bash/{BashTool,BashSandbox,BashPluginAutoConfiguration}.java`

New tests:
- `agent-core/.../change/StagedWorkspaceTest.java` (7 tests)
- `plugin-bash/.../bash/StagedBashTest.java` (4 tests)
- `agent-app/.../app/ImmediateToolMigrationTest.java` (4 tests)
- `agent-app/.../app/ShellScopeTest.java` (1 test)

Existing managed tests adjusted only for outside-home layout and staged-default
assertions: RunExecutorTest, RunBudgetIntegrationTest, RunCommandTest,
RunConsoleStateTest. BashToolTest's factory invocation adds an empty managed
cache list; legacy immediate assertions remain.

All 16 new tests and all affected existing tests are explicitly UNRUN. No Maven,
compiler command, test process, shell probe, dependency install, paid/external
call, commit, plan/ledger edit or apply/undo operation was performed by this unit.
The parent's separate SANDBOX_LAUNCH_OK probe is launch evidence only; confinement
and cancellation require the unrun real shell tests.

Final LSP inspection: zero errors and zero new warnings across changed core,
FS/bash and test files, without truncated results or file failures. The five
pre-existing Spring AI deprecation warnings in AgentAutoConfiguration remain:
ChatClientCustomizer and four defaultToolCallbacks calls. Their statements were
not changed by task14.

The chosen boundary is a captured owner stage plus actual OS confinement, not
rewriting tool JSON to grant home authority or trusting shell argv heuristics.
Mode/source preservation, persisted diffs, supersession/staleness, pre-permission
unknown/path refusal, minimal environment/startup suppression, unsupported
refusal and cancellation are asserted by tests awaiting parent execution.
Snapshot/proposal publication still assumes cooperating writers. Process cleanup
retains the existing reachable-descendant guarantee; the sandbox, not cleanup,
prevents a detached descendant from mutating live source.
