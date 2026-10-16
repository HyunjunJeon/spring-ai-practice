package com.example.miniagent.plugin.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

/** read_file 의 페이지 경계: 끝을 넘긴 offset 과 int 한계에 가까운 offset/limit. */
class FileToolsReadEdgeTest {

	@TempDir
	Path work;

	private final FileTools tools = new FileTools(List.of(), 1024 * 1024, null);

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	@Test
	void offsetPastTheLastLineIsReportedAsOutOfRangeNotAsAnEmptyFile() throws Exception {
		Files.writeString(this.work.resolve("two.txt"), "l1\nl2\n");

		// 끝을 넘겨 읽은 모델이 "빈 파일"이라고 믿고 파일을 덮어쓰지 않도록, 줄 수를 알려 주는 범위 오류여야 한다.
		assertThat(this.tools.readFile("two.txt", 5, null, context()))
			.isNotEqualTo("(empty file)")
			.contains("out of range")
			.contains("Offset 5")
			.contains("2 lines");
	}

	@Test
	void offsetOneBeyondTheLastLineIsStillOutOfRange() throws Exception {
		Files.writeString(this.work.resolve("two.txt"), "l1\nl2\n");

		assertThat(this.tools.readFile("two.txt", 3, 10, context())).contains("out of range").contains("2 lines");
		assertThat(this.tools.readFile("two.txt", 2, 10, context())).isEqualTo("     2  l2");
	}

	@Test
	void trulyEmptyFileKeepsTheEmptyFileMessage() throws Exception {
		Files.writeString(this.work.resolve("empty.txt"), "");

		assertThat(this.tools.readFile("empty.txt", null, null, context())).isEqualTo("(empty file)");
	}

	@Test
	void offsetNearIntMaxIsOutOfRangeWithoutANegativeFooter() throws Exception {
		Files.writeString(this.work.resolve("three.txt"), "l1\nl2\nl3\n");

		// from - 1 + 기본 limit(500) 이 int 를 넘어 음수가 되면 "남은 줄 수" 안내가 엉뚱하게 붙던 경계다.
		assertThat(this.tools.readFile("three.txt", Integer.MAX_VALUE, null, context()))
			.contains("Offset 2147483647")
			.contains("out of range")
			.contains("3 lines")
			.doesNotContain("more lines");
	}

	@Test
	void hugeLimitReturnsTheWholeTailWithoutAMoreLinesFooter() throws Exception {
		Files.writeString(this.work.resolve("five.txt"), "l1\nl2\nl3\nl4\nl5\n");

		// from - 1 + count 가 int 를 넘으면 음수 "남은 줄 수"와 음수 offset 안내가 붙던 경계다.
		assertThat(this.tools.readFile("five.txt", 2, Integer.MAX_VALUE, context()))
			.isEqualTo("     2  l2\n     3  l3\n     4  l4\n     5  l5");
	}

	@Test
	void hugeLimitFromTheFirstLineReturnsTheWholeFile() throws Exception {
		Files.writeString(this.work.resolve("five.txt"), "l1\nl2\nl3\nl4\nl5\n");

		assertThat(this.tools.readFile("five.txt", 1, Integer.MAX_VALUE, context()))
			.isEqualTo("     1  l1\n     2  l2\n     3  l3\n     4  l4\n     5  l5");
	}

	@Test
	void footerStillPointsAtTheNextLineWhenThePageIsShort() throws Exception {
		Files.writeString(this.work.resolve("five.txt"), "l1\nl2\nl3\nl4\nl5\n");

		assertThat(this.tools.readFile("five.txt", 2, 2, context()))
			.isEqualTo("     2  l2\n     3  l3\n... (2 more lines, call again with offset=4)");
		assertThat(this.tools.readFile("five.txt", 4, 2, context())).isEqualTo("     4  l4\n     5  l5");
	}

}
