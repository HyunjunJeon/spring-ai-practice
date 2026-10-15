package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** Strict POSIX publication. Failed temporary bytes are retained for diagnosis. */
public final class AtomicFiles {
	private AtomicFiles() {}

	static void checkDirectory(Path path) throws IOException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
			throw new ChangeStore.Failure(CORRUPT, "Unsafe change directory: " + path);
		if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null)
			throw new ChangeStore.Failure(UNSUPPORTED_FILESYSTEM, "Change storage requires POSIX attributes: " + path);
		if (path.getParent() != null) checkDirectory(path.getParent());
	}

	static void checkFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
			throw new ChangeStore.Failure(CORRUPT, "Unsafe change file: " + path);
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rw-------")))
			throw new ChangeStore.Failure(CORRUPT, "Change file is not private: " + path);
	}

	static void directory(Path path) throws IOException {
		try {
			Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			forceDirectory(path.getParent());
		}
		catch (FileAlreadyExistsException ex) { checkDirectory(path); }
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
				.equals(PosixFilePermissions.fromString("rwx------")))
			throw new ChangeStore.Failure(CORRUPT, "Change directory is not private: " + path);
	}

	static void createFile(Path path) throws IOException {
		try {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			forceFile(path);
			forceDirectory(path.getParent());
		}
		catch (FileAlreadyExistsException ex) { checkFile(path); }
	}

	public static byte[] read(Path path, int maxBytes) throws ChangeStore.Failure {
		try {
			checkDirectory(path.getParent());
			checkFile(path);
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
				if (channel.size() > maxBytes) throw new ChangeStore.Failure(LIMIT, "Change file exceeds read limit");
				ByteBuffer buffer = ByteBuffer.allocate((int) channel.size() + 1);
				while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
				if (buffer.position() > maxBytes) throw new ChangeStore.Failure(LIMIT, "Change file exceeds read limit");
				return Arrays.copyOf(buffer.array(), buffer.position());
			}
		}
		catch (IOException | UnsupportedOperationException ex) { throw failure(ex); }
	}

	public static void write(Path target, byte[] bytes, int maxBytes) throws ChangeStore.Failure {
		try {
			if (bytes.length > maxBytes) throw new ChangeStore.Failure(LIMIT, "Change file exceeds write limit");
			checkDirectory(target.getParent());
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) checkFile(target);
			Path temp = Files.createTempFile(target.getParent(), ".pending-", ".tmp",
					PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				writeFully(channel, bytes);
				try { channel.force(true); }
				catch (IOException | UnsupportedOperationException ex) {
					throw new ChangeStore.Failure(UNSUPPORTED_FILESYSTEM, "File force failed", ex);
				}
			}
			Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			forceDirectory(target.getParent());
		}
		catch (IOException | UnsupportedOperationException ex) { throw failure(ex); }
	}

	static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		while (buffer.hasRemaining()) channel.write(buffer);
	}

	public static void forceDirectory(Path directory) throws ChangeStore.Failure {
		try {
			checkDirectory(directory);
			try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
				channel.force(true);
			}
		}
		catch (IOException | UnsupportedOperationException ex) {
			throw new ChangeStore.Failure(UNSUPPORTED_FILESYSTEM, "Directory force failed: " + directory, ex);
		}
	}

	static void forceFile(Path path) throws ChangeStore.Failure {
		try {
			checkDirectory(path.getParent());
			checkFile(path);
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
				channel.force(true);
			}
		}
		catch (IOException | UnsupportedOperationException ex) {
			throw new ChangeStore.Failure(UNSUPPORTED_FILESYSTEM, "File force failed: " + path, ex);
		}
	}

	static ChangeStore.Failure failure(Exception ex) {
		if (ex instanceof ChangeStore.Failure failure) return failure;
		return new ChangeStore.Failure(ex instanceof AtomicMoveNotSupportedException
				|| ex instanceof UnsupportedOperationException
				|| ex instanceof PathPolicy.Violation violation && violation.code() == PathPolicy.Code.UNSUPPORTED_FILESYSTEM
				? UNSUPPORTED_FILESYSTEM : IO,
				"Change persistence failed", ex);
	}
}
