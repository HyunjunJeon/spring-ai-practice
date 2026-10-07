package com.example.miniagent.plugin.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 세션 하나 = JSONL 파일 하나. (pi 의 세션 파일과 같은 아이디어, 트리 구조는 생략)
 *
 * <p>{@link ChatMemoryRepository} 는 "저장만" 담당하는 SPI 다. JDBC/Redis/Mongo 등 공식 구현체도 같은 4개 메서드만
 * 구현한다. SPI 의 {@link #saveAll} 은 목록 전체로 파일을 다시 쓰지만, 이 플러그인의 {@link JsonlChatMemory} 는
 * {@link #append} 로 끝에 덧붙이기만 한다. 그래서 파일에는 세션의 전체 기록이 남는다.
 *
 * <p>파일에는 메시지 줄 사이에 요약 기록({@code "type":"summary"})도 들어간다. 요약은 "앞에서부터 {@code covers} 개의
 * 메시지를 접은 내용"이다. {@link #findByConversationId} 는 메시지만 돌려주고, 요약까지 필요하면 {@link #read} 를 쓴다.
 *
 * <p>쓰다가 끊긴 마지막 조각(줄바꿈으로 끝나지 않은 줄)은 읽을 때 건너뛰고, 다음 append 때 잘라낸다.
 */
public class JsonlChatMemoryRepository implements ChatMemoryRepository {

	/** 세션 파일을 읽은 결과. {@code summary} 는 가장 마지막 요약 기록이고, 없으면 null 이다. */
	public record History(List<Message> messages, @Nullable Summary summary) {
	}

	/** 앞에서부터 {@code covers} 개의 메시지를 접은 요약. */
	public record Summary(String text, int covers) {
	}

	private static final String EXTENSION = ".jsonl";

	private final Path directory;

	private final JsonMapper json = JsonMapper.builder().build();

	public JsonlChatMemoryRepository(Path directory) {
		this.directory = directory;
	}

	@Override
	public List<String> findConversationIds() {
		if (!Files.isDirectory(this.directory)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(this.directory)) {
			return files.map(f -> f.getFileName().toString())
				.filter(name -> name.endsWith(EXTENSION))
				.map(name -> name.substring(0, name.length() - EXTENSION.length()))
				.sorted()
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public List<Message> findByConversationId(String conversationId) {
		return read(conversationId).messages();
	}

	/** 메시지와 가장 마지막 요약 기록을 함께 읽는다. */
	public History read(String conversationId) {
		Path file = file(conversationId);
		if (!Files.exists(file)) {
			return new History(List.of(), null);
		}
		String text = withoutInterrupt(() -> Files.readString(file, StandardCharsets.UTF_8));
		List<Message> messages = new ArrayList<>();
		Summary summary = null;
		// 줄바꿈으로 끝나지 않은 마지막 조각은 쓰다 끊긴 기록이라 읽지 않는다.
		// 중간 줄이 깨졌다면 숨기지 않고 예외로 알린다.
		for (String line : text.substring(0, text.lastIndexOf('\n') + 1).lines().toList()) {
			if (line.isBlank()) {
				continue;
			}
			StoredMessage stored = this.json.readValue(line, StoredMessage.class);
			if (stored.isSummary()) {
				summary = new Summary((stored.text() != null) ? stored.text() : "",
						(stored.covers() != null) ? stored.covers() : 0);
			}
			else {
				messages.add(stored.toMessage());
			}
		}
		return new History(List.copyOf(messages), summary);
	}

	/** SPI 계약대로 목록 전체로 파일을 다시 쓴다(요약 기록은 남지 않는다). 이 플러그인의 메모리는 append 만 쓴다. */
	@Override
	public void saveAll(String conversationId, List<Message> messages) {
		List<String> lines = messages.stream().map(StoredMessage::from).map(this.json::writeValueAsString).toList();
		try {
			Files.createDirectories(this.directory);
			Files.write(file(conversationId), lines, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** 메시지를 파일 끝에 덧붙인다. 앞의 기록은 건드리지 않는다. */
	public void append(String conversationId, List<Message> messages) {
		appendRecords(conversationId, messages.stream().map(StoredMessage::from).toList());
	}

	/** 요약 기록을 덧붙인다. 읽을 때는 가장 마지막 요약 기록이 쓰인다. */
	public void appendSummary(String conversationId, Summary summary) {
		appendRecords(conversationId, List.of(StoredMessage.summary(summary)));
	}

	/** 파일 잠금으로 같은 세션을 연 다른 mini-agent 프로세스와 동시에 쓰지 않는다. */
	private synchronized void appendRecords(String conversationId, List<StoredMessage> records) {
		if (records.isEmpty()) {
			return;
		}
		StringBuilder lines = new StringBuilder();
		records.forEach(record -> lines.append(this.json.writeValueAsString(record)).append('\n'));
		ByteBuffer bytes = ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8));
		Path file = file(conversationId);
		withoutInterrupt(() -> {
			Files.createDirectories(this.directory);
			try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
					StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
				long position = completeLength(channel);
				channel.truncate(position);
				while (bytes.hasRemaining()) {
					position += channel.write(bytes, position);
				}
			}
			return null;
		});
	}

	@Override
	public void deleteByConversationId(String conversationId) {
		try {
			Files.deleteIfExists(file(conversationId));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private Path file(String conversationId) {
		if (!conversationId.matches("[A-Za-z0-9._-]+")) {
			throw new IllegalArgumentException("Invalid session id: " + conversationId);
		}
		return this.directory.resolve(conversationId + EXTENSION);
	}

	/**
	 * 파일 입출력 동안만 인터럽트 표시를 내려 두었다가 되돌린다. TUI 에서 Esc 로 취소하면 작업 스레드에 인터럽트가
	 * 걸리는데, FileChannel 은 인터럽트된 스레드에서 쓰면 채널을 닫고 실패한다. 그러면 취소된 턴의 마지막 기록이
	 * 빠지거나 오류가 난다. 짧은 기록은 끝까지 쓰고, 취소는 다음 대기 지점(모델 호출, bash)에서 처리되게 한다.
	 */
	private static <T> T withoutInterrupt(IoAction<T> action) {
		boolean interrupted = Thread.interrupted();
		try {
			return action.run();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@FunctionalInterface
	private interface IoAction<T> {

		T run() throws IOException;

	}

	/** 마지막 줄바꿈 바로 뒤의 위치. 파일이 줄바꿈으로 끝나면 파일 크기와 같다. */
	private static long completeLength(FileChannel channel) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		long end = channel.size();
		while (end > 0) {
			int length = (int) Math.min(buffer.capacity(), end);
			long start = end - length;
			buffer.clear().limit(length);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer, start + buffer.position()) < 0) {
					break;
				}
			}
			for (int i = length - 1; i >= 0; i--) {
				if (buffer.get(i) == '\n') {
					return start + i + 1;
				}
			}
			end = start;
		}
		return 0;
	}

	/**
	 * 파일에 쓰는 형태. Spring AI 의 Message 계층을 그대로 직렬화하지 않고 필요한 필드만 옮긴다.
	 * {@code covers} 는 요약 기록에만 있다. 문자열 메타데이터는 제공자별 추론/서명 재전송 상태다.
	 */
	record StoredMessage(String type, @Nullable String text, @Nullable List<AssistantMessage.ToolCall> toolCalls,
			@Nullable List<ToolResponseMessage.ToolResponse> toolResponses, @Nullable Integer covers,
			@Nullable Map<String, Object> metadata) {

		static final String SUMMARY = "summary";

		static StoredMessage from(Message message) {
			return switch (message) {
				case AssistantMessage assistant -> {
					Map<String, Object> metadata = new HashMap<>();
					assistant.getMetadata().forEach((key, value) -> {
						if (value instanceof String) {
							metadata.put(key, value);
						}
					});
					yield new StoredMessage("assistant", assistant.getText(),
							assistant.hasToolCalls() ? assistant.getToolCalls() : null, null, null,
							metadata.isEmpty() ? null : metadata);
				}
				case ToolResponseMessage tool -> new StoredMessage("tool", null, null, tool.getResponses(), null, null);
				case SystemMessage system -> new StoredMessage("system", system.getText(), null, null, null, null);
				default -> new StoredMessage("user", message.getText(), null, null, null, null);
			};
		}

		static StoredMessage summary(Summary summary) {
			return new StoredMessage(SUMMARY, summary.text(), null, null, summary.covers(), null);
		}

		boolean isSummary() {
			return SUMMARY.equals(this.type);
		}

		Message toMessage() {
			return switch (this.type) {
				case "assistant" -> AssistantMessage.builder()
					.content(this.text)
					.toolCalls((this.toolCalls != null) ? this.toolCalls : List.of())
					.properties(this.metadata != null ? this.metadata : Map.of())
					.build();
				case "tool" -> ToolResponseMessage.builder()
					.responses((this.toolResponses != null) ? this.toolResponses : List.of())
					.build();
				case "system" -> new SystemMessage(this.text);
				default -> new UserMessage(this.text);
			};
		}

	}

}
