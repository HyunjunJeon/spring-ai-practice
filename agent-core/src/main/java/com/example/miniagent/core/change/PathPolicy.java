package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Managed path boundary, not a sandbox. Observations require cooperating writers;
 * portable path APIs cannot atomically exclude a hostile concurrent ancestor swap.
 */
public final class PathPolicy {
	public enum Code {
		INVALID_PATH, EXCLUDED, OUTSIDE_ROOT, SYMLINK, CHANGED,
		UNSUPPORTED_FILESYSTEM, UNSUPPORTED_ENTRY, LIMIT
	}

	public static final class Violation extends IOException {
		private final Code code;
		public Violation(Code code, String path) { super(code + ": " + path); this.code = code; }
		public Code code() { return this.code; }
	}

	/** Constructor-time value validation cannot declare checked I/O exceptions. */
	public static final class InvalidRelativePath extends IllegalArgumentException {
		public InvalidRelativePath(String path) { super("INVALID_PATH: " + path); }
		public Code code() { return Code.INVALID_PATH; }
	}

	public record Node(Path path, BasicFileAttributes attributes) {
		public Node { Objects.requireNonNull(path); Objects.requireNonNull(attributes); }
	}

	public record Observation(String relativePath, Path target, List<Node> ancestors, BasicFileAttributes leaf) {
		public Observation { ancestors = List.copyOf(ancestors); }
	}

	private final Path root;
	private final Path agentHome;
	private final List<Path> allowedRoots;
	private final BasicFileAttributes rootIdentity;
	private final boolean staged;

	public PathPolicy(Path cwd, Path agentHome, List<Path> allowedRoots) throws IOException {
		this(cwd, agentHome, allowedRoots, false);
	}

	private PathPolicy(Path cwd, Path agentHome, List<Path> allowedRoots, boolean staged) throws IOException {
		this.root = cwd.toRealPath();
		this.rootIdentity = attributes(this.root);
		if (!this.rootIdentity.isDirectory()) throw new Violation(Code.UNSUPPORTED_ENTRY, this.root.toString());
		this.agentHome = canonicalFuture(agentHome);
		var roots = new ArrayList<Path>();
		roots.add(this.root);
		for (Path allowed : allowedRoots) roots.add(canonicalFuture(allowed));
		this.allowedRoots = List.copyOf(roots);
		this.staged = staged;
	}

	/** Only the owner stage allocated by ChangeStore may call this package-private factory. */
	static PathPolicy stagedView(Path stage, PathPolicy source) throws IOException {
		return new PathPolicy(stage, source.agentHome(), List.of(), true);
	}

	public Path root() { return this.root; }
	public Path agentHome() { return this.agentHome; }
	public List<Path> allowedRoots() { return this.allowedRoots; }

	/** Existing FS read scope; callers still enforce exclusions and their role. */
	public boolean allowsRead(Path path) throws IOException {
		Path actual = canonicalFuture(path);
		return this.allowedRoots.stream().anyMatch(actual::startsWith)
			&& (!actual.startsWith(this.agentHome) || this.staged && actual.startsWith(this.root));
	}

