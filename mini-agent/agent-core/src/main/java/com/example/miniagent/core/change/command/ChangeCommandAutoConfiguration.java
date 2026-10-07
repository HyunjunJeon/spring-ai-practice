package com.example.miniagent.core.change.command;

import java.time.Clock;
import java.time.Duration;

import com.example.miniagent.core.AgentAutoConfiguration;
import com.example.miniagent.core.approval.ApprovalBroker;
import com.example.miniagent.core.change.ApplyEngine;
import com.example.miniagent.core.change.ChangeStore;
import com.example.miniagent.core.change.RecoveryScanner;
import com.example.miniagent.core.change.UndoEngine;
import com.example.miniagent.core.change.VerificationRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.context.ApplicationEventPublisher;

/** Command discovery does not initialize workspace storage or mutation services. */
@AutoConfiguration(after = AgentAutoConfiguration.class)
public class ChangeCommandAutoConfiguration {
	@Bean
	ChangesCommand changesCommand(ObjectProvider<ChangeStore> stores, ObjectProvider<RecoveryScanner> scanners,
			ApplicationEventPublisher events) {
		return new ChangesCommand(stores::getObject, scanners::getObject, events);
	}

	@Bean
	ApplyCommand applyCommand(ObjectProvider<ChangeStore> stores, ObjectProvider<ApplyEngine> engines,
			ApprovalBroker broker, ObjectProvider<Clock> clocks, ApplicationEventPublisher events) {
		return new ApplyCommand(stores::getObject, engines::getObject, broker, clocks.getIfAvailable(Clock::systemUTC), events);
	}

	@Bean
	VerifyCommand verifyCommand(ObjectProvider<ChangeStore> stores, ObjectProvider<VerificationRunner> runners,
			Environment environment, ApplicationEventPublisher events) {
		Duration timeout = DurationStyle.detectAndParse(environment.getProperty("agent.changes.verification-timeout", "30m"));
		int output = environment.getProperty("agent.changes.verification-max-output-bytes", Integer.class,
				environment.getProperty("agent.plugins.bash.max-output-bytes", Integer.class, 1048576));
		return new VerifyCommand(stores::getObject, runners::getObject, timeout, output, events);
	}

	@Bean
	UndoCommand undoCommand(ObjectProvider<ChangeStore> stores, ObjectProvider<ApplyEngine> apply,
			ObjectProvider<UndoEngine> undo, ObjectProvider<RecoveryScanner> scanners,
			ObjectProvider<UndoCommand.LegacyUndo> legacy, ApplicationEventPublisher events) {
		return new UndoCommand(stores::getObject, apply::getObject, undo::getObject, scanners::getObject,
				legacy::getIfAvailable, events);
	}
}
