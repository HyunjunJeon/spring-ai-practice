package com.example.miniagent.core.change;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Deterministic single full-change hunk, not a minimum edit script. */
public final class DiffRenderer {
	public record Limits(long maxBytes, int maxLines, long maxOperations, int context) {
		public Limits {
			if (maxBytes < 0 || maxBytes > Integer.MAX_VALUE - 8 || maxLines < 0 || maxOperations < 0 || context < 0) {
				throw new IllegalArgumentException("Invalid diff limits");
			}
		}
		public static Limits defaults() { return new Limits(1024 * 1024, 20000, 8000000, 3); }
	}

	private record Line(String body, boolean terminated) {}
	private static final class Bound extends RuntimeException {
		final Diff.Content content;
		Bound(Diff.Content content) { this.content = content; }
	}
	private static final class Budget {
		private long remaining;
		Budget(long remaining) { this.remaining = remaining; }
		void charge(long amount) {
			if (amount > this.remaining) throw new Bound(Diff.Content.OPERATION_LIMIT);
			this.remaining -= amount;
		}
	}

	private final PathPolicy policy;
	private final Limits limits;
	public DiffRenderer(PathPolicy policy) { this(policy, Limits.defaults()); }
	public DiffRenderer(PathPolicy policy, Limits limits) {
		this.policy = Objects.requireNonNull(policy);
		this.limits = Objects.requireNonNull(limits);
	}

