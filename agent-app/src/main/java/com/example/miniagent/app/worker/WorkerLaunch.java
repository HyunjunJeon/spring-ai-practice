package com.example.miniagent.app.worker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import com.example.miniagent.core.model.ModelSelection;

/** Allowlisted configuration only. Never copy parent argv, system properties, or environment wholesale. */
public record WorkerLaunch(ModelSelection selection, String auth, String baseUrl, Path codexHome,
		Path userHome, Duration idleTimeout) {
	public WorkerLaunch {
		Objects.requireNonNull(selection);
		if (!selection.ref().provider().equals("codex") || !auth.equals("codex-cli")
				|| !baseUrl.equals("https://chatgpt.com/backend-api/codex"))
			throw new IllegalArgumentException("Worker requires official Codex CLI OAuth");
		if (!selection.ref().model().matches("[A-Za-z0-9._-]{1,128}"))
			throw new IllegalArgumentException("Invalid worker model");
		for (var option : selection.options().entrySet())
			if (!Set.of("effort", "reasoningEffort").contains(option.getKey())
					|| !Set.of("minimal", "low", "medium", "high", "xhigh", "max").contains(option.getValue()))
				throw new IllegalArgumentException("Unsupported worker launch option");
		codexHome = codexHome.toAbsolutePath().normalize();
		userHome = userHome.toAbsolutePath().normalize();
		if (idleTimeout.isNegative() || idleTimeout.isZero() || idleTimeout.compareTo(Duration.ofHours(1)) > 0)
			throw new IllegalArgumentException("Invalid worker idle timeout");
	}

	List<String> command(WorkerFiles files, String instance) {
		var command = new ArrayList<String>();
		// nohup preserves SIG_IGN across exec; all three descriptors are detached below.
		command.add("/usr/bin/nohup");
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.add("-Duser.home=" + userHome);
		File source = new org.springframework.boot.system.ApplicationHome(WorkerLaunch.class).getSource();
		if (source != null && source.isFile() && source.getName().endsWith(".jar")) {
			command.add("-jar");
			command.add(source.getAbsolutePath());
		}
		else {
			command.add("-cp");
			command.add(productionClasspath(System.getProperty("surefire.test.class.path",
					System.getProperty("java.class.path"))));
			command.add("com.example.miniagent.app.MiniAgentApplication");
		}
		command.addAll(List.of("--agent.worker.mode=true", "--agent.worker.instance=" + instance,
				"--agent.cwd=" + files.workspace, "--agent.home=" + files.home,
				"--agent.ui=repl", "--agent.repl.enabled=false", "--spring.main.web-application-type=none",
				"--spring.profiles.active=codex", "--spring.config.location=classpath:/",
				"--spring.config.import=", "--agent.models.default=codex/" + selection.ref().model(),
				"--agent.codex.model=" + selection.ref().model(),
				"--agent.codex.reasoning-effort=" + selection.options().getOrDefault("reasoningEffort",
						selection.options().getOrDefault("effort", "high")),
				"--agent.codex.auth=" + auth, "--agent.codex.base-url=" + baseUrl,
				"--agent.codex.codex-home=" + codexHome, "--agent.codex.idle-timeout=" + idleTimeout,
				"--agent.codex.open-browser=false"));
		return List.copyOf(command);
	}

	static String productionClasspath(String classpath) {
		String result = Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
				.filter(s -> !s.isBlank())
				.map(s -> Path.of(s).toAbsolutePath().normalize())
				// Only an entry that is itself a test output directory; an ancestor named "test" says nothing.
				.filter(p -> p.getFileName() == null
						|| !Set.of("test-classes", "test", "tests").contains(p.getFileName().toString()))
				.map(Path::toString).collect(Collectors.joining(File.pathSeparator));
		if (result.isEmpty()) throw new IllegalArgumentException("No production classpath");
		return result;
	}

	Process start(WorkerFiles files, String instance) throws IOException {
		var builder = new ProcessBuilder(command(files, instance)).directory(files.workspace.toFile());
		builder.environment().clear();
		builder.environment().put("HOME", userHome.toString());
		builder.environment().put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
		builder.redirectInput(new File("/dev/null"));
		// Provider errors can contain response bodies. Do not persist either application stream.
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		return builder.start();
	}
}
