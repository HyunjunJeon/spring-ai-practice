package com.example.miniagent.plugin.bash;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.example.miniagent.core.change.*;
import com.example.miniagent.core.model.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;

class SandboxProfileSmoke {
	public static void main(String[] args) throws Exception {
		Path base = Files.createTempDirectory("sandbox-profile-smoke");
		try {
			Path work = Files.createDirectory(base.resolve("workspace"));
			Path home = base.resolve("state");
			ChatModel model = prompt -> new ChatResponse(List.of());
			var binding = new ModelBinding(UUID.randomUUID(), new ModelRef("fake", "probe"), "a".repeat(64),
					"fake", model, ChatClient.builder(model).build());
			var turn = new TurnContext("session", "turn", work.toRealPath(), binding, "run", "attempt");
			try (var stage = StagedWorkspace.open(new FileChangeStore(home, work), turn, Clock.systemUTC())) {
				var commandMethod = BashSandbox.class.getDeclaredMethod("managedCommand", String.class, StagedWorkspace.class);
				commandMethod.setAccessible(true);
				var command = new ArrayList<>(((List<?>) commandMethod.invoke(null, "printf PROFILE_EXECUTED", stage))
						.stream().map(String.class::cast).toList());
				int profileIndex = command.indexOf("-p") + 1;
				if (args.length > 0 && args[0].equals("without-regex")) {
					command.set(profileIndex, command.get(profileIndex).lines()
							.filter(line -> !line.startsWith("(deny file-read* file-write* (regex"))
							.collect(java.util.stream.Collectors.joining("\n")));
				}
				if (args.length > 0 && args[0].equals("short-regex")) {
					command.set(profileIndex, command.get(profileIndex).lines()
							.map(line -> line.startsWith("(deny file-read* file-write* (regex")
									? "(deny file-read* file-write* (regex #\"/[.]env(/|$)\"))" : line)
							.collect(java.util.stream.Collectors.joining("\n")));
				}
				System.out.println(command.get(profileIndex));
				var builder = new ProcessBuilder(command).directory(stage.root().toFile()).redirectErrorStream(true);
				builder.environment().clear();
				var environmentMethod = BashSandbox.class.getDeclaredMethod("managedEnvironment", StagedWorkspace.class);
				environmentMethod.setAccessible(true);
				((Map<?, ?>) environmentMethod.invoke(null, stage))
						.forEach((key, value) -> builder.environment().put((String) key, (String) value));
				var process = builder.start();
				try {
					process.onExit().get(5, TimeUnit.SECONDS);
					System.out.println(new String(process.getInputStream().readAllBytes()));
					System.out.println("PROFILE_EXIT=" + process.exitValue());
					if (process.exitValue() != 0) throw new AssertionError("Managed profile cannot launch bash");
				}
				finally { if (process.isAlive()) process.destroyForcibly(); }
			}
		}
		finally {
			try (var paths = Files.walk(base)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
			}
			System.out.println("PROFILE_TEMP_REMOVED");
		}
	}
}
