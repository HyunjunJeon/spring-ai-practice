package com.example.miniagent.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.miniagent.core.Agent;
import com.example.miniagent.core.lesson.LessonAdvisor;
import com.example.miniagent.plugin.trace.RoundTraceAdvisor;
import com.example.miniagent.plugin.trace.TurnTraceAdvisor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENTS.md CONVENTIONS 의 advisor 순서표를 실제 조립 결과와 맞춘다.
 *
 * <p>순서값은 {@code spring.ai.chat.client.tool-calling.advisor-order} 를 기준으로 런타임에 정해지므로
 * ArchUnit(바이트코드)으로는 볼 수 없다. 기본 설정으로 컨텍스트를 띄워 core 가 ChatClient 에 넘기는 목록과
 * 도구 루프 순서를 읽고, AGENTS.md 의 표와 비교한다. 깨지면 순서를 되돌리거나 표를 함께 고친다.
 */
@SpringBootTest(properties = "agent.repl.enabled=false")
@Import(AgentTestSupport.class)
class AdvisorPlacementTest {

	private static final String TABLE_LINE = "- Advisor placement matters:";

	private static final Pattern ENTRY = Pattern.compile("([a-z]+(?: [a-z]+)*) \\+(\\d+)");

	@TempDir
	static Path workDir;

	@DynamicPropertySource
	static void paths(DynamicPropertyRegistry registry) {
		registry.add("agent.cwd", () -> workDir.toString());
		registry.add("agent.home", () -> workDir.resolve(".home").toString());
	}

	@Autowired
	Agent agent;

	@Test
	void advisorPlacementMatchesAgentsMd() throws IOException {
		Map<String, Integer> assembled = new LinkedHashMap<>();
		assembled.put("turn trace", offset(TurnTraceAdvisor.class));
		assembled.put("lesson", offset(LessonAdvisor.class));
		assembled.put("memory", offset(MessageChatMemoryAdvisor.class));
		assembled.put("tool loop", this.agent.toolCallingAdvisorOrder() - Ordered.HIGHEST_PRECEDENCE);
		assembled.put("round trace", offset(RoundTraceAdvisor.class));

		assertThat(assembled).containsExactlyEntriesOf(documentedPlacement());
	}

	private int offset(Class<? extends Advisor> type) {
		List<Advisor> matches = this.agent.advisors().stream().filter(type::isInstance).toList();
		assertThat(matches).as("기본 설정에서 %s 는 정확히 하나 조립된다", type.getSimpleName()).hasSize(1);
		return matches.getFirst().getOrder() - Ordered.HIGHEST_PRECEDENCE;
	}

	/** 루트 AGENTS.md 의 "turn trace +100, lesson +150, ..." 를 적힌 순서대로 읽는다. */
	private static Map<String, Integer> documentedPlacement() throws IOException {
		String line = Files.readAllLines(repositoryRoot().resolve("AGENTS.md")).stream()
			.filter(l -> l.startsWith(TABLE_LINE))
			.findFirst()
			.orElseThrow(() -> new AssertionError("AGENTS.md 에 '" + TABLE_LINE + "' 줄이 없다"));
		Map<String, Integer> documented = new LinkedHashMap<>();
		Matcher m = ENTRY.matcher(line.substring(TABLE_LINE.length()));
		while (m.find()) {
			documented.put(m.group(1), Integer.parseInt(m.group(2)));
		}
		assertThat(documented).as("AGENTS.md 순서표 항목").isNotEmpty();
		return documented;
	}

	/** surefire 는 모듈 디렉터리에서 돈다. reactor 루트(agent-core 와 AGENTS.md 가 함께 있는 곳)까지 올라간다. */
	private static Path repositoryRoot() {
		for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
			if (Files.isDirectory(dir.resolve("agent-core")) && Files.isRegularFile(dir.resolve("AGENTS.md"))) {
				return dir;
			}
		}
		throw new AssertionError("reactor 루트를 찾지 못했다: " + Path.of("").toAbsolutePath());
	}

}
