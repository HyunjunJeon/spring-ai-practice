package com.example.miniagent.plugin.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.example.miniagent.plugin.memory.JsonlChatMemoryRepository.History;
import com.example.miniagent.plugin.memory.JsonlChatMemoryRepository.Summary;
import com.example.miniagent.core.model.ProviderPrivateFieldFilter;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 전체 기록과 모델 문맥을 나눈 {@link ChatMemory}.
 *
 * <ul>
 *   <li>{@link #add}: 세션 파일 끝에 덧붙이기만 한다. 오래된 대화도 파일에서 지워지지 않는다.</li>
 *   <li>{@link #get}: 메시지 수와 UTF-16 내용 길이의 두 한도 안에서 연속된 최근 USER 그룹과 선택적 요약을
 *       돌려준다. 도구 호출과 결과는 통째로 보존하거나 생략한다.</li>
 *   <li>요약(compaction): 최근 부분 밖으로 밀려나는 메시지가 생기면, 요약기가 그 앞부분을 요약 하나로 접어 파일에 남기고
 *       문맥 맨 앞에 붙인다. 한 번 접을 때 한도의 절반까지 줄여 두므로, 매 턴 요약하지 않고 최근 부분이 다시 한도를
 *       채울 때만 요약한다. 요약기가 없으면 새 요약을 만들지 않는다.</li>
 * </ul>
 *
 * <p>Spring AI 의 {@code MessageWindowChatMemory} 는 창(window)만 저장소에 다시 써서 창 밖의 기록이 사라진다.
 * {@code MessageChatMemoryAdvisor} 는 get/add 를 부르고, 플러그인 내부의 예산 어드바이저가 실제 요청도 검사한다.
 */
public class JsonlChatMemory implements ChatMemory {

	/** 요약을 모델에게 보낼 때 앞에 붙이는 머리말. 요약은 사용자 메시지 하나로 문맥 맨 앞에 들어간다. */
	static final String SUMMARY_HEADER = "Summary of the earlier conversation (older messages were folded into it):";

	private final JsonlChatMemoryRepository repository;

	private final int maxMessages;

	private final long maxChars;

	private final @Nullable ConversationSummarizer summarizer;

	private final Consumer<String> notice;

	private final int maxSummaryChars;

	/**
	 * @param summarizer 요약기. null 이면 새 요약을 만들지 않는다.
	 * @param notice 요약했거나 실패했을 때 사용자에게 알릴 한 줄
	 */
	public JsonlChatMemory(JsonlChatMemoryRepository repository, int maxMessages, long maxChars,
			@Nullable ConversationSummarizer summarizer, Consumer<String> notice) {
		this(repository, maxMessages, maxChars, summarizer, notice, 8000);
	}

	public JsonlChatMemory(JsonlChatMemoryRepository repository, int maxMessages, long maxChars,
			@Nullable ConversationSummarizer summarizer, Consumer<String> notice, int maxSummaryChars) {
		MemoryBudget.positive("max-messages", maxMessages);
		MemoryBudget.positive("max-context-chars", maxChars);
		MemoryBudget.positive("max-summary-chars", maxSummaryChars);
		this.repository = repository;
		this.maxMessages = maxMessages;
		this.maxChars = maxChars;
		this.summarizer = summarizer;
		this.notice = notice;
		this.maxSummaryChars = maxSummaryChars;
	}

	@Override
	public void add(String conversationId, List<Message> messages) {
		this.repository.append(conversationId, messages);
	}

	@Override
	public List<Message> get(String conversationId) {
		History history = this.repository.read(conversationId);
		List<Message> all = ProviderPrivateFieldFilter.forCurrentTurn(history.messages());
		int start = windowStart(all, this.maxMessages, this.maxChars);
		Summary summary = history.summary();
		if (summary != null) {
			int covers = summary.covers();
			if (covers < 0 || covers > all.size()
					|| (covers < all.size() && all.get(covers).getMessageType() != MessageType.USER)) {
				this.notice.accept("대화 요약의 잘못된 covers 경계를 무시합니다: " + covers);
				summary = null;
			}
			else {
				String bounded = MemoryBudget.prefix(summary.text().strip(), this.maxSummaryChars);
				summary = bounded.isBlank() ? null : new Summary(bounded, covers);
			}
		}
		if (this.summarizer != null && start > 0 && (summary == null || summary.covers() < start)) {
			summary = compact(conversationId, all, start, summary);
		}
		int covered = summary == null ? 0 : summary.covers();
		Message summaryMessage = summary == null ? null : UserMessage.builder()
			.text(SUMMARY_HEADER + "\n" + summary.text()).metadata(java.util.Map.of("memory.summary", true)).build();
		List<Message> raw = all.subList(covered, all.size());
		if (!raw.isEmpty() && !MemoryBudget.validTools(raw.subList(groupStart(raw, raw.size()), raw.size()))) {
			this.notice.accept("잘못된 도구 ID가 있는 최근 대화 그룹을 생략합니다");
		}
		return select(raw, summaryMessage, this.maxMessages, this.maxChars);
	}

	@Override
	public void clear(String conversationId) {
		this.repository.clearHistory(conversationId);
	}

	/** 한도의 절반까지 줄어들도록 앞부분을 요약 하나로 접는다. 실패하면 이전 요약(없으면 null)을 그대로 쓴다. */
	private @Nullable Summary compact(String conversationId, List<Message> all, int start,
			@Nullable Summary previous) {
		int upTo = Math.min(groupStart(all, all.size()), Math.max(start,
				windowStart(all, Math.max(1, this.maxMessages / 2), Math.max(1, this.maxChars / 2))));
		int from = (previous != null) ? Math.min(previous.covers(), upTo) : 0;
		if (upTo <= from) {
			return previous;
		}
		try {
			if (Thread.currentThread().isInterrupted()) {
				throw new java.util.concurrent.CancellationException("Conversation compaction interrupted");
			}
			String text = this.summarizer.summarize((previous != null) ? previous.text() : null, all.subList(from, upTo));
			if (Thread.currentThread().isInterrupted()) {
				throw new java.util.concurrent.CancellationException("Conversation compaction interrupted");
			}
			text = MemoryBudget.prefix(text.strip(), this.maxSummaryChars);
			if (text.isBlank()) {
				throw new IllegalStateException("요약 결과가 비어 있습니다");
			}
			Summary summary = new Summary(text, upTo);
			this.repository.appendSummary(conversationId, summary);
			this.notice.accept("이전 대화 %d개 메시지를 요약해 문맥 앞에 붙였습니다 (요약 %,d자)".formatted(upTo - from,
					text.length()));
			return summary;
		}
		catch (RuntimeException ex) {
			for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
				if (cause instanceof InterruptedException || cause instanceof java.util.concurrent.CancellationException
						|| Thread.currentThread().isInterrupted()) {
					throw ex;
				}
			}
			// 요약이 실패해도 턴은 계속한다. 이번에는 이전 요약(있으면)과 최근 부분만 보낸다.
			this.notice.accept("대화 요약에 실패해 최근 부분만 보냅니다: " + ex.getMessage());
			return previous;
		}
	}

	static List<Message> window(List<Message> all, int maxMessages, long maxChars) {
		return select(all, null, maxMessages, maxChars);
	}

	/** 뒤에서부터 한도 안까지 넓힌 뒤, 턴 중간에서 시작하지 않도록 USER 메시지에 맞춘 시작 위치. */
	static int windowStart(List<Message> all, int maxMessages, long maxChars) {
		int start = all.size();
		long chars = 0;
		while (start > 0) {
			int nextStart = groupStart(all, start);
			List<Message> group = all.subList(nextStart, start);
			long next = chars + MemoryBudget.length(group);
			if (group.getFirst().getMessageType() != MessageType.USER || !MemoryBudget.validTools(group)
					|| next > maxChars || all.size() - nextStart > maxMessages) {
				break;
			}
			chars = next;
			start = nextStart;
		}
		return start;
	}

	static int groupStart(List<Message> all, int end) {
		int start = end - 1;
		while (start > 0 && all.get(start).getMessageType() != MessageType.USER) {
			start--;
		}
		return Math.max(0, start);
	}

	/** Newest raw group first, then optional summary, then a contiguous older suffix. */
	static List<Message> select(List<Message> all, @Nullable Message summary, int maxMessages, long maxChars) {
		int start = all.size();
		long chars = 0;
		int count = 0;
		if (!all.isEmpty()) {
			int newest = groupStart(all, all.size());
			List<Message> group = all.subList(newest, all.size());
			if (group.getFirst().getMessageType() == MessageType.USER && MemoryBudget.validTools(group)
					&& group.size() <= maxMessages && MemoryBudget.length(group) <= maxChars) {
				start = newest;
				count = group.size();
				chars = MemoryBudget.length(group);
			}
		}
		boolean includeSummary = summary != null && count + 1 <= maxMessages
				&& chars + MemoryBudget.length(summary) <= maxChars;
		if (includeSummary) {
			count++;
			chars += MemoryBudget.length(summary);
		}
		// A nonfitting latest group blocks all older raw groups.
		while (start > 0 && start < all.size()) {
			int next = groupStart(all, start);
			List<Message> group = all.subList(next, start);
			long size = MemoryBudget.length(group);
			if (group.getFirst().getMessageType() != MessageType.USER || !MemoryBudget.validTools(group)
					|| count + group.size() > maxMessages || chars + size > maxChars) {
				break;
			}
			start = next;
			count += group.size();
			chars += size;
		}
		List<Message> result = new ArrayList<>();
		if (includeSummary) {
			result.add(summary);
		}
		result.addAll(all.subList(start, all.size()));
		return List.copyOf(result);
	}

}
