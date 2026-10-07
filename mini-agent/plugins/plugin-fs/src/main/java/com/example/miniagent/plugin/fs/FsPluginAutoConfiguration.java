package com.example.miniagent.plugin.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.example.miniagent.core.AgentPlugin;
import com.example.miniagent.core.AgentProperties;
import com.example.miniagent.core.change.command.UndoCommand;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;

/**
 * 플러그인 = 자동 구성 클래스 하나 + {@code AutoConfiguration.imports} 파일 한 줄.
 *
 * <p>Spring AI 의 모든 starter(provider, vector store, MCP …)가 정확히 이 방식으로 붙는다.
 * jar 가 classpath 에 있으면 Boot 가 imports 파일을 읽어 이 클래스를 로드하고, 조건이 맞으면 빈이 생긴다.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "agent.plugins.fs.enabled", matchIfMissing = true)
public class FsPluginAutoConfiguration {

	@Bean
	com.example.miniagent.core.change.StagingToolInterceptor.Policy stagedFilePolicy(
			ToolCallbackProvider fileToolsProvider,
			@Value("${agent.plugins.fs.allowed-roots:}") List<String> allowedRoots) {
		var declarations = new java.util.HashMap<String,
				com.example.miniagent.core.change.StagingToolInterceptor.Declaration>();
		var roots = allowedRoots.stream().filter(s -> !s.isBlank()).map(s -> Path.of(s.strip())).toList();
		for (var callback : fileToolsProvider.getToolCallbacks()) {
			var tool = callback.getToolDefinition();
			var kind = java.util.Set.of("write_file", "edit_file").contains(tool.name())
					? com.example.miniagent.core.change.StagingToolInterceptor.Kind.FILE_WRITE
					: com.example.miniagent.core.change.StagingToolInterceptor.Kind.READ;
			declarations.put(tool.name(), new com.example.miniagent.core.change.StagingToolInterceptor.Declaration(
					com.example.miniagent.core.run.SideEffectClass.definitionDigest(tool), kind, roots));
		}
		return () -> java.util.Map.copyOf(declarations);
	}

	/**
	 * {@code @Tool} 메서드를 가진 객체를 ToolCallback 묶음으로 바꾼다. 코어는 이 타입만 안다.
	 *
	 * <p>작업 디렉터리 외에 사용자 스킬 폴더({@code ~/.mini-agent/skills})를 허용한다. load_skill 이 "상대 경로는 스킬 폴더
	 * 기준"이라고 안내하기 때문이다. {@code ~/.mini-agent} 전체를 열지 않는 이유는 {@code auth/} 에 로그인 토큰이 있어서다.
	 */
	@Bean
	ToolCallbackProvider fileToolsProvider(AgentProperties properties, ObjectProvider<FileCheckpoints> checkpoints,
			@Value("${agent.plugins.fs.allowed-roots:}") List<String> allowedRoots,
			@Value("${agent.plugins.fs.max-read-bytes:10MB}") DataSize maxReadBytes) {
		List<Path> roots = new ArrayList<>();
		roots.add(properties.getHome().resolve("skills"));
		allowedRoots.stream().filter(root -> !root.isBlank()).map(root -> Path.of(root.strip())).forEach(roots::add);
		return MethodToolCallbackProvider.builder()
			.toolObjects(new FileTools(roots, maxReadBytes.toBytes(), checkpoints.getIfAvailable()))
			.build();
	}

	/** write_file·edit_file 전에 원본을 보관한다. 앱이 끝나면 스프링이 close() 를 불러 사본을 지운다. */
	@Bean
	@ConditionalOnBooleanProperty(name = "agent.plugins.fs.checkpoints", matchIfMissing = true)
	FileCheckpoints fileCheckpoints() throws IOException {
		return new FileCheckpoints(Files.createTempDirectory("mini-agent-checkpoints-"));
	}

	@Bean
	UndoCommand.LegacyUndo legacyUndo(ObjectProvider<FileCheckpoints> checkpoints) {
		return ctx -> {
			FileCheckpoints store = checkpoints.getIfAvailable();
			if (store == null) {
				ctx.console().println("체크포인트가 꺼져 있습니다 (agent.plugins.fs.checkpoints=false).");
				return false;
			}
			var outcome = store.undoWithOutcome(ctx.session().id());
			if (outcome.restored().isEmpty() && outcome.conflicts().isEmpty()) {
				ctx.console().println("되돌릴 파일 변경이 없습니다.");
				return true;
			}
			outcome.restored().forEach(change -> ctx.console().println(
					((change.backup() == null) ? "삭제: " : "복원: ") + change.file()));
			outcome.conflicts().forEach(conflict -> ctx.console().error("CONFLICT " + conflict.code()
					+ " " + conflict.change().file() + ": " + conflict.detail()));
			if (!outcome.restored().isEmpty())
				ctx.console().info("파일만 되돌렸습니다. 대화 기록은 그대로이니 필요하면 모델에게 알려 주세요.");
			return outcome.conflicts().isEmpty();
		};
	}

	@Bean
	AgentPlugin fsPlugin() {
		return AgentPlugin.of("fs", "read_file, write_file, edit_file, list_dir, /undo");
	}

}
