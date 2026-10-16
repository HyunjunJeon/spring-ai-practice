package com.example.miniagent.core.change;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Shared live publication, not AtomicFiles' private-metadata protocol. */
final class EngineMutation {
	private EngineMutation() {}

	static void interrupted() throws InterruptedIOException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Change mutation interrupted");
	}

	static ChangeStore.Snapshot owned(ChangeStore.View view, java.util.UUID id,
			ChangeOwner owner, String digest) throws IOException {
		ChangeStore.Snapshot snapshot = view.get(id);
		if (!snapshot.changeSet().owner().equals(owner) || !snapshot.digest().equals(digest))
			throw new ApplyEngine.Failure(ApplyEngine.Code.IDENTITY_MISMATCH, snapshot.changeSet());
		return snapshot;
	}

	static void clean(ChangeStore.View view) throws ChangeStore.Failure {
		if (view.journalState() != ChangeJournal.State.CLEAN) {
			ChangeStore.Code code = switch (view.journalState()) {
				case TRUNCATED -> ChangeStore.Code.TRUNCATED_JOURNAL;
				case CORRUPT -> ChangeStore.Code.CORRUPT;
				case FUTURE_SCHEMA -> ChangeStore.Code.FUTURE_SCHEMA;
				case CLEAN -> throw new AssertionError();
			};
			throw new ChangeStore.Failure(code, "Journal must be inspected without repair");
		}
	}

	static List<Diff> ordered(List<Diff> diffs, boolean undo) {
		Comparator<Diff> order = Comparator.comparingInt(d -> rank(undo ? d.after() : d.before(), undo ? d.before() : d.after()));
		order = order.thenComparingInt(d -> {
			Fingerprint to = undo ? d.before() : d.after();
			int depth = d.relativePath().split("/").length;
			return to.kind() == EntryKind.MISSING ? -depth : depth;
		}).thenComparing(Diff::relativePath);
		return diffs.stream().sorted(order).toList();
	}

	private static int rank(Fingerprint from, Fingerprint to) {
		if (to.kind() == EntryKind.MISSING) return 0;
		if (to.kind() == EntryKind.DIRECTORY && from.kind() == EntryKind.MISSING) return 1;
		if (to.kind() == EntryKind.REGULAR_FILE) return 2;
		return 3;
	}

	static void bytes(Fingerprint endpoint, byte[] bytes) throws IOException {
		boolean file = endpoint.kind() == EntryKind.REGULAR_FILE;
		if (endpoint.kind() == EntryKind.SYMBOLIC_LINK || (file
				? bytes.length != endpoint.size() || !Fingerprinter.sha256(bytes).equals(endpoint.sha256())
				: bytes.length != 0))
			throw new IOException("Unverified mutation endpoint: " + endpoint.relativePath());
	}

	static Set<PosixFilePermission> permissions(int mode) {
		var permissions = EnumSet.noneOf(PosixFilePermission.class);
		var values = new PosixFilePermission[] { PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
			PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
			PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
			PosixFilePermission.OTHERS_EXECUTE };
		for (int i = 0; i < values.length; i++) if ((mode & (0400 >> i)) != 0) permissions.add(values[i]);
		return permissions;
	}

	static void mutate(PathPolicy policy, Fingerprint from, Fingerprint to, byte[] content) throws IOException {
		interrupted();
		bytes(to, content);
		String relative = from.relativePath();
		Path target = policy.resolveMutation(relative); // Exclusions before reading bytes.
		if (!new Fingerprinter(policy).fingerprint(relative).equals(from))
			throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, relative);
		PathPolicy.Observation observation = policy.observe(relative);
		Path temp = null;
		Set<PosixFilePermission> parentMode = null;
		try {
			if (to.kind() == EntryKind.MISSING) {
				policy.revalidate(observation);
				parentMode = grantOwnerWrite(target.getParent());
				Files.delete(target); // Never recursively remove human/excluded children.
			}
			else if (to.kind() == EntryKind.DIRECTORY && from.kind() == EntryKind.DIRECTORY) {
				policy.revalidate(observation);
				Files.setPosixFilePermissions(target, permissions(to.posixMode()));
				forceBoundary(target);
			}
			else {
				parentMode = grantOwnerWrite(target.getParent());
				if (to.kind() == EntryKind.DIRECTORY) {
					temp = Files.createTempDirectory(target.getParent(), ".change-publish-",
							PosixFilePermissions.asFileAttribute(permissions(to.posixMode())));
					Files.setPosixFilePermissions(temp, permissions(to.posixMode()));
					AtomicFiles.forceDirectory(temp);
				}
				else if (to.kind() == EntryKind.REGULAR_FILE) {
					temp = Files.createTempFile(target.getParent(), ".change-publish-", ".tmp",
							PosixFilePermissions.asFileAttribute(permissions(0600)));
					try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
						ByteBuffer buffer = ByteBuffer.wrap(content);
						while (buffer.hasRemaining()) {
							interrupted();
							channel.write(buffer);
						}
						Files.setPosixFilePermissions(temp, permissions(to.posixMode()));
						channel.force(true);
					}
				}
				else throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_ENTRY, relative);
				interrupted();
				policy.revalidate(observation);
				// Last byte/mode check under the operator's exclusive-writer assertion.
				if (!new Fingerprinter(policy).fingerprint(relative).equals(from))
					throw new PathPolicy.Violation(PathPolicy.Code.CHANGED, relative);
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				temp = null;
			}
			forceBoundary(target.getParent());
		}
		catch (java.nio.file.AtomicMoveNotSupportedException ex) {
			throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_FILESYSTEM, relative);
		}
		catch (UnsupportedOperationException ex) {
			throw new PathPolicy.Violation(PathPolicy.Code.UNSUPPORTED_FILESYSTEM, relative);
		}
		finally {
			try {
				if (temp != null) {
					boolean interrupted = Thread.interrupted();
					try {
						Files.delete(temp);
						AtomicFiles.forceDirectory(target.getParent());
					}
					finally { if (interrupted) Thread.currentThread().interrupt(); }
				}
			}
			finally {
				if (parentMode != null) {
					Files.setPosixFilePermissions(target.getParent(), parentMode);
					forceBoundary(target.getParent());
				}
			}
		}
	}

	/**
	 * A reviewed directory mode without owner write (mkdir x, write x/f, chmod 500 x) would block publishing
	 * or removing its entries. Grant owner write for this one entry only; the caller restores the returned
	 * exact mode, so the directory's own checkpoint still observes its reviewed mode.
	 */
	private static Set<PosixFilePermission> grantOwnerWrite(Path directory) throws IOException {
		Set<PosixFilePermission> mode = Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
		if (mode.contains(PosixFilePermission.OWNER_WRITE)) return null;
		var writable = EnumSet.noneOf(PosixFilePermission.class);
		writable.addAll(mode);
		writable.add(PosixFilePermission.OWNER_WRITE);
		Files.setPosixFilePermissions(directory, writable);
		return mode;
	}

	/** Once publication has happened, finish its force even if cancellation arrived. */
	private static void forceBoundary(Path directory) throws IOException {
		boolean interrupted = Thread.interrupted();
		try { AtomicFiles.forceDirectory(directory); }
		finally { if (interrupted) Thread.currentThread().interrupt(); }
	}

	static void failed(ChangeStore.Lease lease, ChangeSet set, Throwable cause, boolean undo) {
		boolean interrupted = Thread.interrupted();
		try {
			ChangeStore.Snapshot snapshot = lease.read().get(set.changeSetId());
			ChangeStatus status = snapshot.changeSet().status();
			ChangeStatus next = undo || snapshot.checkpoints().stream().anyMatch(c -> c.state() == Checkpoint.State.PREPARED)
					? ChangeStatus.RECOVERY_REQUIRED : ChangeStatus.PARTIALLY_APPLIED;
			if (status.canTransitionTo(next)) lease.transition(set.changeSetId(), set.owner(), set.digest(), next);
		}
		catch (IOException | RuntimeException cleanup) { cause.addSuppressed(cleanup); }
		finally { if (interrupted) Thread.currentThread().interrupt(); }
	}

	static void close(ChangeStore.Lease lease) throws IOException {
		boolean interrupted = Thread.interrupted();
		try { lease.close(); }
		finally { if (interrupted) Thread.currentThread().interrupt(); }
	}
}