	private static Path canonicalFuture(Path path) throws IOException {
		Path absolute = path.toAbsolutePath().normalize();
		Path existing = absolute;
		for (;;) {
			try {
				Files.readAttributes(existing, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				return existing.toRealPath().resolve(existing.relativize(absolute));
			}
			catch (NoSuchFileException ex) {
				existing = existing.getParent();
				if (existing == null) throw ex;
			}
		}
	}

	public static String validateRelative(String path) {
		if (path == null || path.isEmpty() || path.indexOf('\\') >= 0
				|| path.chars().anyMatch(Character::isISOControl) || path.matches("^[A-Za-z]:.*")) {
			throw new InvalidRelativePath(path);
		}
		try {
			if (Path.of(path).isAbsolute()) throw new InvalidRelativePath(path);
		}
		catch (InvalidPathException ex) { throw new InvalidRelativePath(path); }
		for (String part : path.split("/", -1)) {
			if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new InvalidRelativePath(path);
		}
		return path;
	}

	/** Exclusions apply to snapshots, blobs, hashes and evidence, before content access. */
	public boolean excluded(String path) {
		validateRelative(path);
		if (!this.staged && this.root.resolve(path).startsWith(this.agentHome)) return true;
		String[] parts = path.split("/");
		for (int i = 0; i < parts.length; i++) {
			String n = parts[i].toLowerCase(Locale.ROOT);
			if (n.equals(".mini-agent")) {
				if (i + 1 == parts.length) continue; // Walk only far enough to find skills.
				if (!parts[i + 1].equals("skills")) return true;
				continue;
			}
			if (excludedName(n)) return true;
		}
		return false;
	}

	private static boolean excludedName(String n) {
		return Set.of(".git", "target", "build", "dist", "node_modules", ".gradle", ".m2",
				".omo", ".cache", ".config", ".ssh", ".aws", ".codex", ".claude",
				"auth", "authentication", "credentials", "keys", "certs", "certificates",
				"runs", "run-requests", "tool-evidence", "model-selections", "changes", "logs")
			.contains(n) || n.startsWith(".env") || n.contains("credential") || n.contains("secret")
			|| n.equals("auth.json") || n.equals(".npmrc") || n.equals(".netrc")
			|| n.startsWith("id_rsa") || n.startsWith("id_ed25519")
			|| n.endsWith(".key") || n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer")
			|| n.endsWith(".der") || n.endsWith(".p12") || n.endsWith(".pfx")
			|| n.endsWith(".jks") || n.endsWith(".keystore");
	}

	/** Observed pathname only; content readers must use Fingerprinter's no-follow read. */
	public Path resolve(String path) throws IOException { return observe(path).target(); }

	public Path resolveMutation(String path) throws IOException {
		Observation observed = observe(path);
		if (List.of(path.split("/")).stream().anyMatch(p -> p.equalsIgnoreCase(".mini-agent"))) {
			throw new Violation(Code.EXCLUDED, path);
		}
		if (observed.leaf() != null && observed.leaf().isSymbolicLink()) throw new Violation(Code.SYMLINK, path);
		return observed.target();
	}

	public Observation observe(String path) throws IOException {
		validateRelative(path);
		if (excluded(path)) throw new Violation(Code.EXCLUDED, path);
		BasicFileAttributes currentRoot = attributes(this.root);
		if (!sameIdentity(this.rootIdentity, currentRoot)) throw new Violation(Code.CHANGED, this.root.toString());
		List<Node> ancestors = new ArrayList<>();
		ancestors.add(new Node(this.root, currentRoot));
		Path target = this.root.resolve(path);
		Path parent = this.root;
		String[] parts = path.split("/");
		for (int i = 0; i < parts.length - 1; i++) {
			parent = parent.resolve(parts[i]);
			BasicFileAttributes attrs = optionalAttributes(parent);
			if (attrs == null) break;
			if (attrs.isSymbolicLink()) throw new Violation(Code.SYMLINK, path);
			if (!attrs.isDirectory()) throw new Violation(Code.UNSUPPORTED_ENTRY, path);
			if (!parent.toRealPath().startsWith(this.root)) throw new Violation(Code.OUTSIDE_ROOT, path);
			ancestors.add(new Node(parent, attrs));
		}
		BasicFileAttributes leaf = optionalAttributes(target);
		return new Observation(path, target, ancestors, leaf);
	}

	public void revalidate(Observation observation) throws IOException {
		Observation now = observe(observation.relativePath());
		if (!now.target().equals(observation.target()) || now.ancestors().size() != observation.ancestors().size()) {
			throw new Violation(Code.CHANGED, observation.relativePath());
		}
		for (int i = 0; i < now.ancestors().size(); i++) {
			if (!sameIdentity(now.ancestors().get(i).attributes(), observation.ancestors().get(i).attributes())) {
				throw new Violation(Code.CHANGED, observation.relativePath());
			}
		}
		if (!sameObservation(now.leaf(), observation.leaf())) throw new Violation(Code.CHANGED, observation.relativePath());
	}

	static BasicFileAttributes optionalAttributes(Path path) throws IOException {
		try { return attributes(path); }
		catch (NoSuchFileException ex) { return null; }
	}

	static BasicFileAttributes attributes(Path path) throws IOException {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (attrs.fileKey() == null) throw new Violation(Code.UNSUPPORTED_FILESYSTEM, path.toString());
			return attrs;
		}
		catch (UnsupportedOperationException ex) {
			throw new Violation(Code.UNSUPPORTED_FILESYSTEM, path.toString());
		}
	}

	private static boolean sameIdentity(BasicFileAttributes a, BasicFileAttributes b) {
		return a.fileKey().equals(b.fileKey()) && a.isDirectory() == b.isDirectory()
			&& a.isSymbolicLink() == b.isSymbolicLink() && a.isRegularFile() == b.isRegularFile();
	}

	private static boolean sameObservation(BasicFileAttributes a, BasicFileAttributes b) {
		if (a == null || b == null) return a == b;
		return sameIdentity(a, b) && a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
			&& a.creationTime().equals(b.creationTime());
	}
}
