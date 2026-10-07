package com.example.miniagent.core.change;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import com.example.miniagent.core.approval.ApprovalDecision;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.run.RunId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class DiffRendererTest {
	@TempDir Path work;
	@TempDir Path outside;

	private PathPolicy policy() throws Exception { return new PathPolicy(this.work, this.outside.resolve("state"), List.of()); }
	private DiffRenderer renderer() throws Exception { return new DiffRenderer(policy()); }
	private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
	private static Fingerprint file(String path, byte[] bytes, int mode) {
		return new Fingerprint(path, EntryKind.REGULAR_FILE, Fingerprinter.sha256(bytes), bytes.length, mode, null);
	}
	private Diff diff(String path, String before, String after, int context) throws Exception {
		return new DiffRenderer(policy(), new DiffRenderer.Limits(1024 * 1024, 20000, 8000000, context))
			.render(file(path, bytes(before), 0644), bytes(before), file(path, bytes(after), 0644), bytes(after));
	}

	@Test void independentPatchReconstructionPreservesExactEndpointsAndLineEndings() throws Exception {
		List<String> texts = List.of("", "a", "a\n", "a\r\n", "a\r", "\n", "\r\n", "a\nb", "a\r\nb\r\n",
				"same\nmiddle\nlast", "same\nchanged\r\nlast", "same\nchanged\r\nlast\n",
				"caf\u00e9 \ud83d\ude80\r\n", "one\ntwo\nthree\nfour\nfive\nsix\nseven\n",
				"one\ntwo\nthree\nfour\nfive\nsix\nchanged\nseven\n");
		for (int context : List.of(0, 3)) {
			for (String before : texts) for (String after : texts) {
				if (before.equals(after)) continue;
				Diff diff = diff("file", before, after, context);
				assertThat(diff.content()).isEqualTo(Diff.Content.TEXT);
				assertThat(reconstruct(bytes(before), diff.patchBytes())).as("context=%s old=%s new=%s", context, before, after)
					.isEqualTo(bytes(after));
			}
		}
	}

	@Test void addDeleteAndEmptyFilesHaveCorrectZeroLineEndpoints() throws Exception {
		for (String text : List.of("", "new", "new\n", "new\r\n", "one\r\ntwo")) {
			byte[] raw = bytes(text);
			Fingerprint fp = file("nested/new", raw, 0600);
			Diff add = renderer().render(Fingerprint.missing("nested/new"), new byte[0], fp, raw);
			Diff delete = renderer().render(fp, raw, Fingerprint.missing("nested/new"), new byte[0]);
			assertThat(add.reviewable()).isTrue();
			assertThat(delete.reviewable()).isTrue();
			assertThat(reconstruct(new byte[0], add.patchBytes())).isEqualTo(raw);
			assertThat(reconstruct(raw, delete.patchBytes())).isEmpty();
		}
	}

	@Test void modeOnlySortedDigestAndPatchBytesAreBound() throws Exception {
		byte[] raw = bytes("same\r\n");
		Diff mode = renderer().render(file("a", raw, 0600), raw, file("a", raw, 0700), raw);
		assertThat(mode.content()).isEqualTo(Diff.Content.METADATA_ONLY);
		assertThat(reconstruct(raw, mode.patchBytes())).isEqualTo(raw);
		Diff other = diff("b", "old", "new", 3);
		assertThat(DiffRenderer.digest(List.of(mode, other))).isEqualTo(DiffRenderer.digest(List.of(other, mode)));
		Diff changedMode = renderer().render(file("a", raw, 0600), raw, file("a", raw, 0750), raw);
		assertThat(changedMode.digest()).isNotEqualTo(mode.digest());
		Diff alteredPatch = new Diff(other.before(), other.after(), other.content(), other.patch() + "\n", other.detail());
		assertThat(alteredPatch.digest()).isNotEqualTo(other.digest());
		assertThatThrownBy(() -> DiffRenderer.digest(List.of(mode, mode))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test void boundsBinaryInvalidUtf8AndMismatchedBytesAreExplicit() throws Exception {
		byte[] old = bytes("old\n"), fresh = bytes("new\n");
		assertOmitted(new DiffRenderer.Limits(1, 100, 1000, 3), old, fresh, Diff.Content.TOO_LARGE);
		assertOmitted(new DiffRenderer.Limits(100, 1, 1000, 3), old, fresh, Diff.Content.LINE_LIMIT);
		assertOmitted(new DiffRenderer.Limits(100, 100, 1, 3), old, fresh, Diff.Content.OPERATION_LIMIT);
		assertOmitted(DiffRenderer.Limits.defaults(), old, new byte[] { 0, 1 }, Diff.Content.BINARY);
		assertOmitted(DiffRenderer.Limits.defaults(), old, new byte[] { (byte) 0xc3 }, Diff.Content.INVALID_UTF8);
		assertThatThrownBy(() -> renderer().render(file("file", old, 0644), bytes("wrong"),
				file("file", fresh, 0644), fresh)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Diff(file("file", old, 0644), file("file", fresh, 0644),
				Diff.Content.METADATA_ONLY, "", "")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Diff(file("file", old, 0644), file("file", fresh, 0644),
				Diff.Content.TEXT, "", "")).isInstanceOf(IllegalArgumentException.class);
	}

	private void assertOmitted(DiffRenderer.Limits limits, byte[] old, byte[] fresh, Diff.Content content) throws Exception {
		Diff diff = new DiffRenderer(policy(), limits).render(file("file", old, 0644), old, file("file", fresh, 0644), fresh);
		assertThat(diff.content()).isEqualTo(content);
		assertThat(diff.reviewable()).isFalse();
		assertThat(diff.patchBytes()).isEmpty();
		assertThat(diff.before().sha256()).isEqualTo(Fingerprinter.sha256(old));
		assertThat(diff.after().sha256()).isEqualTo(Fingerprinter.sha256(fresh));
	}

	@Test void deniedPathsAndLiveSymlinksNeverProduceContent() throws Exception {
		for (String path : List.of(".env", "auth.json", "keys/private", ".mini-agent/skills/course/source")) {
			assertThatThrownBy(() -> renderer().render(file(path, bytes("old"), 0600), bytes("old"),
					file(path, bytes("new"), 0600), bytes("new"))).isInstanceOf(PathPolicy.Violation.class);
		}
		Files.createSymbolicLink(this.work.resolve("file"), this.outside.resolve("unread"));
		assertThatThrownBy(() -> diff("file", "old", "new", 3)).isInstanceOf(PathPolicy.Violation.class);
	}

	@Test void immutableApprovalBindsOwnerModelTreesAndCompleteDiff() throws Exception {
		var owner = new ChangeOwner("session", new RunId("run"), "attempt", "turn");
		UUID id = UUID.randomUUID(), binding = UUID.randomUUID();
		Diff diff = diff("file", "old", "new", 3);
		Instant now = Instant.parse("2026-10-06T00:00:00Z");
		var proposal = ChangeSet.propose(id, owner, this.work.toRealPath().toString(), new ModelRef("fake", "one"),
				List.of(binding), Map.of("file", diff.before()), Map.of("file", diff.after()), List.of(diff), now);
		var decision = new Approval(UUID.randomUUID(), id, proposal.digest(), owner, "console", ApprovalDecision.APPROVED, now);
		var approved = proposal.decide(decision);
		assertThat(proposal.status()).isEqualTo(ChangeStatus.PENDING_APPROVAL);
		assertThat(approved.status()).isEqualTo(ChangeStatus.APPROVED);
		assertThat(approved.digest()).isEqualTo(proposal.digest());
		assertThat(approved.transition(ChangeStatus.APPLYING).transition(ChangeStatus.APPLIED).transition(ChangeStatus.UNDONE).status())
			.isEqualTo(ChangeStatus.UNDONE);
		assertThatThrownBy(() -> proposal.transition(ChangeStatus.APPLYING)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> approved.decide(decision)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> proposal.decide(new Approval(UUID.randomUUID(), id, "0".repeat(64), owner,
				"console", ApprovalDecision.APPROVED, now))).isInstanceOf(IllegalArgumentException.class);
		var omitted = new Diff(diff.before(), diff.after(), Diff.Content.BINARY, "", "BINARY");
		var blocked = ChangeSet.propose(id, owner, proposal.cwd(), proposal.model(), List.of(binding),
				proposal.baseline(), proposal.proposed(), List.of(omitted), now);
		assertThatThrownBy(() -> blocked.decide(new Approval(UUID.randomUUID(), id, blocked.digest(), owner,
				"console", ApprovalDecision.APPROVED, now))).isInstanceOf(IllegalArgumentException.class);
		assertThat(approved.transition(ChangeStatus.STALE).approval()).isEqualTo(decision);
		assertThatThrownBy(() -> approved.transition(ChangeStatus.STALE).transition(ChangeStatus.APPLYING))
			.isInstanceOf(IllegalStateException.class);
		var drifted = ChangeSet.propose(id, owner, proposal.cwd(), new ModelRef("fake", "two"), List.of(binding),
				proposal.baseline(), proposal.proposed(), List.of(diff), now);
		assertThat(drifted.digest()).isNotEqualTo(proposal.digest());
		assertThatThrownBy(() -> drifted.decide(decision)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> proposal.diffs().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test void checkpointBlobsAndVerificationAreExactAndImmutable() throws Exception {
		Diff diff = diff("file", "old", "new", 3);
		var owner = new ChangeOwner("session", new RunId("run"), "attempt", "turn");
		UUID id = UUID.randomUUID();
		Instant now = Instant.parse("2026-10-06T00:00:00Z");
		var checkpoint = new Checkpoint(UUID.randomUUID(), id, owner, diff.before(), diff.after(),
				new Checkpoint.BlobRef(diff.before().sha256(), diff.before().size()),
				new Checkpoint.BlobRef(diff.after().sha256(), diff.after().size()), Checkpoint.State.PREPARED, now);
		assertThat(checkpoint.state()).isEqualTo(Checkpoint.State.PREPARED);
		assertThatThrownBy(() -> new Checkpoint(UUID.randomUUID(), id, owner, diff.before(), diff.after(),
				null, checkpoint.afterBlob(), Checkpoint.State.PREPARED, now)).isInstanceOf(IllegalArgumentException.class);
		var args = new ArrayList<>(List.of("java", "arg with spaces", ""));
		var evidence = new VerificationEvidence(UUID.randomUUID(), id, owner, args, this.work.toRealPath().toString(),
				diff.digest(), "a".repeat(64), "b".repeat(64), 0, false, now, now.plusSeconds(1));
		args.clear();
		assertThat(evidence.argv()).containsExactly("java", "arg with spaces", "");
		assertThat(evidence.successful()).isTrue();
		assertThat(evidence.validFor(diff.digest(), "a".repeat(64))).isTrue();
		assertThat(evidence.validFor("c".repeat(64), "a".repeat(64))).isFalse();
		assertThat(new VerificationEvidence(UUID.randomUUID(), id, owner, List.of("java"), evidence.cwd(),
				evidence.sourceRevision(), evidence.configurationDigest(), evidence.logSha256(), null, true, now, now)
				.successful()).isFalse();
	}

	/**
	 * Independent unified-patch interpreter. It validates deleted/context bytes and
	 * hunk counts, then rebuilds proposed bytes, including standard no-newline markers.
	 */
	private static byte[] reconstruct(byte[] source, byte[] patchBytes) {
		String sourceText = new String(source, StandardCharsets.UTF_8);
		List<String> old = sourceText.isEmpty() ? List.of() : Arrays.asList(sourceText.split("(?<=\\n)", -1));
		if (!old.isEmpty() && old.getLast().isEmpty()) old = old.subList(0, old.size() - 1);
		String[] patch = new String(patchBytes, StandardCharsets.UTF_8).split("\n", -1);
		Pattern hunk = Pattern.compile("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@");
		var output = new StringBuilder();
		int cursor = 0;
		for (int i = 0; i < patch.length; i++) {
			var match = hunk.matcher(patch[i]);
			if (!match.matches()) continue;
			int oldCount = Integer.parseInt(match.group(2)), newCount = Integer.parseInt(match.group(4));
			int start = Integer.parseInt(match.group(1)) - (oldCount == 0 ? 0 : 1);
			while (cursor < start) output.append(old.get(cursor++));
			assertThat(cursor).isEqualTo(start);
			int consumed = 0, produced = 0;
			int newStart = Integer.parseInt(match.group(3)) - (newCount == 0 ? 0 : 1);
			assertThat(output.toString().isEmpty() ? 0 : output.toString().split("(?<=\\n)").length).isEqualTo(newStart);
			while (consumed < oldCount || produced < newCount) {
				String line = patch[++i];
				char op = line.charAt(0);
				String payload = line.substring(1) + "\n";
				if (i + 1 < patch.length && patch[i + 1].equals("\\ No newline at end of file")) {
					payload = payload.substring(0, payload.length() - 1);
					i++;
				}
				if (op == '-' || op == ' ') {
					assertThat(old.get(cursor++)).isEqualTo(payload);
					consumed++;
				}
				if (op == '+' || op == ' ') { output.append(payload); produced++; }
				assertThat(op).isIn('-', '+', ' ');
			}
			assertThat(consumed).isEqualTo(oldCount);
			assertThat(produced).isEqualTo(newCount);
		}
		while (cursor < old.size()) output.append(old.get(cursor++));
		return output.toString().getBytes(StandardCharsets.UTF_8);
	}
}
