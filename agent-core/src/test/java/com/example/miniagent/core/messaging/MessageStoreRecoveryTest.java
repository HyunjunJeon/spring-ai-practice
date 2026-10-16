package com.example.miniagent.core.messaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.example.miniagent.core.change.Fingerprinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.example.miniagent.core.messaging.MessageStore.*;
import static org.assertj.core.api.Assertions.*;

/** Every storage state the mailbox cannot trust is refused as it is found, without rewriting a byte. */
class MessageStoreRecoveryTest {
	private static final Key KEY = new Key("parent-run", "plan", "child");
	private static final Receipt RECEIPT = new Receipt("m-0", "parent", "pay 10", State.DELIVERED,
		"original-run", "execution-run", "paid", "claim");
	@TempDir Path root;
	private Path home, messages, data;
	private MessageStore store;

	@BeforeEach void useAnEmptyHome() throws IOException {
		root = root.toRealPath();
		home = root.resolve("home");
		messages = home.resolve("messages");
		data = messages.resolve("mailboxes.json");
		store = new MessageStore(home);
	}

	/** One real committed transaction: the lock, the data file and their private modes come from the store. */
	private void commitOneReceipt() throws IOException {
		store.transact(threads -> { replace(threads, find(threads, KEY), List.of(RECEIPT)); return null; });
	}

