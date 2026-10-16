package com.example.miniagent.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.FileChangeStore;
import com.example.miniagent.core.change.PathPolicy;
import com.example.miniagent.core.change.UndoEngine;

/**
 * Test-only entry point for the JVM that undoes a change a killed JVM left behind. The crash
 * itself comes from {@link CrashRecoveryProcessFixture}; this process only runs the operator undo.
 */
public final class ChangeCrashProcessFixture {
	private ChangeCrashProcessFixture() {}

	/** Same GO/READY/DONE protocol as CrashRecoveryProcessFixture; only PATH and HOME reach the child. */
	static CrashRecoveryProcessFixture.Child launch(Path home, Path cwd, Path user, String mode) throws Exception {
		String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		// The subprocess cwd differs from Surefire's cwd; resolve relative entries first.
		classpath = Arrays.stream(classpath.split(File.pathSeparator))
				.map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
				.collect(Collectors.joining(File.pathSeparator));
		ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classpath, ChangeCrashProcessFixture.class.getName(), mode, home.toString(), cwd.toString());
		builder.directory(cwd.toFile()).redirectErrorStream(true);
		builder.environment().clear();
		builder.environment().put("PATH", "/usr/bin:/bin");
		builder.environment().put("HOME", user.toString());
		var child = new CrashRecoveryProcessFixture.Child(builder.start());
		try {
			child.process.getOutputStream().write("GO\n".getBytes(StandardCharsets.UTF_8));
			child.process.getOutputStream().flush();
			return child;
		}
		catch (Throwable ex) { child.close(); throw ex; }
	}

	public static void main(String[] args) throws Exception {
		var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
		if (!"GO".equals(input.readLine())) throw new IOException("Missing subscribed parent");
		String mode = args[0];
		Path home = Path.of(args[1]), cwd = Path.of(args[2]).toRealPath();
		ApplyEngine.BoundaryHook hook = switch (mode) {
			case "undo" -> (boundary, checkpoint) -> {};
			// RESTORED of source.txt is journaled before COMPLETED; z-second.txt is still to come.
			case "undo-crash" -> (boundary, checkpoint) -> {
				if (boundary == ApplyEngine.Boundary.COMPLETED && checkpoint.before().relativePath().equals("source.txt"))
					CrashRecoveryProcessFixture.readyAndBlock();
			};
			default -> throw new IllegalArgumentException(mode);
		};
		var store = new FileChangeStore(home, cwd);
		var policy = new PathPolicy(cwd, home, List.of());
		var snapshot = store.read().changes().getFirst();
		var set = snapshot.changeSet();
		var console = new AgentConsole(new BufferedReader(new StringReader(
				"exclusive " + set.changeSetId() + " " + snapshot.digest() + "\n")),
				new PrintStream(OutputStream.nullOutputStream()), true);
		// A fresh JVM mints its own proof; nothing from the killed process is carried over.
		var proof = new ApplyEngine(store, policy, CrashRecoveryProcessFixture.CLOCK)
				.confirmExclusiveOwnership(console, set.changeSetId(), set.owner(), snapshot.digest());
		var result = new UndoEngine(store, policy, CrashRecoveryProcessFixture.CLOCK, hook)
				.undo(set.changeSetId(), set.owner(), snapshot.digest(), proof);
		System.out.println("STATUS " + result.changeSet().status());
		System.out.println("DONE"); System.out.flush();
	}
}
