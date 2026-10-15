package com.example.miniagent.plugin.bash;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.example.miniagent.core.change.StagedWorkspace;
import com.example.miniagent.core.change.VerificationRunner;
import com.example.miniagent.core.change.Fingerprinter;

/**
 * macOS {@code sandbox-exec} 로 bash 를 감싼다. (Codex CLI 가 macOS 에서 쓰는 Seatbelt 샌드박스와 같은 방식)
 *
 * <p>읽기와 네트워크는 그대로 두고 쓰기만 막는다. 쓸 수 있는 곳은 작업 디렉터리, {@code writableRoots}, {@code /dev} 뿐이다.
 * 프로필은 뒤에 오는 규칙이 앞의 규칙을 이긴다: 전부 허용 → 쓰기 전부 금지 → 허용할 경로만 다시 허용.
 * 경로는 프로필 문자열에 끼워 넣지 않고 {@code -D} 파라미터로 넘긴다(따옴표가 든 경로도 안전).
 * 샌드박스는 실제 경로로 판단하므로 /var, /tmp 같은 링크는 실제 위치(/private/...)로 바꿔 넘긴다.
 */
public final class BashSandbox {

	static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

	private final List<Path> writableRoots;

	public enum Code { UNSUPPORTED_SANDBOX, INVALID_CACHE_ROOT }
	public static final class Refusal extends IllegalStateException {
		private final Code code;
		public Refusal(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
		public Code code() { return this.code; }
	}

	private BashSandbox(List<Path> writableRoots) {
		this.writableRoots = List.copyOf(writableRoots);
	}

	/** macOS 에서만 만들 수 있다. 다른 OS 에서 켜면 시작할 때 바로 실패한다(샌드박스 없이 조용히 돌지 않도록). */
	public static BashSandbox workspaceWrite(List<Path> writableRoots) {
		if (!Files.isExecutable(SANDBOX_EXEC)) {
			throw new IllegalStateException(
					"agent.plugins.bash.sandbox=workspace-write 는 macOS 의 /usr/bin/sandbox-exec 가 필요합니다");
		}
		return new BashSandbox(writableRoots.stream().map(BashSandbox::realPath).toList());
	}

	/** {@code bash -lc <command>} 를 샌드박스 안에서 실행하는 명령줄. */
	List<String> command(String shellCommand, File workingDirectory) {
		List<String> command = new ArrayList<>(
				List.of(SANDBOX_EXEC.toString(), "-D", "WORKSPACE=" + realPath(workingDirectory.toPath())));
		StringBuilder writable = new StringBuilder("(subpath \"/dev\") (subpath (param \"WORKSPACE\"))");
		for (int i = 0; i < this.writableRoots.size(); i++) {
			command.add("-D");
			command.add("ROOT" + i + "=" + this.writableRoots.get(i));
			writable.append(" (subpath (param \"ROOT").append(i).append("\"))");
		}
		command.add("-p");
		command.add("(version 1)\n(allow default)\n(deny file-write*)\n(allow file-write* " + writable + ")");
		command.addAll(List.of("bash", "-lc", shellCommand));
		return command;
	}

	/** Managed mode never inherits the legacy allow-default profile or broad temp roots. */
	static List<String> managedCommand(String shellCommand, StagedWorkspace stage) {
		return managedCommand(shellCommand, stage, List.of());
	}

