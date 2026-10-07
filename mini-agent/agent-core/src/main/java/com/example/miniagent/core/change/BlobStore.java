package com.example.miniagent.core.change;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;

/** Raw SHA256-addressed blobs. Only the owning workspace lease publishes them. */
public final class BlobStore {
	private final Path directory;
	private final int limit;

	BlobStore(Path directory, int limit) { this.directory = directory; this.limit = limit; }

	Checkpoint.BlobRef put(byte[] bytes) throws ChangeStore.Failure {
		if (bytes.length > this.limit) throw new ChangeStore.Failure(ChangeStore.Code.LIMIT, "Blob exceeds limit");
		Checkpoint.BlobRef ref = new Checkpoint.BlobRef(Fingerprinter.sha256(bytes), bytes.length);
		Path path = this.directory.resolve(ref.sha256());
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			if (!Arrays.equals(read(ref), bytes)) throw new ChangeStore.Failure(ChangeStore.Code.CORRUPT, "Blob differs");
			AtomicFiles.forceFile(path);
			AtomicFiles.forceDirectory(this.directory);
		}
		else AtomicFiles.write(path, bytes, this.limit);
		return ref;
	}

	public byte[] read(Checkpoint.BlobRef ref) throws ChangeStore.Failure {
		byte[] bytes = AtomicFiles.read(this.directory.resolve(ref.sha256()), this.limit);
		if (bytes.length != ref.size() || !Fingerprinter.sha256(bytes).equals(ref.sha256()))
			throw new ChangeStore.Failure(ChangeStore.Code.CORRUPT, "Blob integrity mismatch");
		return bytes;
	}
}
