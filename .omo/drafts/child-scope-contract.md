# Task19 child scope ABI

`ChildScopeFactory.create(TurnContext parent, ChildSpec child, ToolScope parentScope)`
returns an attempt-local `ChildScopeFactory.Scope`, implementing `AutoCloseable`.
`metadata()` is immutable `(childId, role, effective ModelRef, logical ToolScope,
parentConversationId, parentRunId)`; no parent selection, history, client, or credentials are copied.
`call()` synchronously starts the existing durable `RunExecutor` with the child task,
captured ref, fresh binding and its reservation. `context()` returns the captured
child `TurnContext` once the executor enters its attempt; `runContext()` returns
that exact durable context. `executor()` exposes the existing cancellation API.
`bind(RunContext)` is the capture adapter entry point: it accepts only an active
durable run carrying this factory's observed model and an exact owner stage.
`call()` invokes it automatically through TurnRunner.
One Scope represents one new run, not a scheduler or recovery service. Retry
reservation and aggregation belong to task20/21. `close()` revokes tool authority;
call cancellation must first use the executor. A completed narrative is not
verification evidence.

Construction requires raw callbacks/providers, ordered existing interceptors,
staging policies, trusted read policies, scoped advisor contributions, and the
existing RunStore/RunRequestStore/SessionModelStore/broker/clock. Spring assembly
provides the factory lazily via AgentAutoConfiguration. Stores remain the existing
bounded/private implementations, with independent child conversation/run/attempt
UUIDs allocated by RunExecutor; no new store or budget subsystem.

`ModelRegistry.scoped(AgentChatClientFactory)` shares provider factories but not
the parent's client assembly. Ordinary `bind` remains unchanged.
The factory's default `modelBoundary(ChatModel)` is identity for ordinary
bindings; child assembly wraps captured provenance before constructing the
binding, so both its client and its summarizer use that same observed boundary.
`AgentChatClientFactory.ScopedAssembly` contains only the explicit system prompt,
frozen callbacks, scoped advisors and tool advisor order. Its fresh official
ChatClient has no parent customizers, default providers, or parent advisors.
Models with preinstalled tool callbacks/context are refused rather than assuming
a request callback list clears them.

`TurnRunner.capture(TurnContext)` is the explicit attempt capture adapter used by
the isolated Agent. It verifies the existing executor's RunContext and owner
stage before adding `ToolContextKeys.CHILD_SCOPE`. No callback accepts a missing,
foreign or merely equivalent context, or falls back to ThreadLocal state.

`McpToolSnapshot.capture(callbacks, providers, allowedNames)` freezes definitions
and callback identities. Every model round and tool execution checks those
captured tools still exist with the same callback object identity and schema.
Later additions are never advertised/resolved; removals, callback replacement
(including identical schemas) and schema drift revoke execution.
Unknown model tool requests are refused before the tool manager can resolve them.
Parent real Spring QA distinguished MethodToolCallbackProvider: its static
annotated method set creates fresh wrapper objects when queried. Child factory
materializes that static provider once to preserve executable identity; other
providers (including dynamic MCP) are still queried and compared on every gate.
Empty Spring callback options may be null and denote no advertised callbacks;
the exact child tool context remains mandatory.

`ScopedToolInterceptor` uses trusted staging declarations to project filesystem
arguments into the exact owner stage, then RolePolicy, before the original
budget/permission/journal chain. Reads are limited to projected parent authority;
implement writes are stage-only; verify writes require explicit generated roots.
PathPolicy exclusions, symlink checks and auth/home defense still apply.
Shell declarations alone do not prove bounded read/write effects: they are denied
without a richer trusted classification adapter. No argv heuristic is authority.
Unclassified external MCP and generic read declarations with unbounded resources
are denied. No sandbox capability is widened.

`AdvisorContribution.create(ChatModel childModel, Supplier<TurnContext> authority)`
creates fresh advisors for the child binding. Memory contributes its own bounded
JSONL memory wrapped by an exact-ID ScopeMemory; compaction captures the child
provenance model directly. Memory-off contributes nothing.

Scheduler overload: `create(parent, child, parentScope, Boundaries)`.
`Boundaries.beforeModel(Metadata, RunContext)` runs before every model round and
compaction; `beforeTool` runs after scope refusal and before the existing tool
budget/approval/journal. `observed` reports the existing writer's cumulative
record after model/tool and final executor cleanup, including exceptional paths.
Scheduler consumes runId/sequence to avoid double counting cumulative usage.
The same scheduler reservation must cover all retry scopes. Hooks must not reset
RunContext's meter. Metadata lineage is captured even for unmanaged parents
(parentRunId null). Existing RunExecutor currently writes parentConversationId;
its RunSpec parentRunId/childId fields remain task20/21's durable lineage bridge.