	static List<String> managedCommand(String shellCommand, StagedWorkspace stage, List<Path> cacheRoots) {
		if (!Files.isExecutable(SANDBOX_EXEC))
			throw new Refusal(Code.UNSUPPORTED_SANDBOX, null);
		List<String> command = new ArrayList<>(List.of(SANDBOX_EXEC.toString(),
				"-D", "STAGE=" + stage.root(), "-D", "TEMP=" + stage.tempDirectory()));
		StringBuilder caches = new StringBuilder();
		for (int i = 0; i < cacheRoots.size(); i++) {
			Path cache;
			try { cache = cacheRoots.get(i).toRealPath(); }
			catch (IOException ex) { throw new Refusal(Code.INVALID_CACHE_ROOT, ex); }
			if (!Files.isDirectory(cache) || cache.getParent() == null
					|| stage.liveRoot().startsWith(cache) || cache.startsWith(stage.liveRoot())
					|| stage.policy().agentHome().startsWith(cache) || cache.startsWith(stage.policy().agentHome())
					|| cache.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())) {
				throw new Refusal(Code.INVALID_CACHE_ROOT, null);
			}
			command.addAll(List.of("-D", "CACHE" + i + "=" + cache));
			caches.append(" (subpath (param \"CACHE").append(i).append("\"))");
		}
		StringBuilder deniedLinks = new StringBuilder();
		for (int i = 0; i < stage.linkPaths().size(); i++) {
			command.addAll(List.of("-D", "LINK" + i + "=" + stage.root().resolve(stage.linkPaths().get(i))));
			deniedLinks.append(" (subpath (param \"LINK").append(i).append("\"))");
		}
		command.add("-p");
		// Deny-default also prevents network, IPC services and reads of live/auth/home files.
		// Use a regex rooted at the stage, escaped as a parameter-free literal.
		// Seatbelt regex does not support Java's \\Q quoting. Escape literal regex metacharacters instead.
		String prefix = stage.root().toString().replace("\\", "\\\\").replaceAll("([.\\[\\]{}()*+?^$|])", "\\\\$1");
		var exclusions = new StringBuilder();
		// Separate literals avoid Seatbelt's string-reader limit on one large alternation.
		for (String pattern : List.of("[.]git", "target", "build", "dist", "node_modules", "[.]gradle",
				"[.]m2", "[.]omo", "[.]cache", "[.]config", "[.]ssh", "[.]aws", "[.]codex", "[.]claude",
				"auth", "authentication", "credentials", "keys", "certs", "certificates", "runs",
				"run-requests", "tool-evidence", "model-selections", "changes", "logs", "[.]env[^/]*",
				"[^/]*credential[^/]*", "[^/]*secret[^/]*", "auth[.]json", "[.]npmrc", "[.]netrc",
				"id_rsa[^/]*", "id_ed25519[^/]*", "[^/]*[.]key", "[^/]*[.]pem", "[^/]*[.]crt",
				"[^/]*[.]cer", "[^/]*[.]der", "[^/]*[.]p12", "[^/]*[.]pfx", "[^/]*[.]jks", "[^/]*[.]keystore")) {
			var insensitive = new StringBuilder();
			for (char c : pattern.toCharArray()) {
				if (c >= 'a' && c <= 'z') insensitive.append('[').append(c).append(Character.toUpperCase(c)).append(']');
				else insensitive.append(c);
			}
			String below = "^" + prefix + "/([^/]+/)*" + insensitive + "(/|$)";
			exclusions.append("(deny file-read* file-write* (regex #\"")
					.append(below.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"))\n");
		}
		String profile = "(version 1)\n(deny default)\n(allow process*)\n(allow sysctl-read)\n"
				+ "(allow file-read-metadata)\n"
				+ "(allow file-read* (literal \"/\") (subpath \"/bin\") (subpath \"/usr/bin\") (subpath \"/usr/lib\")"
				+ " (subpath \"/System/Library\") (subpath \"/Library/Apple/System/Library\")"
				+ " (subpath \"/private/var/db/dyld\") (literal \"/private/etc/localtime\")"
				+ " (literal \"/dev/null\") (literal \"/dev/random\") (literal \"/dev/urandom\")"
				+ " (subpath (param \"STAGE\")) (subpath (param \"TEMP\"))" + caches + ")\n"
				+ "(allow file-write* (subpath (param \"STAGE\")) (subpath (param \"TEMP\"))"
				+ caches + " (literal \"/dev/null\"))\n"
				+ exclusions
				+ "(deny file-write* (subpath \"" + stage.root().resolve(".mini-agent").toString()
						.replace("\\", "\\\\").replace("\"", "\\\"") + "\")" + deniedLinks + ")";
		command.add(profile);
		command.addAll(List.of("/bin/bash", "--noprofile", "--norc", "-c", shellCommand));
		return command;
	}

	static Map<String, String> managedEnvironment(StagedWorkspace stage) {
		return Map.of("PATH", "/usr/bin:/bin", "HOME", stage.tempDirectory().toString(),
				"TMPDIR", stage.tempDirectory().toString(), "LANG", "C", "LC_ALL", "C");
	}

