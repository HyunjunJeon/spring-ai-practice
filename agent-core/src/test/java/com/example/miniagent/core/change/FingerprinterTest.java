package com.example.miniagent.core.change;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class FingerprinterTest {
	@TempDir Path work;
	@TempDir Path outside;

	private Fingerprinter printer() throws Exception {
		return new Fingerprinter(new PathPolicy(this.work, this.outside.resolve("state"), List.of()));
	}

	@Test void rawBytesSizeModeReadAndDeletion() throws Exception {
		Path file = Files.createDirectories(this.work.resolve("nested")).resolve("raw");
		byte[] original = "one\r\ntwo".getBytes(StandardCharsets.UTF_8);
		Files.write(file, original);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-x---"));
		var printer = printer();
		var observation = printer.read("nested/raw");
		var fp = observation.fingerprint();
		assertThat(fp.kind()).isEqualTo(EntryKind.REGULAR_FILE);
		assertThat(fp.size()).isEqualTo(original.length);
		assertThat(fp.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
		assertThat(fp.posixMode()).isEqualTo(0750);
		assertThat(observation.bytes()).isEqualTo(original);
		byte[] mutated = observation.bytes();
		mutated[0] = 0;
		assertThat(observation.bytes()).isEqualTo(original);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		assertThat(printer.fingerprint("nested/raw")).isNotEqualTo(fp);
		assertThat(printer.fingerprint("nested/raw").sha256()).isEqualTo(fp.sha256());
		Files.writeString(file, "one\ntwo");
		assertThat(printer.fingerprint("nested/raw").sha256()).isNotEqualTo(fp.sha256());
		Files.delete(file);
		assertThat(printer.fingerprint("nested/raw")).isEqualTo(Fingerprint.missing("nested/raw"));
	}

	@Test void linkTextFingerprintNeverReadsOutsideOrDanglingTarget() throws Exception {
		Path target = Files.writeString(this.outside.resolve("target"), "must not be hashed");
		Path link = Files.createSymbolicLink(this.work.resolve("alias"), target);
		var printer = printer();
		var actual = printer.read("alias");
		assertThat(actual.fingerprint().kind()).isEqualTo(EntryKind.SYMBOLIC_LINK);
		assertThat(actual.fingerprint().linkText()).isEqualTo(target.toString());
		assertThat(actual.bytes()).isEqualTo(target.toString().getBytes(StandardCharsets.UTF_8));
		assertThat(actual.fingerprint().sha256()).isNotEqualTo(printerHash(Files.readAllBytes(target)));
		Files.delete(target);
		assertThat(printer.fingerprint("alias")).isEqualTo(actual.fingerprint());
		Files.delete(link);
		Files.createSymbolicLink(link, Path.of("different"));
		assertThat(printer.fingerprint("alias")).isNotEqualTo(actual.fingerprint());
	}

	@Test void snapshotsFilterBeforeContentAndRetainOnlySkillSource() throws Exception {
		for (String path : List.of(".git/config", "target/classes/large", "build/output", "node_modules/lib/code",
				".mini-agent/auth/token", ".mini-agent/logs/output", "runtime/blob", "auth.json", ".env.local", "private.pem")) {
			Path file = this.work.resolve(path);
			Files.createDirectories(file.getParent());
			// Broken symbolic links are sufficient: dereferencing one would make this test fail.
			Files.createSymbolicLink(file, this.outside.resolve("does-not-exist"));
		}
		Files.createDirectories(this.work.resolve(".mini-agent/skills/course"));
		Files.writeString(this.work.resolve(".mini-agent/skills/course/SKILL.md"), "read only");
		Files.writeString(this.work.resolve("source"), "source");
		var printer = new Fingerprinter(new PathPolicy(this.work, this.work.resolve("runtime"), List.of()), 64, 20);
		var tree = printer.snapshot();
		assertThat(tree.keySet()).contains("source", ".mini-agent/skills/course/SKILL.md")
			.doesNotContain(".env.local", "private.pem", "auth.json", "runtime", "target", "node_modules");
		assertThat(tree.values()).allMatch(fp -> !fp.relativePath().contains("auth") && !fp.relativePath().contains("logs"));
		assertThat(new java.util.ArrayList<>(tree.keySet())).isSorted();
		assertThatThrownBy(() -> tree.clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> printer.read(".env.local")).isInstanceOf(PathPolicy.Violation.class);
	}

	@Test void boundsDoNotReturnPartialSnapshots() throws Exception {
		Files.writeString(this.work.resolve("large"), "0123456789");
		var policy = new PathPolicy(this.work, this.outside.resolve("state"), List.of());
		assertThatThrownBy(() -> new Fingerprinter(policy, 4, 10).read("large"))
			.isInstanceOf(PathPolicy.Violation.class)
			.satisfies(ex -> assertThat(((PathPolicy.Violation) ex).code()).isEqualTo(PathPolicy.Code.LIMIT));
		Files.writeString(this.work.resolve("second"), "x");
		assertThatThrownBy(() -> new Fingerprinter(policy, 64, 1).snapshot()).isInstanceOf(PathPolicy.Violation.class);
	}

	@Test void permissionDeniedIsNotConvertedToMissingOrEmptyContent() throws Exception {
		Path file = Files.writeString(this.work.resolve("unreadable"), "source");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
		try {
			org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(file), "Privileged user bypasses POSIX read denial");
			assertThatThrownBy(() -> printer().read("unreadable")).isInstanceOf(java.nio.file.AccessDeniedException.class);
		}
		finally {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		}
	}

	@Test void sourceDigestSortsAndIncludesKindsModesAndRawBytes() throws Exception {
		Files.writeString(this.work.resolve("b"), "b\r\n");
		Files.writeString(this.work.resolve("a"), "a\n");
		var tree = printer().snapshot();
		assertThat(Fingerprinter.treeDigest(Map.of("b", tree.get("b"), "a", tree.get("a"))))
			.isEqualTo(Fingerprinter.treeDigest(tree));
		Files.setPosixFilePermissions(this.work.resolve("a"), PosixFilePermissions.fromString("rwx------"));
		assertThat(Fingerprinter.treeDigest(printer().snapshot())).isNotEqualTo(Fingerprinter.treeDigest(tree));
	}

	@Test void unsupportedFilesystemIsTypedWithoutWeakerFallback() throws Exception {
		URI uri = URI.create("jar:" + this.outside.resolve("fixture.zip").toUri());
		try (var zip = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
			assertThatThrownBy(() -> new PathPolicy(zip.getPath("/"), zip.getPath("/state"), List.of()))
				.isInstanceOf(PathPolicy.Violation.class)
				.satisfies(ex -> assertThat(((PathPolicy.Violation) ex).code()).isEqualTo(PathPolicy.Code.UNSUPPORTED_FILESYSTEM));
		}
	}

	private static String printerHash(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
