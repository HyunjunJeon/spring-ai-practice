package com.example.miniagent.plugin.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자동 구성이 만든 파일 도구가 사용자 스킬 폴더({@code <home>/skills})를 어떻게 여는가: 읽기는 허용하고,
 * 로그인 토큰이 있는 {@code <home>/auth} 등 나머지는 열지 않는다.
 */
class FileToolsSkillsRootTest {

	@TempDir
	Path work;

	@TempDir
	Path home;

	private ToolCallback[] callbacks;

	@BeforeEach
	void wire() {
		var properties = new AgentProperties();
		properties.setHome(this.home);
		this.callbacks = new FsPluginAutoConfiguration().fileToolsProvider(properties,
				new DefaultListableBeanFactory().getBeanProvider(FileCheckpoints.class), List.of(),
				DataSize.ofMegabytes(1)).getToolCallbacks();
	}

	private ToolContext context() {
		return new ToolContext(Map.of(ToolContextKeys.CWD, this.work.toString()));
	}

	private String call(String tool, String arguments) throws Exception {
		ToolCallback callback = Arrays.stream(this.callbacks)
			.filter(candidate -> candidate.getToolDefinition().name().equals(tool)).findFirst().orElseThrow();
		return callback.call(arguments, context());
	}

	private String read(Path file) throws Exception {
		return call("read_file", "{\"path\":\"%s\"}".formatted(file));
	}

	private String write(Path file, String content) throws Exception {
		return call("write_file", "{\"path\":\"%s\",\"content\":\"%s\"}".formatted(file, content));
	}

	private String edit(Path file, String oldText, String newText) throws Exception {
		return call("edit_file", "{\"path\":\"%s\",\"oldText\":\"%s\",\"newText\":\"%s\"}".formatted(file,
				oldText, newText));
	}

	private Path skill() throws Exception {
		Path file = this.home.resolve("skills/demo/SKILL.md");
		Files.createDirectories(file.getParent());
		return Files.writeString(file, "# demo\nuse me\n");
	}

	@Test
	void skillSupportFileUnderTheHomeSkillsRootCanBeRead() throws Exception {
		Path skill = skill();

		assertThat(read(skill)).isEqualTo("     1  # demo\n     2  use me");
	}

	@Test
	void otherPartsOfTheHomeStayClosedForReadingAndWriting() throws Exception {
		Path token = Files.createDirectories(this.home.resolve("auth")).resolve("token.json");
		Files.writeString(token, "{\"refresh\":\"secret\"}");
		Path lookalike = Files.createDirectories(this.home.resolve("skills-extra")).resolve("note.md");
		Files.writeString(lookalike, "not a skill");

		for (Path closed : List.of(token, lookalike)) {
			assertThatThrownBy(() -> read(closed)).hasMessageContaining("outside the allowed directories");
			assertThatThrownBy(() -> write(closed, "x")).hasMessageContaining("outside the allowed directories");
			assertThatThrownBy(() -> edit(closed, "e", "E")).hasMessageContaining("outside the allowed directories");
		}
		assertThat(Files.readString(token)).isEqualTo("{\"refresh\":\"secret\"}");
		assertThat(Files.readString(lookalike)).isEqualTo("not a skill");
		assertThatThrownBy(() -> write(this.home.resolve("auth/new.json"), "x"))
			.hasMessageContaining("outside the allowed directories");
		assertThat(this.home.resolve("auth/new.json")).doesNotExist();
	}

	@Test
	@Disabled("SUSPECT plugins-tools:G14: write_file and edit_file succeed on a file under <home>/skills in a legacy turn, "
			+ "because the skills root added for load_skill reads is also a mutation root; design call whether it should be read-only")
	void skillsRootIsRefusedForWriteAndEdit() throws Exception {
		Path skill = skill();

		assertThatThrownBy(() -> write(skill, "# hijacked")).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> edit(skill, "use me", "run rm -rf")).isInstanceOf(RuntimeException.class);

		assertThat(Files.readString(skill)).isEqualTo("# demo\nuse me\n");
	}

}