	private Map<String, String> snapshot() throws IOException {
		var files = new TreeMap<String, String>();
		try (var paths = Files.list(messages)) {
			for (Path path : paths.toList())
				files.put(path.getFileName().toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
		}
		return files;
	}

	private void assertReadRefused(String message) throws IOException {
		var before = snapshot();
		assertThatThrownBy(() -> store.read(KEY, 0)).isExactlyInstanceOf(IOException.class).hasMessage(message);
		assertThat(snapshot()).isEqualTo(before);
	}

	private void assertReadStillWorks() throws IOException {
		assertThat(store.read(KEY, 0).entries()).containsExactly(RECEIPT);
	}

	@ParameterizedTest @ValueSource(ints = {0, 1, 3})
	void aSchemaOtherThanTwoIsRefusedBecauseResponseCompletenessIsUnknown(int schema) throws Exception {
		commitOneReceipt();
		Files.writeString(data, "{\"schema\":" + schema + ",\"digest\":\"ignored\",\"threads\":[]}");
		assertReadRefused("Unsupported mailbox schema; response completeness unknown");
	}

	@Test void aChecksumMismatchIsRefusedAfterTheStoredContentWasEdited() throws Exception {
		commitOneReceipt();
		Files.writeString(data, Files.readString(data).replace("pay 10", "pay 99"));
		assertReadRefused("Corrupt mailbox checksum");
	}

	@Test void aCorruptedDigestFieldIsRefusedAsAChecksumMismatch() throws Exception {
		commitOneReceipt();
		Files.writeString(data, Files.readString(data).replaceFirst("\"digest\":\"[0-9a-f]{64}\"",
			"\"digest\":\"" + "0".repeat(64) + "\""));
		assertReadRefused("Corrupt mailbox checksum");
	}

	@Test void mailboxDataWithoutTheStableLockIsRefusedAndNoLockIsInvented() throws Exception {
		commitOneReceipt();
		Files.delete(messages.resolve("store.lock"));
		assertReadRefused("Missing stable mailbox lock");
		assertThat(messages.resolve("store.lock")).doesNotExist();
	}

	@ParameterizedTest @ValueSource(strings = {".pending-x7q2.tmp", "notes.txt"})
	void aStrayFileMeansRecoveryIsRequiredUntilItIsRemoved(String stray) throws Exception {
		commitOneReceipt();
		Files.createFile(messages.resolve(stray), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		assertReadRefused("Mailbox storage recovery required");
		assertReadRefused("Mailbox storage recovery required");
		Files.delete(messages.resolve(stray));
		assertReadStillWorks();
	}

	@ParameterizedTest @ValueSource(strings = {
		"{\"schema\":2,\"digest\":\"x\",\"threads\":[],\"extra\":1}",
		"{\"schema\":2,\"schema\":2,\"digest\":\"x\",\"threads\":[]}",
		"{\"schema\":2,\"digest\":\"x\",\"threads\":[]} {}",
		"{\"schema\":2,\"digest\":"})
	void unknownDuplicateTrailingOrTruncatedJsonIsRefusedAsAFailedTransaction(String json) throws Exception {
		commitOneReceipt();
		Files.writeString(data, json);
		var before = snapshot();
		assertThatThrownBy(() -> store.read(KEY, 0)).isExactlyInstanceOf(IOException.class)
			.hasMessage("Mailbox transaction refused").hasCauseInstanceOf(RuntimeException.class);
		assertThat(snapshot()).isEqualTo(before);
	}

	private static ThreadBox thread(int index) {
		return new ThreadBox(new Key("parent-run", "plan", "child-" + index), 1, List.of());
	}

	@Test void exactlyTheThreadCapacityIsStoredAndOneMoreIsRefused() throws Exception {
		store.transact(threads -> {
			for (int i = 0; i < MAX_THREADS; i++) threads.add(thread(i));
			return null;
		});
		assertThat(store.read(new Key("parent-run", "plan", "child-" + (MAX_THREADS - 1)), 0).version()).isEqualTo(1);
		var before = snapshot();
		assertThatThrownBy(() -> store.transact(threads -> { threads.add(thread(MAX_THREADS)); return null; }))
			.isExactlyInstanceOf(IOException.class).hasMessage("Mailbox thread capacity");
		assertThat(snapshot()).isEqualTo(before);
		assertThat(store.read(new Key("parent-run", "plan", "child-" + MAX_THREADS), 0).entries()).isEmpty();
	}

	@Test void aStoredFileBeyondTheThreadCapacityIsRefusedWhenLoaded() throws Exception {
		commitOneReceipt();
		var threads = new ArrayList<ThreadBox>();
		for (int i = 0; i <= MAX_THREADS; i++) threads.add(thread(i));
		Files.writeString(data, "{\"schema\":2,\"digest\":\"" + Fingerprinter.sha256(JSON.writeValueAsBytes(threads))
			+ "\",\"threads\":" + JSON.writeValueAsString(threads) + "}");
		assertReadRefused("Mailbox thread capacity");
	}

	@Test void duplicateThreadKeysAndNegativeVersionsAreRefusedBeforeAnythingIsWritten() throws Exception {
		commitOneReceipt();
		var before = snapshot();
		assertThatThrownBy(() -> store.transact(threads -> { threads.add(thread(1)); threads.add(thread(1)); return null; }))
			.isExactlyInstanceOf(IOException.class).hasMessage("Invalid bounded mailbox");
		assertThatThrownBy(() -> store.transact(threads -> { threads.add(new ThreadBox(KEY, -1, List.of())); return null; }))
			.isExactlyInstanceOf(IOException.class).hasMessage("Invalid bounded mailbox");
		assertThat(snapshot()).isEqualTo(before);
		assertReadStillWorks();
	}

	@Test void aMessagesDirectoryThatIsNotPrivateIsRefusedUntilItIsRestored() throws Exception {
		commitOneReceipt();
		Files.setPosixFilePermissions(messages, PosixFilePermissions.fromString("rwxr-xr-x"));
		assertReadRefused("Mailbox directory is not private");
		Files.setPosixFilePermissions(messages, PosixFilePermissions.fromString("rwx------"));
		assertReadStillWorks();
	}

	@Test void aSymlinkedMessagesDirectoryIsUnsafe() throws Exception {
		commitOneReceipt();
		Path moved = Files.move(messages, root.resolve("moved-messages"));
		Files.createSymbolicLink(messages, moved);
		assertThatThrownBy(() -> store.read(KEY, 0)).isExactlyInstanceOf(IOException.class)
			.hasMessage("Unsafe mailbox path");
		assertThat(Files.readString(moved.resolve("mailboxes.json"))).contains("pay 10");
	}
}
