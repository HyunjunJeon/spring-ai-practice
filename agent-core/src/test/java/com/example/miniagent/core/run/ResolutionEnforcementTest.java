package com.example.miniagent.core.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.example.miniagent.core.ToolInterceptor;
import com.example.miniagent.core.execution.RunExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import static org.assertj.core.api.Assertions.*;

/** Operator SKIP and MUTATING_RETRY are journaled; these tests prove the next attempt actually obeys them. */
class ResolutionEnforcementTest {
	private static final String SAME_ARGS = "{}";
	private static final String OTHER_ARGS = "{\"path\":\"other\"}";
	@TempDir Path temp;

	private static String call(SafeResumeTest.Fixture f, String input, ToolInterceptor.Chain chain) {
		return f.journal.intercept(new ToolInterceptor.ToolInvocation(f.tool, input,
				new ToolContext(Map.of(RunContext.TOOL_CONTEXT_KEY, RunContext.current().orElseThrow()))), chain);
	}

	/** First attempt: the tool starts, then the process dies mid-effect. It stays UNKNOWN. */
	private static RunRecord crashMidEffect(SafeResumeTest.Fixture f, AtomicInteger effects) throws Exception {
		f.action.set(p -> call(f, SAME_ARGS, i -> {
			effects.incrementAndGet();
			throw new IllegalStateException("died mid-effect");
		}));
		RunRecord record = f.start();
		assertThat(record.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(record.uncertainTools()).hasSize(1);
		return record;
	}

	private static RunExecutor.ResumeOptions resolve(RunRecord record, RunEvent.Resolution decision) {
		var unknown = record.uncertainTools().getFirst();
		return new RunExecutor.ResumeOptions(List.of(new RunExecutor.ToolResolutionInput(unknown.attemptId(),
				unknown.toolCallKey(), decision, "operator", "inspection")), null);
	}

	private static long count(SafeResumeTest.Fixture f, RunRecord record, Class<? extends RunEvent> type) throws Exception {
		return FileRunStore.events(f.runs, record.spec().runId()).stream().filter(type::isInstance).count();
	}

	@Test void skippedToolCallIsRefusedWhenTheModelRepeatsItWithTheSameArguments() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		RunRecord crashed = crashMidEffect(f, effects);

		var refused = new AtomicReference<Stop>();
		var chainEntered = new AtomicBoolean();
		f.action.set(p -> {
			try { call(f, SAME_ARGS, i -> { chainEntered.set(true); return "must not run"; }); }
			catch (Stop stop) { refused.set(stop); throw stop; }
		});
		RunRecord resumed = f.executor.resume(crashed.spec().runId(), f.selected, f.cwd,
				resolve(crashed, RunEvent.Resolution.SKIP)).record();

		assertThat(refused.get()).as("the repeated skipped call must be stopped").isNotNull();
		assertThat(refused.get().kind()).isEqualTo(Stop.Kind.INTERRUPTED);
		assertThat(refused.get()).hasMessage("Operator skipped this UNKNOWN effect");
		assertThat(chainEntered).isFalse();
		assertThat(effects).hasValue(1);
		assertThat(resumed.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(resumed.uncertainTools()).isEmpty();
		assertThat(count(f, resumed, RunEvent.ToolStarted.class)).isEqualTo(1);
		assertThat(count(f, resumed, RunEvent.ToolFinished.class)).isZero();
		assertThat(count(f, resumed, RunEvent.ToolResolution.class)).isEqualTo(1);
	}

	@Test void skipOnlyBlocksTheExactToolAndArgumentsTheOperatorRuledOut() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		RunRecord crashed = crashMidEffect(f, effects);

		f.action.set(p -> call(f, OTHER_ARGS, i -> {
			effects.incrementAndGet();
			return "ran with other arguments";
		}));
		RunRecord resumed = f.executor.resume(crashed.spec().runId(), f.selected, f.cwd,
				resolve(crashed, RunEvent.Resolution.SKIP)).record();

		assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(effects).hasValue(2);
		assertThat(count(f, resumed, RunEvent.ToolStarted.class)).isEqualTo(2);
		assertThat(count(f, resumed, RunEvent.ToolFinished.class)).isEqualTo(1);
	}

	@Test void mutatingRetryRunsTheRepeatedCallExactlyOnce() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		RunRecord crashed = crashMidEffect(f, effects);

		var refused = new AtomicReference<Stop>();
		var secondChainEntered = new AtomicBoolean();
		f.action.set(p -> {
			assertThat(call(f, SAME_ARGS, i -> {
				effects.incrementAndGet();
				return "retried";
			})).isEqualTo("retried");
			try { call(f, SAME_ARGS, i -> { secondChainEntered.set(true); return "second repeat"; }); }
			catch (Stop stop) { refused.set(stop); throw stop; }
		});
		RunRecord resumed = f.executor.resume(crashed.spec().runId(), f.selected, f.cwd,
				resolve(crashed, RunEvent.Resolution.MUTATING_RETRY)).record();

		assertThat(effects).as("one crashed run plus exactly one operator-approved retry").hasValue(2);
		assertThat(refused.get()).as("the second identical call after the retry must be stopped").isNotNull();
		assertThat(refused.get().kind()).isEqualTo(Stop.Kind.INTERRUPTED);
		assertThat(refused.get()).hasMessage("Verified mutation must not be automatically repeated");
		assertThat(secondChainEntered).isFalse();
		assertThat(resumed.state()).isEqualTo(RunState.INTERRUPTED);
		assertThat(resumed.uncertainTools()).isEmpty();
		assertThat(count(f, resumed, RunEvent.ToolStarted.class)).isEqualTo(2);
		assertThat(count(f, resumed, RunEvent.ToolFinished.class)).isEqualTo(1);
	}

	@Test void mutatingRetryDoesNotWaiveTheGuardForDifferentArguments() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, false);
		var effects = new AtomicInteger();
		RunRecord crashed = crashMidEffect(f, effects);

		f.action.set(p -> {
			call(f, SAME_ARGS, i -> { effects.incrementAndGet(); return "retried"; });
			call(f, OTHER_ARGS, i -> { effects.incrementAndGet(); return "different call"; });
		});
		RunRecord resumed = f.executor.resume(crashed.spec().runId(), f.selected, f.cwd,
				resolve(crashed, RunEvent.Resolution.MUTATING_RETRY)).record();

		assertThat(resumed.state()).isEqualTo(RunState.COMPLETED);
		assertThat(effects).hasValue(3);
		assertThat(count(f, resumed, RunEvent.ToolFinished.class)).isEqualTo(2);
	}

	@Test void readOnlyToolMayRepeatTheSameCallBecauseOnlyMutationsAreGuarded() throws Exception {
		var f = new SafeResumeTest.Fixture(this.temp, true);
		var effects = new AtomicInteger();
		f.action.set(p -> {
			call(f, SAME_ARGS, i -> { effects.incrementAndGet(); return "read"; });
			call(f, SAME_ARGS, i -> { effects.incrementAndGet(); return "read"; });
		});
		RunRecord record = f.start();

		assertThat(record.state()).isEqualTo(RunState.COMPLETED);
		assertThat(effects).hasValue(2);
		assertThat(count(f, record, RunEvent.ToolFinished.class)).isEqualTo(2);
	}
}
