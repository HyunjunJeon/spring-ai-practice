package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.lesson.LessonRecord.State;
import static org.assertj.core.api.Assertions.*;

/** goal-lesson:G9. Candidates and retired lessons count toward MAX_ENTRIES for good. */
class LessonQuotaPolicyTest {
	@TempDir Path root;

	private static LessonRecord revision(LessonRecord base, String id, int version, State state) {
		return new LessonRecord(id, version, state, base.scope(), base.session(), base.workspace(), base.content(),
				base.proof(), base.creator(), base.operator(), base.operation(), null, base.generation(), base.createdAt());
	}
	/** One accepted lesson plus MAX_ENTRIES - 1 entries that never inject: never-accepted candidates and retired lessons. */
	private static LessonRecord fillWithInertEntries(LessonFixture f) throws Exception {
		var accepted = f.accepted("parser kept");
		f.store.mutate(histories -> {
			for (int i = 1; i < LessonStore.MAX_ENTRIES; i++) {
				String id = UUID.randomUUID().toString();
				if (i % 2 == 0) histories.add(List.of(revision(accepted, id, 1, State.CANDIDATE)));
				else histories.add(List.of(revision(accepted, id, 1, State.CANDIDATE),
						revision(accepted, id, 2, State.ACCEPTED), revision(accepted, id, 3, State.RETIRED)));
			}
			return accepted;
		});
		return accepted;
	}

	@Test void aFullStoreOfInertEntriesRefusesEveryNewProposalAndRetiringNeverFreesAnEntry() throws Exception {
		var f = new LessonFixture(root);
		var accepted = fillWithInertEntries(f);
		assertThat(f.store.read()).hasSize(LessonStore.MAX_ENTRIES);
		// Only the one accepted lesson is injectable; the other 127 entries serve nothing.
		assertThat(f.service.lookup(f.actor, "parser")).extracting(LessonRecord::id).containsExactly(accepted.id());
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser another"), f.actor))
				.isInstanceOf(IOException.class).hasMessage("Lesson entry limit");
		var retired = f.service.retire(accepted.id(), 2, f.actor);
		assertThat(retired.state()).isEqualTo(State.RETIRED);
		assertThat(f.service.lookup(f.actor, "parser")).isEmpty();
		assertThat(f.store.read()).hasSize(LessonStore.MAX_ENTRIES);
		assertThat(f.service.list(f.actor)).extracting(LessonRecord::id).contains(accepted.id());
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser another"), f.actor))
				.isInstanceOf(IOException.class).hasMessage("Lesson entry limit");
	}

	@Disabled("SUSPECT goal-lesson:G9: LessonStore.MAX_ENTRIES (128) counts every candidate and RETIRED lesson forever and "
			+ "no operation removes an entry, so propose throws IOException 'Lesson entry limit' once the store is full "
			+ "(expiry, deletion or a clearing command is a retention-policy design call)")
	@Test void aStoreFullOfCandidatesAndRetiredLessonsStillTakesANewProposal() throws Exception {
		var f = new LessonFixture(root);
		var accepted = fillWithInertEntries(f);
		f.service.retire(accepted.id(), 2, f.actor);
		var candidate = f.service.propose("verified-goal", f.content("parser another"), f.actor);
		assertThat(f.service.lookup(candidate.id(), f.actor).state()).isEqualTo(State.CANDIDATE);
	}
}
