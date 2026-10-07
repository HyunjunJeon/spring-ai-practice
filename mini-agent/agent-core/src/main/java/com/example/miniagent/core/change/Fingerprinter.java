package com.example.miniagent.core.change;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Bounded no-follow observations. Neither a snapshot transaction nor a writer lock. */
public final class Fingerprinter {
	public record Observation(Fingerprint fingerprint, byte[] bytes) {
		public Observation { bytes = bytes.clone(); }
		@Override public byte[] bytes() { return this.bytes.clone(); }
	}

	private final PathPolicy policy;
	private final long maxBytes;
	private final int maxEntries;

	public Fingerprinter(PathPolicy policy) { this(policy, 16 * 1024 * 1024, 10000); }
	public Fingerprinter(PathPolicy policy, long maxBytes, int maxEntries) {
		this.policy = java.util.Objects.requireNonNull(policy);
		if (maxBytes < 0 || maxBytes > Integer.MAX_VALUE - 8 || maxEntries < 1) {
			throw new IllegalArgumentException("Invalid fingerprint limits");
		}
		this.maxBytes = maxBytes;
		this.maxEntries = maxEntries;
	}

	public Fingerprint fingerprint(String path) throws IOException { return read(path).fingerprint(); }

	public Observation read(String path) throws IOException {
		PathPolicy.Observation observed = this.policy.observe(path); // Deny before content/mode/link reads.
		BasicFileAttributes attrs = observed.leaf();
		if (attrs == null) {
			this.policy.revalidate(observed);
			return new Observation(Fingerprint.missing(path), new byte[0]);
		}
		Path file = observed.target();
		int mode = mode(file);
		byte[] bytes;
		EntryKind kind;
		String link = null;
		if (attrs.isSymbolicLink()) {
			kind = EntryKind.SYMBOLIC_LINK;
			link = Files.readSymbolicLink(file).toString();
			bytes = link.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > this.maxBytes) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, path);
		}
		else if (attrs.isDirectory()) { kind = EntryKind.DIRECTORY; bytes = new byte[0]; }
		else if (attrs.isRegularFile()) {
			kind = EntryKind.REGULAR_FILE;
			if (attrs.size() > this.maxBytes) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, path);
			try (SeekableByteChannel channel = Files.newByteChannel(file,
					Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
				var output = new ByteArrayOutputStream();
				ByteBuffer buffer = ByteBuffer.allocate(8192);
				for (int n; (n = channel.read(buffer)) != -1;) {
					if ((long) output.size() + n > this.maxBytes) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, path);
					output.write(buffer.array(), 0, n);
					buffer.clear();
				}
				bytes = output.toByteArray();
			}
			catch (UnsupportedOperationException ex) {
				throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_FILESYSTEM, path);
			}
			if (bytes.length != attrs.size()) throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, path);
		}
		else throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_ENTRY, path);
		this.policy.revalidate(observed);
		if (mode(file) != mode || kind == EntryKind.SYMBOLIC_LINK && !link.equals(Files.readSymbolicLink(file).toString())) {
			throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, path);
		}
		return new Observation(new Fingerprint(path, kind, sha256(bytes), bytes.length, mode, link), bytes);
	}

	private static int mode(Path file) throws IOException {
		try {
			PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (view == null) throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_FILESYSTEM, file.toString());
			int mode = 0;
			for (PosixFilePermission p : view.readAttributes().permissions()) {
				mode |= switch (p) {
					case OWNER_READ -> 0400; case OWNER_WRITE -> 0200; case OWNER_EXECUTE -> 0100;
					case GROUP_READ -> 0040; case GROUP_WRITE -> 0020; case GROUP_EXECUTE -> 0010;
					case OTHERS_READ -> 0004; case OTHERS_WRITE -> 0002; case OTHERS_EXECUTE -> 0001;
				};
			}
			return mode;
		}
		catch (UnsupportedOperationException ex) {
			throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_FILESYSTEM, file.toString());
		}
	}

	public Map<String, Fingerprint> snapshot() throws IOException {
		var entries = new TreeMap<String, Fingerprint>();
		Files.walkFileTree(this.policy.root(), new SimpleFileVisitor<>() {
			private String relative(Path p) { return policy.root().relativize(p).toString().replace('\\', '/'); }
			private void capture(Path p) throws IOException {
				if (entries.size() >= maxEntries) throw new PathPolicy.Violation(PathPolicy.Code.LIMIT, relative(p));
				Fingerprint f = fingerprint(relative(p));
				if (f.kind() == EntryKind.MISSING) throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, relative(p));
				entries.put(f.relativePath(), f);
			}
			@Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (dir.equals(policy.root())) return FileVisitResult.CONTINUE;
				if (policy.excluded(relative(dir))) return FileVisitResult.SKIP_SUBTREE;
				capture(dir);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!policy.excluded(relative(file))) capture(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFileFailed(Path file, IOException ex) throws IOException {
				if (!policy.excluded(relative(file))) throw ex;
				return FileVisitResult.CONTINUE;
			}
		});
		return Collections.unmodifiableMap(entries);
	}

	public static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
	}

	public static String treeDigest(Map<String, Fingerprint> entries) {
		try {
			var bytes = new ByteArrayOutputStream();
			var out = new DataOutputStream(bytes);
			DiffRenderer.field(out, "source-tree-v1");
			out.writeInt(entries.size());
			for (var entry : new TreeMap<>(entries).entrySet()) {
				if (!entry.getKey().equals(entry.getValue().relativePath()) || entry.getValue().kind() == EntryKind.MISSING) {
					throw new IllegalArgumentException("Invalid source tree");
				}
				DiffRenderer.fingerprint(out, entry.getValue());
			}
			return sha256(bytes.toByteArray());
		}
		catch (IOException ex) { throw new AssertionError(ex); }
	}
}
