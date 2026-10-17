package com.example.miniagent.core.run;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.example.miniagent.core.model.ModelBinding;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.TurnContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Child accounting judges lineage by spec.json: a stranger's damage is skipped, a child's or its own is not. */
class ChildRunScanTest {
	private static final Instant AT = Instant.EPOCH;
	private static final String DIGEST = "0".repeat(64);
	private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);

	enum ChildDamage {
		CORRUPT_JOURNAL(RunStoreException.Code.CORRUPT),
		FUTURE_JOURNAL_TAIL(RunStoreException.Code.FUTURE_SCHEMA),
		UNREADABLE_SPEC(RunStoreException.Code.CORRUPT);

		final RunStoreException.Code refusal;

		ChildDamage(RunStoreException.Code refusal) { this.refusal = refusal; }
	}

	@TempDir Path home;

	@Test void damagedStrangersAndOsMetadataNeitherFailNorHideTheChild() throws Exception {
		var runs = new FileRunStore(this.home);
		var parent = spec("parent", null);
		try (var stranger = runs.create(spec("corrupt-stranger", null))) {
			start(stranger, "stranger-attempt");
		}
		runs.create(spec("future-stranger", null)).close();
		replace(run("corrupt-stranger").resolve("events.log"), "RUNNING", "FAILED");
		Files.writeString(run("future-stranger").resolve("spec.json"), "{\"schemaVersion\":2}");
		Files.writeString(this.home.resolve("runs").resolve(".DS_Store"), "finder metadata");
		Files.writeString(this.home.resolve("runs").resolve("._parent"), "appledouble metadata");
		try (var writer = runs.create(parent)) {
			runs.create(spec("child", parent)).close();
			var context = start(writer, "parent-attempt");
			assertThat(runs.children(parent.runId())).containsExactly(new RunId("child"));
			context.reconcileChildUsage(runs);
		}
	}

	@ParameterizedTest
	@EnumSource(ChildDamage.class)
	void damageInTheParentsChildStillFailsChildAccountingClosed(ChildDamage damage) throws Exception {
		var runs = new FileRunStore(this.home);
		var parent = spec("parent", null);
		try (var writer = runs.create(parent); var child = runs.create(spec("child", parent))) {
			var context = start(writer, "parent-attempt");
			start(child, "child-attempt");
			Path directory = run("child");
			switch (damage) {
				case CORRUPT_JOURNAL -> replace(directory.resolve("events.log"), "RUNNING", "FAILED");
				case FUTURE_JOURNAL_TAIL -> Files.writeString(directory.resolve("events.log"), "2\tpartial",
						StandardOpenOption.APPEND);
				case UNREADABLE_SPEC -> Files.writeString(directory.resolve("spec.json"), "{");
			}
			assertThatThrownBy(() -> context.reconcileChildUsage(runs)).isInstanceOfSatisfying(RunStoreException.class,
					refused -> assertThat(refused.code()).isEqualTo(damage.refusal));
		}
	}

	@Test void damageInTheParentsOwnJournalFailsChildAccountingClosed() throws Exception {
		var runs = new FileRunStore(this.home);
		try (var writer = runs.create(spec("parent", null))) {
			var context = start(writer, "parent-attempt");
			replace(run("parent").resolve("events.log"), "RUNNING", "FAILED");
			assertThatThrownBy(() -> context.reconcileChildUsage(runs)).isInstanceOf(RunStoreException.class)
					.hasMessage("Event CRC/sequence mismatch");
		}
	}

	@Test void anUnknownFileBesideTheRunsStillFailsChildAccountingClosed() throws Exception {
		var runs = new FileRunStore(this.home);
		Files.writeString(this.home.resolve("runs").resolve("notes.txt"), "not a run");
		try (var writer = runs.create(spec("parent", null))) {
			var context = start(writer, "parent-attempt");
			assertThatThrownBy(() -> context.reconcileChildUsage(runs)).isInstanceOf(RunStoreException.class)
					.hasMessageStartingWith("Not a real run directory");
		}
	}

	private Path run(String id) {
		return this.home.resolve("runs").resolve(id);
	}

	private static void replace(Path file, String from, String to) throws Exception {
		Files.writeString(file, Files.readString(file).replaceFirst(from, to));
	}

	private RunSpec spec(String id, RunSpec parent) {
		return new RunSpec(1, new RunId(id), "conversation-" + id,
				parent == null ? "original" : parent.conversationId(),
				parent == null ? null : parent.runId(), parent == null ? null : "worker",
				new ModelRef("fake", "test"), "fake", this.home.toString(), RunBudget.defaults(),
				DIGEST, DIGEST, AT);
	}

	private static RunContext start(RunWriter writer, String attempt) throws Exception {
		RunSpec spec = writer.record().spec();
		var binding = new ModelBinding(UUID.randomUUID(), spec.model(), DIGEST, "fake",
				mock(ChatModel.class), mock(ChatClient.class));
		writer.append(new RunEvent.StateChanged(AT, RunState.RUNNING, RunEvent.Reason.NONE));
		writer.append(new RunEvent.AttemptStarted(AT, attempt, binding.bindingId()));
		var turn = new TurnContext(spec.conversationId(), UUID.randomUUID().toString(),
				Path.of(spec.cwd()), binding, spec.runId().value(), attempt);
		return new RunContext(spec, attempt, writer, turn, CLOCK);
	}
}
