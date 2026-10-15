package com.example.miniagent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.example.miniagent.core.lesson.LessonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class StatePathsTest {
	@TempDir Path root;

	@Test void configuredAncestorAliasResolvesWithoutCreatingStorage() throws Exception {
		Path physical = Files.createDirectory(root.resolve("physical")).toRealPath();
		Path alias = Files.createSymbolicLink(root.resolve("alias"), physical);
		Path actual = StatePaths.home(alias.resolve("home"));
		assertThat(actual).isEqualTo(physical.resolve("home"));
		assertThat(actual).doesNotExist();
		assertThat(new LessonStore(actual).read()).isEmpty();
		assertThat(actual).doesNotExist();
	}

	@Test void privateHomeLeafAliasIsNotFollowedOrAccepted() throws Exception {
		Path physicalRoot = root.toRealPath();
		Path outside = Files.createDirectory(physicalRoot.resolve("outside"));
		Path alias = Files.createSymbolicLink(physicalRoot.resolve("home"), outside);
		Path actual = StatePaths.home(alias);
		assertThat(actual).isEqualTo(alias);
		assertThatThrownBy(() -> new LessonStore(actual).read()).isInstanceOf(IOException.class);
	}
}
