package com.example.miniagent.plugin.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/** Bounded search shared by the two read-only file tools. */
public final class FileSearch {

	public record Match(String path, int line, String text) {}

	public record Page(List<Match> matches, @Nullable Integer nextOffset, boolean depthLimited,
			boolean byteLimited, boolean offsetLimited, int skippedFiles, int skippedLines) {}

	private final UnaryOperator<Path> resolve;
	private final int fileBytes;

	FileSearch(UnaryOperator<Path> resolve, long maxReadBytes) {
		this.resolve = resolve;
		this.fileBytes = (int) Math.max(0, Math.min(maxReadBytes, 1024 * 1024));
	}

	Page search(Path root, String expression, boolean glob, @Nullable Integer offset, @Nullable Integer limit) {
		int from = offset == null ? 0 : offset;
		int count = limit == null ? 50 : limit;
		if (from < 0 || from > 10000 || count < 1 || count > 100)
			throw new IllegalArgumentException("Search requires offset 0..10000 and limit 1..100");
		if (expression.isEmpty() || expression.length() > 256 || expression.chars().anyMatch(Character::isISOControl))
			throw new IllegalArgumentException("Search expression must be 1..256 chars without control characters");
		String[] pattern = expression.split("/", -1);
		if (glob && (pattern.length > 32 || expression.contains("\\") || expression.contains("[")
				|| expression.contains("]") || expression.contains("{") || expression.contains("}")
				|| List.of(pattern).stream().anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")
					|| p.contains("**") && !p.equals("**"))))
			throw new IllegalArgumentException("Glob supports only *, ?, and whole-segment ** in a relative path");
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			throw new IllegalArgumentException("Not a directory: " + root);
		var files = new ArrayList<Path>();
		boolean[] depthLimited = { false };
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<>() {
				private int visited;

				private void visit(Path path) {
					if (++this.visited > 10000) throw new IllegalArgumentException("Search traversal exceeds 10000 entries; narrow path");
				}

				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
					visit(dir);
					if (!dir.equals(root) && ignored(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
					resolve.apply(dir);
					if (!dir.equals(root) && root.relativize(dir).getNameCount() >= 16) {
						depthLimited[0] = true;
						return FileVisitResult.SKIP_SUBTREE;
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					visit(file);
					if (attrs.isRegularFile() && !ignored(file.getFileName().toString())
							&& root.relativize(file).toString().length() <= 1024) files.add(file);
					return FileVisitResult.CONTINUE;
				}
			});
			files.sort(Comparator.comparing(p -> root.relativize(p).toString()));
			var matches = new ArrayList<Match>();
			int seen = 0;
			int output = 0;
			int skippedFiles = 0;
			int skippedLines = 0;
			long bytes = 0;
			for (Path file : files) {
				Path actual = this.resolve.apply(file);
				if (!actual.equals(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
					throw new IllegalArgumentException("Search path changed: " + root.relativize(file));
				String relative = root.relativize(file).toString();
				List<Match> found = new ArrayList<>();
				if (glob) {
					if (globMatches(pattern, relative.split("/"))) found.add(new Match(relative, 0, ""));
				}
				else {
					long size = Files.size(file);
					if (size > this.fileBytes) { skippedFiles++; continue; }
					// Reserve the extra growth-detection byte before opening the file.
					if (bytes + this.fileBytes + 1 > 16L * 1024 * 1024)
						return new Page(List.copyOf(matches), null, depthLimited[0], true, false, skippedFiles, skippedLines);
					byte[] data;
					try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
						data = input.readNBytes(this.fileBytes + 1);
					}
					bytes += data.length;
					if (data.length > this.fileBytes) { skippedFiles++; continue; }
					String text;
					try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString(); }
					catch (CharacterCodingException ex) { skippedFiles++; continue; }
					if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
						skippedFiles++;
						continue;
					}
					// Bound each literal comparison to a single line, not the remaining file.
					int line = 0;
					for (int start = 0; start < text.length();) {
						int end = start;
						while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') end++;
						line++;
						if (end - start > 4096) skippedLines++;
						else {
							if (text.substring(start, end).contains(expression))
								found.add(new Match(relative, line, text.substring(start, Math.min(end, start + 512))));
						}
						start = end + 1;
						if (end < text.length() && text.charAt(end) == '\r' && start < text.length() && text.charAt(start) == '\n') start++;
						if (found.size() > from + count) break;
					}
				}
				for (Match match : found) {
					if (seen++ < from) continue;
					int length = match.path().length() + match.text().length();
					if (matches.size() == count || output + length > 16000)
						return new Page(List.copyOf(matches), from + matches.size() <= 10000 ? from + matches.size() : null,
							depthLimited[0], false, from + matches.size() > 10000, skippedFiles, skippedLines);
					matches.add(match);
					output += length;
				}
			}
			return new Page(List.copyOf(matches), null, depthLimited[0], false, false, skippedFiles, skippedLines);
		}
		catch (IOException ex) { throw new UncheckedIOException("Cannot search directory", ex); }
	}

	static boolean ignored(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return Set.of(".git", "target", "build", "dist", "out", "node_modules", "vendor", ".gradle", ".m2",
				".cache", ".config", ".idea", ".mini-agent", ".omo", ".ssh", ".aws", ".codex", ".claude",
				"auth", "authentication", "credentials", "keys", "certs", "certificates", "runs", "run-requests",
				"tool-evidence", "model-selections", "changes", "logs", ".npmrc", ".netrc", "auth.json",
				".credentials", "credentials.json", "secrets.json").contains(n)
			|| n.startsWith(".env") || n.startsWith("id_rsa") || n.startsWith("id_ed25519")
			|| List.of(".key", ".pem", ".crt", ".cer", ".der", ".p12", ".pfx", ".jks", ".keystore").stream().anyMatch(n::endsWith);
	}

	/** Dynamic programming avoids regex backtracking, including for adversarial wildcard patterns. */
	private static boolean globMatches(String[] pattern, String[] path) {
		boolean[][] match = new boolean[pattern.length + 1][path.length + 1];
		match[0][0] = true;
		for (int i = 1; i <= pattern.length; i++) {
			if (pattern[i - 1].equals("**")) match[i][0] = match[i - 1][0];
			for (int j = 1; j <= path.length; j++) {
				match[i][j] = pattern[i - 1].equals("**") ? match[i - 1][j] || match[i][j - 1]
					: match[i - 1][j - 1] && segmentMatches(pattern[i - 1], path[j - 1]);
			}
		}
		return match[pattern.length][path.length];
	}

	private static boolean segmentMatches(String pattern, String name) {
		boolean[][] match = new boolean[pattern.length() + 1][name.length() + 1];
		match[0][0] = true;
		for (int i = 1; i <= pattern.length(); i++) {
			char p = pattern.charAt(i - 1);
			if (p == '*') match[i][0] = match[i - 1][0];
			for (int j = 1; j <= name.length(); j++)
				match[i][j] = p == '*' ? match[i - 1][j] || match[i][j - 1]
					: match[i - 1][j - 1] && (p == '?' || p == name.charAt(j - 1));
		}
		return match[pattern.length()][name.length()];
	}
}