`Boundaries.usage(Metadata, RunContext, RunEvent.UsageRecorded)` delivers each
newly persisted usage delta at the exact captured owner, including model rounds,
compaction, tools and final active time. A forwarding RunWriter capture adapter
queues only usage events acknowledged by the existing durable writer. Successful
hook delivery drains that bounded per-call queue; no log parser or second
persistence implementation is added. Consumers use runId/attemptId/callId as durable
idempotency keys; a throwing hook can be redelivered during cleanup. Cumulative
`observed` is informational, never a ledger delta.

## Task19 handoff

Maven, compiler commands and all tests are explicitly **UNRUN** in this lane.
Parent owns runtime verification and any follow-up. The authored tests are
`agent-core/.../delegation/ChildScopeFactoryTest` (11 methods) and
`plugin-memory/.../memory/ChildMemoryIsolationTest` (2 methods). They exercise
the actual ChatClient/ToolCallingAdvisor, FileRunStore/RunExecutor, owner stage,
fake models/local callbacks and Spring memory contribution. Cases include
inherited/explicit model isolation, independent UUIDs, parent JSONL/selection
preservation, dynamic addition/removal/same-schema replacement/schema drift, plain-call/foreign-context
refusal, role gates before permission/budget, generated-only verify writes,
excluded/symlink paths, unknown external/shell effects, parent-only tools,
provider default callbacks, shared cap hooks and durable delta accounting,
memory inside/outside/off and child-only compaction.

Changed Java paths, relative to `mini-agent/`:

- `agent-core/src/main/java/com/example/miniagent/core/Agent.java`
- `agent-core/src/main/java/com/example/miniagent/core/AgentAutoConfiguration.java`
- `agent-core/src/main/java/com/example/miniagent/core/ToolContextKeys.java`
- `agent-core/src/main/java/com/example/miniagent/core/TurnRunner.java` (new)
- `agent-core/src/main/java/com/example/miniagent/core/model/ModelRegistry.java`
- `agent-core/src/main/java/com/example/miniagent/core/model/AgentChatClientFactory.java`
- `agent-core/src/main/java/com/example/miniagent/core/delegation/ChildScopeFactory.java` (new)
- `agent-core/src/main/java/com/example/miniagent/core/delegation/ScopedToolInterceptor.java` (new)
- `agent-core/src/main/java/com/example/miniagent/core/delegation/McpToolSnapshot.java` (new)
- `agent-core/src/test/java/com/example/miniagent/core/delegation/ChildScopeFactoryTest.java` (new)
- `plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/MemoryPluginAutoConfiguration.java`
- `plugins/plugin-memory/src/main/java/com/example/miniagent/plugin/memory/ScopeMemory.java` (new)
- `plugins/plugin-memory/src/test/java/com/example/miniagent/plugin/memory/ChildMemoryIsolationTest.java` (new)

The other authored file is this ABI document. MCP uses the existing official
ToolCallbackProvider contribution directly; no MCP startup/configuration or
provider behavior was changed. No parent-owned contracts, scheduler, ledger,
run/recovery services, source/apply engines, command/UI, FS/bash files, POM,
plan, existing tests or Git metadata were edited.

Final changed-file LSP: zero errors and zero new warnings across all 13 Java
files. AgentAutoConfiguration retains its five existing Spring AI removal
warnings (ChatClientCustomizer and four defaultToolCallbacks calls).
Pinned 2.0.1 jar signatures were inspected using javap; documentation retrieval
was supplementary, not substituted for the pinned API.

Integration boundary: lineage is immutable in Metadata, while durable RunSpec
parentRunId/childId population remains the parent's task20/21 bridge. Scope
supports one start; retry scheduling/remaining reservations and recovery are
parent-owned. No narrative is promoted to verification evidence. Shell and
unbounded external effects fail UNKNOWN_EFFECT; rich process/cache adapters
remain task20/22. Existing PathPolicy exclusions remain authoritative, including
excluded build/cache directory names; generic file callbacks do not acquire an
exception for those names. Snapshot/portable path checks retain the repository's
cooperating-writer assumption and do not claim an OS sandbox.
