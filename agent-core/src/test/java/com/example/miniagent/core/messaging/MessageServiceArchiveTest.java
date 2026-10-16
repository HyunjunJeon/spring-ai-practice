package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.example.miniagent.core.delegation.runtime.DefaultDelegationRuntime;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.FileRunStore;
import com.example.miniagent.core.run.RunBudget;
import com.example.miniagent.core.run.RunId;
import com.example.miniagent.core.run.RunSpec;
import com.example.miniagent.core.run.RunStoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.messaging.MessageStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The console archive is the only read path after the parent ends or the application restarts, and the
 * only authorization on it is the real parent run: conversation, run kind and canonical workspace.
 */
class MessageServiceArchiveTest {
	private static final String CONVERSATION = "original-conversation";
	private static final Key KEY = new Key("parent-run", "plan", "child");
	@TempDir Path root;
	@TempDir Path elsewhere;
	private final DefaultDelegationRuntime runtime = mock(DefaultDelegationRuntime.class);
	private Path home, workspace;
	private FileRunStore runs;
	private MessageService service;

	@BeforeEach void createParentRunWithMailbox() throws Exception {
		root = root.toRealPath();
		elsewhere = elsewhere.toRealPath();
		home = root.resolve("agent-home");
		workspace = Files.createDirectory(root.resolve("workspace"));
		runs = new FileRunStore(home);
		runs.create(spec("parent-run", null, null)).close();
		service = new MessageService(new MessageStore(home), runtime);
	}

	private RunSpec spec(String run, String parentRun, String childId) {
		String digest = "0".repeat(64);
		return new RunSpec(RunSpec.SCHEMA_VERSION, new RunId(run), "conversation-" + run, CONVERSATION,
			parentRun == null ? null : new RunId(parentRun), childId, new ModelRef("fake", "test"), "fake",
			workspace.toString(), RunBudget.defaults(), digest, digest, Instant.EPOCH);
	}

	private static Receipt receipt(int index, State state, String response, String claim) {
		return new Receipt("m-" + index, "parent", "body " + index, state, "original-run",
			state == State.RUNNING ? null : "execution-run", response, claim);
	}

	private static void seed(MessageStore store, Key key, List<Receipt> entries) throws IOException {
		store.transact(threads -> { replace(threads, find(threads, key), entries); return null; });
	}

	@Test void anOwnedParentRunPagesTheStoredReceiptsThroughAFreshServiceAfterRestart() throws Exception {
		var entries = new ArrayList<Receipt>();
		for (int i = 0; i < 20; i++) entries.add(receipt(i, State.DELIVERED, "answer " + i, "claim"));
		seed(new MessageStore(home), KEY, entries);
		var restarted = new MessageService(new MessageStore(home), runtime);
		var runsBefore = runs.list();
		var first = restarted.archive(CONVERSATION, workspace, runs, "parent-run", "plan", "child", 0);
		assertThat(first.version()).isEqualTo(1);
		assertThat(first.nextOffset()).isEqualTo(PAGE_SIZE);
		assertThat(first.entries()).containsExactlyElementsOf(entries.subList(0, PAGE_SIZE));
		var second = restarted.archive(CONVERSATION, workspace, runs, "parent-run", "plan", "child", PAGE_SIZE);
		assertThat(second.nextOffset()).isEqualTo(20);
		assertThat(second.entries()).containsExactlyElementsOf(entries.subList(PAGE_SIZE, 20));
		assertThat(runs.list()).isEqualTo(runsBefore);
		verifyNoInteractions(runtime);
	}

	@Test void aRunningReceiptWhoseClaimIsNotLiveInThisProcessIsArchivedAsUncertain() throws Exception {
		var delivered = receipt(0, State.DELIVERED, "answer", "claim");
		seed(new MessageStore(home), KEY, List.of(delivered, receipt(1, State.RUNNING, null, "dead-instance:claim")));
		var page = new MessageService(new MessageStore(home), runtime)
			.archive(CONVERSATION, workspace, runs, "parent-run", "plan", "child", 0);
		assertThat(page.entries()).extracting(Receipt::state).containsExactly(State.DELIVERED, State.UNCERTAIN);
		assertThat(page.entries().getFirst()).isEqualTo(delivered);
		assertThat(page.entries().getLast().response()).isNull();
	}

	@Test void aSymlinkedWorkspaceThatResolvesToTheRunCwdIsAccepted() throws Exception {
		seed(new MessageStore(home), KEY, List.of(receipt(0, State.DELIVERED, "answer", "claim")));
		Path alias = Files.createSymbolicLink(root.resolve("alias"), workspace);
		var page = service.archive(CONVERSATION, alias, runs, "parent-run", "plan", "child", 0);
		assertThat(page.entries()).extracting(Receipt::id).containsExactly("m-0");
	}

	@Test void aForeignConversationIsRefusedEvenWhenReceiptsExist() throws Exception {
		seed(new MessageStore(home), KEY, List.of(receipt(0, State.DELIVERED, "answer", "claim")));
		assertThatThrownBy(() -> service.archive("other-conversation", workspace, runs, "parent-run", "plan", "child", 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("Foreign message archive");
	}

	@Test void aWorkspaceOutsideTheRunCwdIsRefusedEvenWhenReceiptsExist() throws Exception {
		seed(new MessageStore(home), KEY, List.of(receipt(0, State.DELIVERED, "answer", "claim")));
		assertThatThrownBy(() -> service.archive(CONVERSATION, elsewhere, runs, "parent-run", "plan", "child", 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("Foreign message archive");
	}

	@Test void aChildRunIsRefusedEvenWithTheOwningConversationWorkspaceAndReceipts() throws Exception {
		runs.create(spec("child-run", "parent-run", "child")).close();
		seed(new MessageStore(home), new Key("child-run", "plan", "child"),
			List.of(receipt(0, State.DELIVERED, "answer", "claim")));
		assertThatThrownBy(() -> service.archive(CONVERSATION, workspace, runs, "child-run", "plan", "child", 0))
			.isInstanceOf(IllegalArgumentException.class).hasMessage("Foreign message archive");
	}

	@Test void authorizationHappensBeforeAnyMailboxIsOpened() {
		assertThatThrownBy(() -> service.archive("other-conversation", workspace, runs, "parent-run", "plan", "child", 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(home.resolve("messages")).doesNotExist();
	}

	@Test void anUnknownParentRunIsNotFound() {
		assertThatThrownBy(() -> service.archive(CONVERSATION, workspace, runs, "missing-run", "plan", "child", 0))
			.isInstanceOfSatisfying(RunStoreException.class,
				ex -> assertThat(ex.code()).isEqualTo(RunStoreException.Code.NOT_FOUND));
		assertThat(home.resolve("messages")).doesNotExist();
	}

	@Test void aThreadWithoutReceiptsArchivesAsAnEmptyPage() throws Exception {
		var page = service.archive(CONVERSATION, workspace, runs, "parent-run", "plan", "other-child", 0);
		assertThat(page.entries()).isEmpty();
		assertThat(page.version()).isZero();
		assertThat(page.nextOffset()).isZero();
	}
}