	/** Probe the actual profile with an OS-denied write, not merely executable presence. */
	static void verify(StagedWorkspace stage) {
		verify(stage, List.of());
	}

	static void verify(StagedWorkspace stage, List<Path> caches) {
		Path denied = stage.tempDirectory().getParent().resolve(".stage-sandbox-probe-" + java.util.UUID.randomUUID());
		Path allowed = stage.tempDirectory().resolve("sandbox-probe");
		String script = "printf ok > " + quote(allowed.toString()) + " || exit 71; "
				+ "if (printf forbidden > " + quote(denied.toString()) + ") 2>/dev/null; then exit 72; fi";
		Process process = null;
		try {
			ProcessBuilder builder = new ProcessBuilder(managedCommand(script, stage, caches)).directory(stage.root().toFile())
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.environment().clear();
			builder.environment().putAll(managedEnvironment(stage));
			process = builder.start();
			process.onExit().get(5, TimeUnit.SECONDS);
			if (process.exitValue() != 0 || Files.exists(denied) || !Files.readString(allowed).equals("ok"))
				throw new Refusal(Code.UNSUPPORTED_SANDBOX, null);
			Files.delete(allowed);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new Refusal(Code.UNSUPPORTED_SANDBOX, ex);
		}
		catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
			throw new Refusal(Code.UNSUPPORTED_SANDBOX, ex);
		}
		finally {
			if (process != null && process.isAlive()) process.destroyForcibly();
			try { Files.deleteIfExists(denied); }
			catch (IOException ex) { throw new Refusal(Code.UNSUPPORTED_SANDBOX, ex); }
		}
	}

	private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

	/** Verification never grants source writes, inherited environment, or workspace execution. */
	public static VerificationRunner.LaunchPolicy verificationPolicy(List<Path> cacheRoots) {
		var configured = List.copyOf(cacheRoots);
		return (context, argv) -> {
			if (!System.getProperty("os.name").equals("Mac OS X") || !Files.isExecutable(SANDBOX_EXEC))
				throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
			Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
			Path userHome = Path.of(System.getProperty("user.home")).toRealPath();
			if (!Files.isExecutable(runtime.resolve("bin/java")) || runtime.getParent() == null
					|| overlaps(runtime, context.liveRoot()) || overlaps(runtime, context.agentHome())
					|| userHome.startsWith(runtime))
				throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
			var caches = new ArrayList<Path>();
			for (Path configuredCache : configured) {
				Path cache;
				try { cache = configuredCache.toRealPath(); }
				catch (IOException ex) {
					throw new VerificationRunner.Refusal(VerificationRunner.Code.INVALID_CACHE_ROOT, ex);
				}
				if (!Files.isDirectory(cache) || cache.getParent() == null
						|| overlaps(cache, context.liveRoot()) || overlaps(cache, context.agentHome())
						|| overlaps(cache, context.root()) || overlaps(cache, context.temp())
						|| overlaps(cache, userHome) || overlaps(cache, runtime))
					throw new VerificationRunner.Refusal(VerificationRunner.Code.INVALID_CACHE_ROOT, null);
				caches.add(cache);
			}
			Map<String, String> environment = Map.of("PATH", runtime.resolve("bin") + ":/usr/bin:/bin",
					"HOME", context.temp().toString(), "TMPDIR", context.temp().toString(), "LANG", "C", "LC_ALL", "C");
			String profile = verificationProfile(caches.size());
			var prefix = new ArrayList<>(List.of(SANDBOX_EXEC.toString(), "-D", "SOURCE=" + context.root(),
					"-D", "TEMP=" + context.temp(), "-D", "JAVA=" + runtime));
			for (int i = 0; i < caches.size(); i++)
				prefix.addAll(List.of("-D", "CACHE" + i + "=" + caches.get(i)));
			for (String generated : List.of("target", "build", "dist", ".gradle"))
				prefix.addAll(List.of("-D", generated.toUpperCase(java.util.Locale.ROOT).replace(".", "")
						+ "=" + context.root().resolve(generated)));
			prefix.addAll(List.of("-p", profile));
			verifyReadOnly(context, prefix, environment);
			var command = new ArrayList<>(prefix);
			command.addAll(argv); // Never interpolate the original argv into a shell command.
			var bytes = new java.io.ByteArrayOutputStream();
			var data = new java.io.DataOutputStream(bytes);
			for (String value : List.of("seatbelt-verification-v1", profile, runtime.toString())) data.writeUTF(value);
			data.writeInt(caches.size());
			for (Path cache : caches) data.writeUTF(cache.toString());
			return new VerificationRunner.Launch(command, environment, Fingerprinter.sha256(bytes.toByteArray()));
		};
	}

	private static boolean overlaps(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }

	private static String verificationProfile(int cacheCount) {
		var cacheScope = new StringBuilder();
		for (int i = 0; i < cacheCount; i++) cacheScope.append(" (subpath (param \"CACHE").append(i).append("\"))");
		return "(version 1)\n(deny default)\n(allow process*)\n(allow sysctl-read)\n"
				+ "(allow file-read-metadata)\n"
				+ "(allow file-read* (literal \"/\") (subpath \"/bin\") (subpath \"/usr/bin\")"
				+ " (subpath \"/usr/lib\") (subpath \"/System/Library\") (subpath \"/Library/Apple/System/Library\")"
				+ " (subpath \"/private/var/db/dyld\") (literal \"/private/etc/localtime\")"
				+ " (literal \"/dev/null\") (literal \"/dev/random\") (literal \"/dev/urandom\")"
				+ " (subpath (param \"SOURCE\")) (subpath (param \"TEMP\")) (subpath (param \"JAVA\"))"
				+ cacheScope + ")\n"
				+ "(allow file-write* (literal \"/dev/null\") (subpath (param \"TEMP\"))"
				+ " (subpath (param \"TARGET\")) (subpath (param \"BUILD\"))"
				+ " (subpath (param \"DIST\")) (subpath (param \"GRADLE\"))" + cacheScope + ")\n";
	}

	private static void verifyReadOnly(VerificationRunner.Context context, List<String> prefix,
			Map<String, String> environment) throws IOException {
		Path allowed = context.temp().resolve("sandbox-probe");
		Path denied = context.root().resolve(".verification-denied-probe");
		Path outside = context.temp().getParent().resolve(".verification-denied-probe");
		String script = "printf ok > " + quote(allowed.toString()) + " || exit 71; "
				+ "if (printf forbidden > " + quote(denied.toString()) + ") 2>/dev/null; then exit 72; fi; "
				+ "if (printf forbidden > " + quote(outside.toString()) + ") 2>/dev/null; then exit 73; fi";
		var command = new ArrayList<>(prefix);
		command.addAll(List.of("/bin/bash", "--noprofile", "--norc", "-c", script));
		Process process = null;
		try {
			var builder = new ProcessBuilder(command).directory(context.root().toFile())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
			builder.environment().clear();
			builder.environment().putAll(environment);
			process = builder.start();
			process.onExit().get(5, TimeUnit.SECONDS);
			if (process.exitValue() != 0 || Files.exists(denied) || Files.exists(outside)
					|| !Files.readString(allowed).equals("ok"))
				throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, null);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, ex);
		}
		catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
			throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, ex);
		}
		finally {
			if (process != null) {
				boolean interrupted = Thread.interrupted();
				try {
					process.descendants().forEach(ProcessHandle::destroyForcibly);
					if (process.isAlive()) process.destroyForcibly();
					process.onExit().get(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					interrupted = true;
					throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, ex);
				}
				catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ex) {
					throw new VerificationRunner.Refusal(VerificationRunner.Code.UNSUPPORTED_SANDBOX, ex);
				}
				finally { if (interrupted) Thread.currentThread().interrupt(); }
			}
			Files.deleteIfExists(allowed);
			Files.deleteIfExists(denied);
			Files.deleteIfExists(outside);
		}
	}

	private static Path realPath(Path path) {
		try {
			return path.toRealPath();
		}
		catch (IOException ex) {
			// 아직 없는 경로는 그대로 쓴다. 나중에 만들어지면 그 아래 쓰기가 허용된다.
			return path.toAbsolutePath().normalize();
		}
	}

}
