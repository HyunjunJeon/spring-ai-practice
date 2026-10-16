package com.example.miniagent.e2efixture;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.approval.ApprovalAnswer;
import com.example.miniagent.core.approval.ApprovalRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tier I console for the in-process E2E context: /apply and /undo need an interactive console and an
 * {@code exclusive <id> <digest>} confirmation, which neither RPC (allowlist) nor piped stdin can give.
 * The bean is named agentConsole, so core's {@code @ConditionalOnMissingBean} console backs off.
 * Outside the scan root for the same reason as {@link FixtureLaunchPolicySource}.
 */
@Configuration(proxyBeanMethods = false)
public class ScreenConsoleSource {

	@Bean
	Screen agentConsole() {
		return new Screen(new ByteArrayOutputStream());
	}

	/** Approvals are queued for the test to answer through the broker; readLine is served from a queue. */
	public static final class Screen extends AgentConsole {
		private static final Duration WAIT = Duration.ofSeconds(20);
		private final ByteArrayOutputStream output;
		private final BlockingQueue<ApprovalRequest> approvals = new LinkedBlockingQueue<>();
		private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();

		private Screen(ByteArrayOutputStream output) {
			super(new BufferedReader(new StringReader("")), new PrintStream(output, true, StandardCharsets.UTF_8), true);
			this.output = output;
		}

		@Override public void requestApproval(ApprovalRequest request, Consumer<ApprovalAnswer> response) {
			this.approvals.add(request);
		}

		@Override public String readLine(String prompt) {
			try { return this.answers.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS); }
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new CancellationException("Interrupted while waiting for a console answer");
			}
		}

		/** Queues the next line a command will read. */
		public void answer(String line) { this.answers.add(line); }

		public ApprovalRequest awaitApproval() throws InterruptedException {
			ApprovalRequest request = this.approvals.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
			if (request == null) throw new AssertionError("No approval request within " + WAIT + ":\n" + take());
			return request;
		}

		/** Everything printed since the previous take. */
		public String take() {
			synchronized (this.output) {
				String text = this.output.toString(StandardCharsets.UTF_8);
				this.output.reset();
				return text;
			}
		}
	}
}
