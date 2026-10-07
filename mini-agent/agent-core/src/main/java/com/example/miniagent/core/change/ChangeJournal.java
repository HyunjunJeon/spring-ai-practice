package com.example.miniagent.core.change;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32C;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static com.example.miniagent.core.change.ChangeStore.Code.*;

/** Version/sequence/CRC32C framed JSONL. No damaged tail is ever truncated. */
public final class ChangeJournal {
	public enum State { CLEAN, TRUNCATED, CORRUPT, FUTURE_SCHEMA }
	public record Scan(State state, List<ChangeStore.Snapshot> records, long completeBytes) {
		public Scan { records = List.copyOf(records); }
	}
	static final JsonMapper JSON = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
			.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
	static final int SCHEMA = 1;
	private final Path path;
	private final int limit;
	private final int recordLimit;

	ChangeJournal(Path path, int limit, int recordLimit) {
		this.path = path; this.limit = limit; this.recordLimit = recordLimit;
	}

	public Scan scan() throws ChangeStore.Failure {
		byte[] bytes = AtomicFiles.read(this.path, this.limit);
		List<ChangeStore.Snapshot> records = new ArrayList<>();
		int start = 0;
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] != '\n') continue;
			if (i - start > this.recordLimit + 64) return new Scan(State.CORRUPT, records, start);
			try {
				String row = utf8(bytes, start, i - start);
				String[] parts = row.split("\t", 4);
				if (future(parts[0])) return new Scan(State.FUTURE_SCHEMA, records, start);
				if (parts.length != 4 || !parts[0].equals("1") || !parts[1].matches("[1-9][0-9]*")
						|| !parts[2].matches("[0-9a-f]{8}")
						|| Long.parseLong(parts[1]) != records.size() + 1L
						|| !crc(parts[0] + "\t" + parts[1] + "\t" + parts[3]).equals(parts[2]))
					return new Scan(State.CORRUPT, records, start);
				ChangeStore.Snapshot snapshot = JSON.readValue(parts[3], ChangeStore.Snapshot.class);
				if (snapshot.sequence() != records.size() + 1L) return new Scan(State.CORRUPT, records, start);
				records.add(snapshot);
				start = i + 1;
			}
			catch (RuntimeException | CharacterCodingException ex) { return new Scan(State.CORRUPT, records, start); }
		}
		if (start != bytes.length) {
			// Only decode a bounded schema prefix: unknown payload bytes stay opaque.
			int end = start;
			while (end < bytes.length && end - start < 20 && bytes[end] >= '0' && bytes[end] <= '9') end++;
			if (end < bytes.length && bytes[end] == '\t'
					&& future(new String(bytes, start, end - start, UTF_8)))
				return new Scan(State.FUTURE_SCHEMA, records, start);
			boolean currentPrefix = bytes[start] == '1'
					&& (start + 1 == bytes.length || bytes[start + 1] == '\t');
			return new Scan(currentPrefix ? State.TRUNCATED : State.CORRUPT, records, start);
		}
		return new Scan(State.CLEAN, records, start);
	}

	void append(ChangeStore.Snapshot snapshot) throws ChangeStore.Failure {
		Scan scan = scan();
		if (scan.state() != State.CLEAN) throw blocked(scan.state());
		if (snapshot.sequence() != scan.records().size() + 1L)
			throw new ChangeStore.Failure(CORRUPT, "Journal sequence changed");
		String payload = JSON.writeValueAsString(snapshot);
		if (payload.getBytes(UTF_8).length > this.recordLimit)
			throw new ChangeStore.Failure(LIMIT, "Journal record exceeds limit");
		String prefix = SCHEMA + "\t" + snapshot.sequence();
		byte[] frame = (prefix + "\t" + crc(prefix + "\t" + payload) + "\t" + payload + "\n").getBytes(UTF_8);
		if (scan.completeBytes() + frame.length > this.limit)
			throw new ChangeStore.Failure(LIMIT, "Journal exceeds limit");
		try (FileChannel channel = FileChannel.open(this.path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.position(scan.completeBytes());
			AtomicFiles.writeFully(channel, frame);
			try { channel.force(true); }
			catch (IOException | UnsupportedOperationException ex) {
				throw new ChangeStore.Failure(UNSUPPORTED_FILESYSTEM, "Journal force failed", ex);
			}
		}
		catch (IOException | UnsupportedOperationException ex) { throw AtomicFiles.failure(ex); }
	}

	static ChangeStore.Failure blocked(State state) {
		return new ChangeStore.Failure(switch (state) {
			case TRUNCATED -> TRUNCATED_JOURNAL;
			case CORRUPT -> CORRUPT;
			case FUTURE_SCHEMA -> FUTURE_SCHEMA;
			case CLEAN -> RECOVERY_REQUIRED;
		}, "Journal requires explicit recovery: " + state);
	}

	private static boolean future(String version) {
		if (!version.matches("[1-9][0-9]*")) return false;
		return version.length() > 1 || !version.equals("1");
	}
	private static String utf8(byte[] bytes, int start, int length) throws CharacterCodingException {
		return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, start, length)).toString();
	}
	private static String crc(String input) {
		CRC32C crc = new CRC32C();
		crc.update(input.getBytes(UTF_8));
		return String.format(Locale.ROOT, "%08x", crc.getValue());
	}
}
