# Model foundation integration contract

All types are in `com.example.miniagent.core.model`. No Spring configuration is added here.

```java
record ModelRef(String provider, String model)
record ModelEntry(ModelRef ref, String wireFamily, ModelUnavailableException.Code unavailableCode)
interface ProviderAuth { boolean available(); }
interface ModelProvider {
    String providerId();
    List<ModelEntry> models();
    ProviderAuth auth();
    ChatModel create(ModelRef ref, Map<String, String> options);
    default Set<String> supportedOptions(); // empty: options are opt-in
    default Set<String> privateMetadataKeys(); // empty
}
interface AgentChatClientFactory { ChatClient create(ChatModel model); }
record ModelSelection(ModelRef ref, Map<String, String> options) {
    ModelSelection(ModelRef ref);
    String optionsFingerprint();
}
record ModelBinding(UUID bindingId, ModelRef ref, String optionsFingerprint,
    String wireFamily, ChatModel chatModel, ChatClient client)
final class ModelRegistry {
    ModelRegistry(List<ModelProvider> providers, AgentChatClientFactory clientFactory);
    List<ModelEntry> models();
    ModelEntry requireAvailable(ModelRef ref);
    ModelBinding bind(ModelSelection selection);
    Set<String> privateMetadataKeys(ModelRef ref);
}
final class ModelUnavailableException extends IllegalStateException {
    enum Code { AUTH_MISSING, UNKNOWN_MODEL }
    Code code();
    ModelRef ref();
}
interface SessionModelStore {
    Optional<ModelSelection> load(String sessionId) throws IOException;
    void save(String sessionId, ModelSelection selection) throws IOException;
}
final class FileSessionModelStore implements SessionModelStore {
    FileSessionModelStore(Path agentHome) throws IOException;
}
record TurnContext(String sessionId, String turnId, Path cwd, ModelBinding binding,
    String runId, String attemptId) {
    TurnContext(String sessionId, String turnId, Path cwd, ModelBinding binding);
    Scope open();
    static Optional<TurnContext> current();
    final class Scope implements AutoCloseable { void close(); }
}
```

`ModelEntry.unavailableCode` and run/attempt IDs may be null. The registry dynamically
checks authentication, validates duplicate provider/model IDs and never falls back.
Every bind creates a fresh independent model, client, and UUID; no mutable model cache.
The parent supplies the configured official ChatClient factory (advisors, tools,
observability, customizers, resolution fallback off and lazy MCP).

Options are immutable string values with explicit noncredential keys:
`effort`, `reasoningEffort`, `thinkingLevel`, `temperature`, `topP`, `topK`,
`maxTokens`, `seed`, `stopSequences`. Each provider additionally opts into its supported
keys. The provider creates fresh configured options; the registry never mutates
an existing model. Fingerprints are SHA-256 over canonical sorted, length-framed UTF-8.

The store uses `<agentHome>/model-selections/<sessionId>.json`, schema version 1,
separate from memory. Only ref/options are persisted, never a binding/client/model.
IDs allow ASCII alphanumeric first character followed by alphanumeric, `_`, `-`
(maximum 128 characters). Corrupt/unknown schema or fields and unsafe paths fail with
IOException; unsafe IDs/options fail with IllegalArgumentException. POSIX-private
directory/temp files, file force, atomic replace and directory force are required.
No non-atomic fallback is used.

TurnContext scope is explicit, thread-confined and nestable; closing restores the
previous context. Direct synchronous compaction can use
`TurnContext.current().orElseThrow().binding().chatModel()`. Async work must carry
the captured context explicitly and open it on its execution thread.

## Verified delivery

Executed once from `mini-agent/`:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -pl agent-core -am test
```

Exit 0, 43 tests, zero failures/errors/skips (12 registry/scope tests and 31 store
tests). Both changed Java directories have zero LSP diagnostics. Build evidence:
`.omo/evidence/agent-evolution/phase1/model-foundation-test.log`.
The build retains two pre-existing `defaultToolCallbacks` removal warnings in
`AgentAutoConfiguration.java:107,110`; that file was not edited.

The tested decision is fresh per-turn factories rather than mutable model caches.
Store tests cover memory-absent restart, untouched conversation bytes, replacement
cleanup, owner-only permissions, strict schemas, credential-field rejection,
bounded reads, traversal IDs and symlink rejection. Registry tests exercise actual
official ChatClient calls against independent in-process fake models.

Assumptions: the application owns agent home and provider implementations honor
the fresh-model factory contract. POSIX permissions/atomic rename/directory force
are required; no portable or non-atomic fallback is claimed. ThreadLocal context
does not propagate itself to asynchronous execution. Provider adapters, configured
client assembly, application wiring and complete tool-roundtrip migration remain
the parent's integration scope. No remote provider calls or commits were made.

Exact authored files:

- `.omo/drafts/model-foundation-contract.md`
- `mini-agent/agent-core/pom.xml`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelRef.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelEntry.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelProvider.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ProviderAuth.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelBinding.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelRegistry.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelUnavailableException.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/AgentChatClientFactory.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/ModelSelection.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/SessionModelStore.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/FileSessionModelStore.java`
- `mini-agent/agent-core/src/main/java/com/example/miniagent/core/model/TurnContext.java`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/model/ModelRegistryTest.java`
- `mini-agent/agent-core/src/test/java/com/example/miniagent/core/model/FileSessionModelStoreTest.java`
