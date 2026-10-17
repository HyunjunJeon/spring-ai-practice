package com.example.miniagent.core.lesson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.example.miniagent.core.lesson.LessonRecord.State;
import static org.assertj.core.api.Assertions.*;

/**
 * goal-lesson:G9. A proposal into a full store evicts the RETIRED lesson, or else the CANDIDATE, whose latest revision
 * is oldest. ACCEPTED lessons are never evicted.
 */
class LessonQuotaPolicyTest {
	@TempDir Path root;

	private static LessonRecord revision(LessonRecord base, String id, int version, State state, Instant at) {
		return new LessonRecord(id, version, state, base.scope(), base.session(), base.workspace(), base.content(),
				base.proof(), base.creator(), base.operator(), base.operation(), null, base.generation(), at);
	}
	/** One accepted lesson plus MAX_ENTRIES - 1 entries that never inject: never-accepted candidates and retired lessons. */
	private static LessonRecord fillWithInertEntries(LessonFixture f) throws Exception {
		var accepted = f.accepted("parser kept");
		f.store.mutate(histories -> {
			for (int i = 1; i < LessonStore.MAX_ENTRIES; i++) {
				String id = UUID.randomUUID().toString();
				var at = accepted.createdAt();
				if (i % 2 == 0) histories.add(List.of(revision(accepted, id, 1, State.CANDIDATE, at)));
				else histories.add(List.of(revision(accepted, id, 1, State.CANDIDATE, at),
						revision(accepted, id, 2, State.ACCEPTED, at), revision(accepted, id, 3, State.RETIRED, at)));
			}
			return accepted;
		});
		return accepted;
	}
	/** A new lesson whose latest revision reached {@code state} at {@code at}; its earlier revisions are from {@code since}. */
	private static List<LessonRecord> lesson(LessonRecord base, State state, Instant since, Instant at) {
		String id = UUID.randomUUID().toString();
		var states = switch (state) {
			case CANDIDATE -> List.of(State.CANDIDATE);
			case ACCEPTED -> List.of(State.CANDIDATE, State.ACCEPTED);
			case RETIRED -> List.of(State.CANDIDATE, State.ACCEPTED, State.RETIRED);
		};
		var history = new ArrayList<LessonRecord>();
		for (int v = 1; v <= states.size(); v++)
			history.add(revision(base, id, v, states.get(v - 1), v == states.size() ? at : since));
		return List.copyOf(history);
	}
	/** Appends the lessons, then ACCEPTED lessons from the epoch, older than anything else, until the store is full. */
	private static Set<String> fillWithAccepted(LessonFixture f, LessonRecord base, List<List<LessonRecord>> lessons)
			throws Exception {
		var accepted = new HashSet<String>();
		f.store.mutate(histories -> {
			histories.addAll(lessons);
			while (histories.size() < LessonStore.MAX_ENTRIES) {
				var filler = lesson(base, State.ACCEPTED, Instant.EPOCH, Instant.EPOCH);
				accepted.add(filler.getFirst().id());
				histories.add(filler);
			}
			return base;
		});
		return accepted;
	}
	private static Set<String> ids(LessonFixture f) throws IOException {
		return f.store.read().stream().map(history -> history.getFirst().id()).collect(Collectors.toSet());
	}
	/** Proposes into the full store and returns the one entry the proposal evicted to make room. */
	private static String evictedByProposal(LessonFixture f) throws Exception {
		var before = ids(f);
		var candidate = f.service.propose("verified-goal", f.content("parser another"), f.actor);
		var after = ids(f);
		assertThat(after).hasSize(LessonStore.MAX_ENTRIES).contains(candidate.id());
		var evicted = new HashSet<>(before);
		evicted.removeAll(after);
		assertThat(evicted).hasSize(1);
		return evicted.iterator().next();
	}

