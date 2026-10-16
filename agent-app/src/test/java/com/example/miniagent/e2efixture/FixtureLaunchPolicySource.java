package com.example.miniagent.e2efixture;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.example.miniagent.core.change.Fingerprinter;
import com.example.miniagent.core.change.VerificationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fixture-only verification launch policy for E2E launches that run goal gates or /verify without
 * sandbox-exec. It proves gate and evidence plumbing, never OS containment.
 *
 * <p>This package is outside the {@code com.example.miniagent.app} scan root on purpose: scanned, this bean
 * would clash with the bash plugin's sandbox policy in every other agent-app context. Launches name it through
 * {@code --spring.main.sources} together with {@code --agent.plugins.bash.enabled=false}. It is deliberately
 * not {@code @Primary}, so a context that also has the sandbox policy fails instead of silently using this one.
 */
@Configuration(proxyBeanMethods = false)
public class FixtureLaunchPolicySource {

	static final String POLICY_DIGEST = Fingerprinter.sha256("e2e-fixture-pass-through".getBytes(StandardCharsets.UTF_8));

	@Bean
	VerificationRunner.LaunchPolicy fixtureLaunchPolicy() {
		// The runner already chose the private clone as the working directory; the environment stays empty.
		return (context, argv) -> new VerificationRunner.Launch(argv, Map.of(), POLICY_DIGEST);
	}
}
