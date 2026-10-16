package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.example.miniagent.core.model.ToolContextKeys;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import static org.assertj.core.api.Assertions.*;

/** goal-lesson:G7. Any file in the lessons folder besides the snapshot and its lock fails the store closed. */
class LessonStoreStrayFileTest {
	@TempDir Path root;

	private static ChatClientRequest request(LessonFixture f) {
		return new ChatClientRequest(new Prompt(List.of(new SystemMessage("base"), new UserMessage("parser issue")),
				ToolCallingChatOptions.builder().build()),
				Map.of(ToolContextKeys.SESSION_ID, f.actor.session(), ToolContextKeys.CWD, f.actor.workspace()));
	}

	@ParameterizedTest
	// A Finder file, the temp file AtomicFiles leaves after a crash mid-write, and an editor backup.
	@ValueSource(strings = { ".DS_Store", ".pending-4711.tmp", "lessons.json.bak" })
	void aStrayFileFailsReadsWritesAndTheAdvisorClosedWithoutTouchingStoredLessons(String stray) throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser lesson");
		Path lessons = f.home.resolve("lessons");
		byte[] snapshot = Files.readAllBytes(lessons.resolve("lessons.json"));
		Files.createFile(lessons.resolve(stray));
		assertThatThrownBy(f.store::read).isInstanceOf(IOException.class).hasMessage("Lesson storage recovery required");
		assertThatThrownBy(() -> f.service.list(f.actor)).isInstanceOf(IOException.class)
				.hasMessage("Lesson storage recovery required");
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser another"), f.actor))
				.isInstanceOf(IOException.class).hasMessage("Lesson storage recovery required");
		assertThatThrownBy(() -> new LessonAdvisor(f.service).prepare(request(f)))
				.isInstanceOf(UncheckedIOException.class).hasMessage("Lesson retrieval failed closed")
				.hasCauseInstanceOf(IOException.class).cause().hasMessage("Lesson storage recovery required");
		assertThat(lessons.resolve(stray)).exists();
		assertThat(Files.readAllBytes(lessons.resolve("lessons.json"))).isEqualTo(snapshot);
		// The refusal is recoverable by hand: removing the file restores every lesson.
		Files.delete(lessons.resolve(stray));
		assertThat(f.service.lookup(f.actor, "parser")).extracting(LessonRecord::id).containsExactly(accepted.id());
	}

	@Disabled("SUSPECT goal-lesson:G7: a stray .DS_Store in the lessons folder makes LessonStore.clean() throw "
			+ "'Lesson storage recovery required', so LessonAdvisor fails every agent turn with UncheckedIOException "
			+ "'Lesson retrieval failed closed' until the file is deleted by hand (fail closed versus skip is a design call)")
	@Test void aFinderMetadataFileDoesNotStopTheAdvisorFromInjectingAcceptedLessons() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser lesson");
		Files.createFile(f.home.resolve("lessons").resolve(".DS_Store"));
		var prepared = new LessonAdvisor(f.service).prepare(request(f));
		assertThat(prepared.context().get(LessonAdvisor.SELECTED))
				.isEqualTo(List.of(Map.of("id", accepted.id(), "version", 2, "scope", "SESSION")));
	}
}