	@Test void retiringFreesNoEntryAndTheNextProposalEvictsTheLessonRetiredFirst() throws Exception {
		var f = new LessonFixture(root);
		var accepted = fillWithInertEntries(f);
		var retired = f.service.retire(accepted.id(), 2, f.actor);
		assertThat(retired.state()).isEqualTo(State.RETIRED);
		assertThat(f.service.lookup(f.actor, "parser")).isEmpty();
		assertThat(f.store.read()).hasSize(LessonStore.MAX_ENTRIES);
		assertThat(f.service.list(f.actor)).extracting(LessonRecord::id).contains(accepted.id());
		// Every inert entry was retired at the same instant, before this lesson; the tie goes to the earliest entry.
		var firstInertRetired = f.store.read().get(1).getFirst().id();
		assertThat(evictedByProposal(f)).isEqualTo(firstInertRetired);
		assertThat(ids(f)).contains(accepted.id());
	}

	@Test void aFullStoreEvictsRetiredLessonsBeforeCandidatesAndTheOldestFirst() throws Exception {
		var f = new LessonFixture(root);
		var base = f.accepted("parser kept");
		var t = Instant.EPOCH;
		var newerCandidate = lesson(base, State.CANDIDATE, t.plusSeconds(2), t.plusSeconds(2));
		var olderCandidate = lesson(base, State.CANDIDATE, t.plusSeconds(1), t.plusSeconds(1));
		// Created first but retired last, so the retirement time and not the creation time orders it.
		var laterRetired = lesson(base, State.RETIRED, t.plusSeconds(3), t.plusSeconds(40));
		var earlierRetired = lesson(base, State.RETIRED, t.plusSeconds(10), t.plusSeconds(20));
		fillWithAccepted(f, base, List.of(newerCandidate, olderCandidate, laterRetired, earlierRetired));
		assertThat(evictedByProposal(f)).isEqualTo(earlierRetired.getFirst().id());
		assertThat(evictedByProposal(f)).isEqualTo(laterRetired.getFirst().id());
		assertThat(evictedByProposal(f)).isEqualTo(olderCandidate.getFirst().id());
		assertThat(evictedByProposal(f)).isEqualTo(newerCandidate.getFirst().id());
	}

	@Test void anAcceptedLessonIsNeverEvictedEvenWhenItIsTheOldest() throws Exception {
		var f = new LessonFixture(root);
		var base = f.accepted("parser kept");
		var newest = lesson(base, State.CANDIDATE, Instant.now().plusSeconds(60), Instant.now().plusSeconds(60));
		var accepted = fillWithAccepted(f, base, List.of(newest));
		assertThat(evictedByProposal(f)).isEqualTo(newest.getFirst().id());
		assertThat(ids(f)).containsAll(accepted).contains(base.id());
	}

	@Test void aFullStoreOfAcceptedLessonsStillRefusesANewProposal() throws Exception {
		var f = new LessonFixture(root);
		var base = f.accepted("parser kept");
		fillWithAccepted(f, base, List.of());
		Path snapshot = f.home.resolve("lessons").resolve("lessons.json");
		byte[] before = Files.readAllBytes(snapshot);
		assertThatThrownBy(() -> f.service.propose("verified-goal", f.content("parser another"), f.actor))
				.isInstanceOf(IOException.class).hasMessage("Lesson entry limit");
		assertThat(Files.readAllBytes(snapshot)).isEqualTo(before);
	}

	@Test void anEvictedLessonIsGoneWithItsHistoryAndRollbackCannotRestoreIt() throws Exception {
		var f = new LessonFixture(root);
		var accepted = f.accepted("parser kept");
		var retired = f.service.retire(accepted.id(), 2, f.actor);
		fillWithAccepted(f, retired, List.of());
		assertThat(evictedByProposal(f)).isEqualTo(accepted.id());
		assertThatThrownBy(() -> f.service.history(accepted.id(), f.actor)).isInstanceOf(IOException.class)
				.hasMessage("Lesson not found");
		assertThatThrownBy(() -> f.service.rollback(accepted.id(), 3, 2, f.actor)).isInstanceOf(IOException.class)
				.hasMessage("Lesson not found");
	}

	@Test void aStoreFullOfCandidatesAndRetiredLessonsStillTakesANewProposal() throws Exception {
		var f = new LessonFixture(root);
		var accepted = fillWithInertEntries(f);
		f.service.retire(accepted.id(), 2, f.actor);
		var candidate = f.service.propose("verified-goal", f.content("parser another"), f.actor);
		assertThat(f.service.lookup(candidate.id(), f.actor).state()).isEqualTo(State.CANDIDATE);
	}
}
