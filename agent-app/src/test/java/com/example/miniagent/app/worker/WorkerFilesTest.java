package com.example.miniagent.app.worker;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import com.example.miniagent.core.change.AtomicFiles;
import com.example.miniagent.core.run.DriftGate;
import com.example.miniagent.core.run.RunExecutor;
import com.example.miniagent.core.run.RunEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class WorkerFilesTest {
	@TempDir Path temporary;

	@Test
	void stableClaimRemainsExclusiveAcrossStatusAndReacquisition() throws Exception {
		Path root = temporary.toRealPath();
		var first = new WorkerFiles(root, root.resolve("home"));
		Object inode = Files.getAttribute(first.directory.resolve("claim.lock"), "unix:ino");
		try (var claim = first.acquire()) {
			assertThat(claim).isNotNull();
			var second = new WorkerFiles(root.resolve("."), root.resolve("home"));
			assertThat(second.directory).isEqualTo(first.directory);
			assertThat(second.held()).isTrue();
			assertThat(second.acquire()).isNull();
			assertThat(first.held()).isTrue();
		}
		try (var claim = first.acquire()) { assertThat(claim).isNotNull(); }
		assertThat(Files.getAttribute(first.directory.resolve("claim.lock"), "unix:ino")).isEqualTo(inode);
	}

	@Test
	void rejectsSymlinkedClaimAndLeavesPoisonUntouched() throws Exception {
		Path root = temporary.toRealPath();
		var files = new WorkerFiles(root, root.resolve("home"));
		Path lock = files.directory.resolve("claim.lock");
		Files.delete(lock); // Fixture poisoning only, with no owner.
		Path target = Files.createFile(root.resolve("foreign"));
		Files.createSymbolicLink(lock, target);
		assertThatThrownBy(() -> new WorkerFiles(root, root.resolve("home"))).isInstanceOf(IOException.class);
		assertThat(Files.isSymbolicLink(lock)).isTrue();
	}

	@Test
	void corruptMetadataIsNotRepairedOrUsedAsAuthority() throws Exception {
		Path root = temporary.toRealPath();
		var files = new WorkerFiles(root, root.resolve("home"));
		AtomicFiles.write(files.metadata, new byte[] { '{' }, WorkerFiles.LIMIT);
		assertThatThrownBy(() -> new WorkerFiles(root, root.resolve("home"))).isInstanceOf(IOException.class);
		assertThat(AtomicFiles.read(files.metadata, WorkerFiles.LIMIT)).containsExactly((byte) '{');
	}

	@Test
	void privateControlRoundTripsExactResumeAuthorityAndInstance() throws Exception {
		Path root = temporary.toRealPath();
		var files = new WorkerFiles(root, root.resolve("home"));
		String instance = UUID.randomUUID().toString();
		String request = UUID.randomUUID().toString();
		var resolution = new RunExecutor.ToolResolutionInput("attempt", "tool", RunEvent.Resolution.SKIP,
				"operator", "evidence");
		var options = new RunExecutor.ResumeOptions(java.util.List.of(resolution),
				new DriftGate.Acknowledgement("a".repeat(64), "b".repeat(64), "operator", "evidence"));
		var expected = new WorkerFiles.Request(1, instance, request, "resume", "goal", options);
		files.send(expected);
		Path control = files.inbox.resolve(request + ".json");
		assertThat(files.request(control)).isEqualTo(expected);
		assertThat(Files.getPosixFilePermissions(control)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		assertThat(Files.getPosixFilePermissions(files.inbox)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
	}

	@Test
	void rejectsPermissiveAndHardlinkedControlWithoutCleanup() throws Exception {
		Path root = temporary.toRealPath();
		var files = new WorkerFiles(root, root.resolve("home"));
		String request = UUID.randomUUID().toString();
		files.send(new WorkerFiles.Request(1, UUID.randomUUID().toString(), request, "stop", "", null));
		Path control = files.inbox.resolve(request + ".json");
		Files.createLink(root.resolve("alias"), control);
		assertThatThrownBy(() -> files.request(control)).isInstanceOf(IOException.class);
		assertThat(control).exists();
	}
}