	public Diff render(Fingerprint before, byte[] beforeBytes, Fingerprint after, byte[] afterBytes) throws IOException {
		Objects.requireNonNull(before);
		Objects.requireNonNull(after);
		String path = before.relativePath();
		if (!path.equals(after.relativePath()) || before.equals(after)) {
			throw new IllegalArgumentException("Diff requires distinct fingerprints on the same path");
		}
		// Caller-supplied bytes do not bypass secret/read-only/link mutation policy.
		this.policy.resolveMutation(path);
		Objects.requireNonNull(beforeBytes);
		Objects.requireNonNull(afterBytes);
		if ((long) beforeBytes.length + afterBytes.length > this.limits.maxBytes()
				|| before.size() > this.limits.maxBytes() || after.size() > this.limits.maxBytes()) {
			return omitted(before, after, Diff.Content.TOO_LARGE);
		}
		try {
			var budget = new Budget(this.limits.maxOperations());
			verifyBytes(before, beforeBytes, budget);
			verifyBytes(after, afterBytes, budget);
			if (before.kind() == EntryKind.SYMBOLIC_LINK || after.kind() == EntryKind.SYMBOLIC_LINK
					|| before.kind() == EntryKind.DIRECTORY && after.kind() == EntryKind.REGULAR_FILE
					|| after.kind() == EntryKind.DIRECTORY && before.kind() == EntryKind.REGULAR_FILE) {
				return omitted(before, after, Diff.Content.UNSUPPORTED);
			}
			String header = header(before, after);
			budget.charge(header.length());
			// Identical raw content, or empty add/delete: metadata captures kind/mode/existence.
			budget.charge(Math.max(beforeBytes.length, afterBytes.length));
			if (java.util.Arrays.equals(beforeBytes, afterBytes)) {
				return new Diff(before, after, Diff.Content.METADATA_ONLY, header, "kind/mode/existence only");
			}
			String oldText = decode(beforeBytes, budget);
			String newText = decode(afterBytes, budget);
			List<Line> oldLines = lines(oldText, budget);
			List<Line> newLines = lines(newText, budget);
			if ((long) oldLines.size() + newLines.size() > this.limits.maxLines()) throw new Bound(Diff.Content.LINE_LIMIT);
			int prefix = 0;
			while (prefix < oldLines.size() && prefix < newLines.size()) {
				if (!equal(oldLines.get(prefix), newLines.get(prefix), budget)) break;
				prefix++;
			}
			int suffix = 0;
			while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix) {
				if (!equal(oldLines.get(oldLines.size() - suffix - 1), newLines.get(newLines.size() - suffix - 1), budget)) break;
				suffix++;
			}
			int start = Math.max(0, prefix - this.limits.context());
			int trailing = Math.min(suffix, this.limits.context());
			int oldEnd = oldLines.size() - suffix + trailing;
			int newEnd = newLines.size() - suffix + trailing;
			int oldCount = oldEnd - start;
			int newCount = newEnd - start;
			String hunk = "@@ -" + (oldCount == 0 ? start : start + 1) + "," + oldCount
				+ " +" + (newCount == 0 ? start : start + 1) + "," + newCount + " @@\n";
			budget.charge(hunk.length());
			var patch = new StringBuilder(header).append(hunk);
			for (int i = start; i < prefix; i++) append(patch, ' ', oldLines.get(i), budget);
			for (int i = prefix; i < oldLines.size() - suffix; i++) append(patch, '-', oldLines.get(i), budget);
			for (int i = prefix; i < newLines.size() - suffix; i++) append(patch, '+', newLines.get(i), budget);
			for (int i = oldLines.size() - suffix; i < oldEnd; i++) append(patch, ' ', oldLines.get(i), budget);
			return new Diff(before, after, Diff.Content.TEXT, patch.toString(), "single full-change hunk with context");
		}
		catch (Bound ex) { return omitted(before, after, ex.content); }
		catch (CharacterCodingException ex) { return omitted(before, after, Diff.Content.INVALID_UTF8); }
	}

	private static Diff omitted(Fingerprint before, Fingerprint after, Diff.Content content) {
		return new Diff(before, after, content, "", content.name());
	}

	private static void verifyBytes(Fingerprint fp, byte[] bytes, Budget budget) {
		budget.charge(bytes.length);
		if (fp.kind() == EntryKind.MISSING ? bytes.length != 0
				: fp.size() != bytes.length || !fp.sha256().equals(Fingerprinter.sha256(bytes))) {
			throw new IllegalArgumentException("Supplied bytes do not match fingerprint");
		}
	}

	private String decode(byte[] bytes, Budget budget) throws CharacterCodingException {
		budget.charge(bytes.length);
		String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		budget.charge(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t') throw new Bound(Diff.Content.BINARY);
		}
		return text;
	}

	private List<Line> lines(String text, Budget budget) {
		var lines = new ArrayList<Line>();
		int start = 0;
		budget.charge(text.length());
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				if (lines.size() >= this.limits.maxLines()) throw new Bound(Diff.Content.LINE_LIMIT);
				lines.add(new Line(text.substring(start, i), true));
				start = i + 1;
			}
		}
		if (start < text.length()) {
			if (lines.size() >= this.limits.maxLines()) throw new Bound(Diff.Content.LINE_LIMIT);
			lines.add(new Line(text.substring(start), false));
		}
		return lines;
	}

	private static boolean equal(Line a, Line b, Budget budget) {
		budget.charge((long) Math.max(a.body().length(), b.body().length()) + 1);
		return a.equals(b);
	}

	private static void append(StringBuilder patch, char operation, Line line, Budget budget) {
		budget.charge((long) line.body().length() + (line.terminated() ? 2 : 30));
		patch.append(operation).append(line.body()).append('\n');
		if (!line.terminated()) patch.append("\\ No newline at end of file\n");
	}

	private static String header(Fingerprint before, Fingerprint after) {
		String a = quote("a/" + before.relativePath());
		String b = quote("b/" + after.relativePath());
		String header = "diff --git " + a + " " + b + "\n";
		if (before.kind() == EntryKind.MISSING) header += "new file mode " + mode(after) + "\n";
		else if (after.kind() == EntryKind.MISSING) header += "deleted file mode " + mode(before) + "\n";
		else if (before.posixMode() != after.posixMode() || before.kind() != after.kind()) {
			header += "old mode " + mode(before) + "\nnew mode " + mode(after) + "\n";
		}
		return header + "--- " + (before.kind() == EntryKind.MISSING ? "/dev/null" : a)
			+ "\n+++ " + (after.kind() == EntryKind.MISSING ? "/dev/null" : b) + "\n";
	}

	private static String mode(Fingerprint fp) {
		return String.format(java.util.Locale.ROOT, "%06o",
				(fp.kind() == EntryKind.DIRECTORY ? 040000 : 0100000) | fp.posixMode());
	}

	private static String quote(String path) {
		if (path.chars().allMatch(c -> c > 32 && c < 127 && c != '"' && c != '\\')) return path;
		var out = new StringBuilder("\"");
		for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
			int b = Byte.toUnsignedInt(value);
			if (b == '"' || b == '\\') out.append('\\').append((char) b);
			else if (b >= 32 && b < 127) out.append((char) b);
			else out.append('\\').append(String.format(java.util.Locale.ROOT, "%03o", b));
		}
		return out.append('"').toString();
	}

	/** Sorted length-prefixed canonical encoding includes kinds, modes, endpoints and patch bytes. */
	public static String digest(List<Diff> diffs) {
		try {
			var bytes = new ByteArrayOutputStream();
			var out = new DataOutputStream(bytes);
			field(out, "diff-v1");
			out.writeInt(diffs.size());
			var seen = new HashSet<String>();
			for (Diff diff : diffs.stream().sorted(Comparator.comparing(Diff::relativePath)).toList()) {
				if (!seen.add(diff.relativePath())) throw new IllegalArgumentException("Duplicate diff path");
				fingerprint(out, diff.before());
				fingerprint(out, diff.after());
				field(out, diff.content().name());
				field(out, diff.patch());
				field(out, diff.detail());
			}
			return Fingerprinter.sha256(bytes.toByteArray());
		}
		catch (IOException ex) { throw new AssertionError(ex); }
	}

	static void fingerprint(DataOutputStream out, Fingerprint fp) throws IOException {
		field(out, fp.relativePath());
		field(out, fp.kind().name());
		field(out, fp.sha256());
		out.writeLong(fp.size());
		out.writeInt(fp.posixMode());
		field(out, fp.linkText());
	}

	static void field(DataOutputStream out, String value) throws IOException {
		if (value == null) { out.writeInt(-1); return; }
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		out.writeInt(bytes.length);
		out.write(bytes);
	}
}
