package com.example.miniagent.core.run;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** No Git dependency. Exclusions are applied before opening file contents. */
public record WorkspaceFingerprint(String cwd, Map<String, String> entries) {
	public WorkspaceFingerprint {
		entries = Collections.unmodifiableMap(new TreeMap<>(entries));
	}

	public static WorkspaceFingerprint capture(Path cwd, Path agentHome) throws IOException {
		Path root = cwd.toRealPath();
		Path state = agentHome.toRealPath();
		Map<String, String> entries = new TreeMap<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				return !dir.equals(root) && (excluded(dir.getFileName().toString()) || dir.startsWith(state))
						? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
			}

			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (excluded(file.getFileName().toString()) || file.startsWith(state)) return FileVisitResult.CONTINUE;
				String name = root.relativize(file).toString();
				if (attrs.isSymbolicLink()) {
					Path target = file.toRealPath();
					if (!target.startsWith(root)) throw new IOException("Workspace symlink escapes: " + name);
					entries.put(name, "link:" + Files.readSymbolicLink(file));
				}
				else if (attrs.isRegularFile()) {
					// Bound the snapshot rather than quietly omitting large source.
					if (attrs.size() > 16 * 1024 * 1024) throw new IOException("Workspace file too large: " + name);
					entries.put(name, "file:" + attrs.size() + ":" + Files.getPosixFilePermissions(file)
							.stream().map(Enum::name).sorted().toList() + ":"
							+ RunRequestStore.digest(Files.readAllBytes(file)));
				}
				else throw new IOException("Unsupported workspace entry: " + name);
				if (entries.size() > 10000) throw new IOException("Workspace snapshot too large");
				return FileVisitResult.CONTINUE;
			}
		});
		return new WorkspaceFingerprint(root.toString(), entries);
	}

	static boolean excluded(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return Set.of(".git", "target", "build", "dist", "node_modules", ".gradle", ".m2",
				".omo", "runs", "run-requests", "tool-evidence", "model-selections",
				"auth", ".ssh", ".aws", ".config", ".cache", "logs", "changes").contains(n)
				|| n.startsWith(".env") || n.contains("credential") || n.contains("secret")
				|| n.equals("auth.json") || n.equals(".npmrc") || n.equals(".netrc")
				|| n.equals("id_rsa") || n.equals("id_ed25519")
				|| n.endsWith(".key") || n.endsWith(".pem") || n.endsWith(".crt")
				|| n.endsWith(".cer") || n.endsWith(".p12") || n.endsWith(".pfx")
				|| n.endsWith(".jks") || n.endsWith(".keystore");
	}
}
