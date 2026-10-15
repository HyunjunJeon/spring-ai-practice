package com.example.miniagent.core.messaging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.messaging.MessageStore.*;
import static org.assertj.core.api.Assertions.*;

class MessageStoreTest {
	@TempDir Path root;
	@org.junit.jupiter.api.BeforeEach void canonicalPaths() throws java.io.IOException { root = root.toRealPath(); }
	@Test void privateAtomicPagesRetainReceiptsAcrossInstancesAndRefuseCas() throws Exception {
		Path home = root.resolve("home");
		var store = new MessageStore(home);
		var key = new Key("parent-run", "plan", "child");
		store.transact(threads -> {
			var entries = new ArrayList<Receipt>();
			for (int i = 0; i < 20; i++) entries.add(new Receipt("m-" + i, "parent", "body", State.DELIVERED,
				"original-run", "execution-run", "reply", "claim"));
			replace(threads, find(threads, key), entries); return null;
		});
		var fresh = new MessageStore(home);
		assertThat(fresh.read(key, 0).entries()).hasSize(PAGE_SIZE);
		assertThat(fresh.read(key, PAGE_SIZE).entries()).hasSize(4);
		assertThat(fresh.read(key, 0).version()).isEqualTo(1);
		assertThat(fresh.read(new Key("foreign", "plan", "child"), 0).entries()).isEmpty();
		assertThat(Files.getPosixFilePermissions(home.resolve("messages/mailboxes.json")))
			.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		assertThat(Files.getPosixFilePermissions(home.resolve("messages")))
			.isEqualTo(PosixFilePermissions.fromString("rwx------"));
		assertThatThrownBy(() -> store.transact(threads -> {
			if (find(threads, key).version() != 0) throw new java.io.IOException("Stale mailbox version");
			return null;
		})).hasMessageContaining("Stale mailbox version");
		assertThat(fresh.read(key, 0).version()).isEqualTo(1);
	}
	@Test void boundsDuplicateIdsAndSymlinksFailClosed() throws Exception {
		var store = new MessageStore(root.resolve("home"));
		var key = new Key("parent", "plan", "child");
		assertThatThrownBy(() -> new Receipt("id", "parent", "x".repeat(4097), State.QUEUED, "run", null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		var entry = new Receipt("id", "parent", "body", State.QUEUED, "run", null, null, null);
		assertThatThrownBy(() -> store.transact(threads -> {
			replace(threads, find(threads, key), List.of(entry, entry)); return null;
		})).hasMessageContaining("Duplicate receipt");
		assertThat(store.read(key, 0).entries()).isEmpty();
		assertThatThrownBy(() -> store.transact(threads -> {
			var entries = new ArrayList<Receipt>();
			for (int i = 0; i <= MAX_ENTRIES; i++)
				entries.add(new Receipt("id-" + i, "parent", "body", State.QUEUED, "run", null, null, null));
			replace(threads, find(threads, key), entries); return null;
		})).hasMessageContaining("Invalid bounded mailbox");
		var other = root.resolve("other"); Files.createDirectory(other);
		Files.createSymbolicLink(root.resolve("unsafe"), other);
		assertThatThrownBy(() -> new MessageStore(root.resolve("unsafe")).read(key, 0))
			.hasMessageContaining("Unsafe mailbox path");
	}
	@Test void lockOwnershipRefusesRatherThanOpeningAnotherDescriptor() throws Exception {
		var store = new MessageStore(root.resolve("home"));
		var key = new Key("parent", "plan", "child");
		store.transact(threads -> {
			assertThatThrownBy(() -> new MessageStore(root.resolve("home")).read(key, 0))
				.hasMessageContaining("Mailbox busy");
			return null;
		});
	}
}
