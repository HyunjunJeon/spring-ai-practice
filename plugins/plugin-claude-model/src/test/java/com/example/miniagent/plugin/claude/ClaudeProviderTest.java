package com.example.miniagent.plugin.claude;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.example.miniagent.core.model.ModelRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeProviderTest {

	@TempDir
	Path home;

	@Test
	void nativeFactoriesSnapshotOptionsRatherThanRetainingMutableProperties() {
		var properties = new ClaudeProperties();
		properties.setAuthStore(this.home.resolve("claude.json"));
		var auth = new ClaudeAuth(properties.getAuthStore(), new ClaudeOAuth(Duration.ofSeconds(1)));
		var provider = new ClaudeModelAutoConfiguration().claudeModelProvider(properties, auth);
		ModelRef ref = new ModelRef("claude", properties.getModel());
		var first = provider.create(ref, Map.of("maxTokens", "123"));
		var second = provider.create(ref, Map.of("maxTokens", "456"));
		properties.setModel("later-model");
		properties.setMaxTokens(999);
		assertThat(first).isNotSameAs(second);
		assertThat(first.getOptions().getModel()).isEqualTo(ref.model());
		assertThat(first.getOptions().getMaxTokens()).isEqualTo(123);
		assertThat(second.getOptions().getMaxTokens()).isEqualTo(456);
		assertThat(provider.privateMetadataKeys()).containsExactly("claude.content");
	}

	@Test
	void availabilityNeverParsesAnAuthFile() throws Exception {
		var auth = new ClaudeAuth(this.home.resolve("claude.json"), new ClaudeOAuth(Duration.ofSeconds(1)));
		assertThat(auth.available()).isFalse();
		Files.writeString(auth.store(), "not even JSON");
		assertThat(auth.available()).isTrue();
	}
}
