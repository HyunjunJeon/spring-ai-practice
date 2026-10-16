package com.example.miniagent.app;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.miniagent.core.run.SideEffectClass;
import com.example.miniagent.plugin.fs.FsPluginAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * READ_ONLY 는 재개할 때 다시 실행해도 안전하다는 표시다. 이름만 같은 제공자는 믿지 않고,
 * 신뢰된 자동 구성이 낸 정확한 팩토리 기여의 세 도구에만 붙는다.
 */
class BuiltInReadOnlyPolicyTest {

	private static final Set<String> NAMES = Set.of("read_file", "list_dir", "load_skill");

	private static final String[] WITHOUT_BUILT_INS = { "--agent.plugins.fs.enabled=false",
			"--agent.plugins.skills.enabled=false" };

	@TempDir
	Path home;

	/** 신뢰된 도구 이름과 쓰기 도구 이름을 모두 내놓는, 신뢰되지 않은 제공자. */
	static ToolCallbackProvider lookalike() {
		return ToolCallbackProvider.from(List.of("read_file", "list_dir", "load_skill", "write_file").stream()
			.map(BuiltInReadOnlyPolicyTest::callback).toList());
	}

	private static ToolCallback callback(String name) {
		ToolDefinition definition = ToolDefinition.builder().name(name).description("lookalike " + name)
			.inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
		return new ToolCallback() {
			@Override
			public ToolDefinition getToolDefinition() {
				return definition;
			}

			@Override
			public String call(String toolInput) {
				return "lookalike";
			}
		};
	}

	/** 신뢰된 소유자가 아닌 클래스의 인스턴스 팩토리 메서드. */
	static final class ForeignOwner {

		ToolCallbackProvider fileToolsProvider() {
			return lookalike();
		}

		ToolCallbackProvider skillToolsProvider() {
			return lookalike();
		}
	}

	/** 팩토리 빈이 없는 정적 팩토리 메서드. */
	static final class StaticFactory {

		static ToolCallbackProvider fileToolsProvider() {
			return lookalike();
		}
	}

	private static SideEffectClass.Policy policy(ConfigurableApplicationContext context) {
		return context.getBean("builtInReadOnlyPolicy", SideEffectClass.Policy.class);
	}

	private static String liveDigest(ConfigurableApplicationContext context, String provider, String tool) {
		return Arrays.stream(context.getBean(provider, ToolCallbackProvider.class).getToolCallbacks())
			.map(ToolCallback::getToolDefinition).filter(definition -> definition.name().equals(tool))
			.map(SideEffectClass::definitionDigest).findFirst().orElseThrow();
	}

	private static void register(ConfigurableApplicationContext context, String name, BeanDefinitionBuilder builder) {
		// SAFETY: a non-web SpringApplication builds an AnnotationConfigApplicationContext, which is a GenericApplicationContext.
		((GenericApplicationContext) context).registerBeanDefinition(name, builder.getBeanDefinition());
	}

	private static BeanDefinitionBuilder factoryMethod(String method, String owner) {
		return BeanDefinitionBuilder.genericBeanDefinition().setFactoryMethodOnBean(method, owner);
	}

	@Test
	void realContextTrustsExactlyTheThreeBuiltInReadersByTheirLiveDefinitionDigest() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "")) {
			Map<String, String> trusted = policy(context).readOnlyDefinitions();

			assertThat(trusted).containsOnlyKeys(NAMES);
			assertThat(trusted.get("read_file")).isEqualTo(liveDigest(context, "fileToolsProvider", "read_file"));
			assertThat(trusted.get("list_dir")).isEqualTo(liveDigest(context, "fileToolsProvider", "list_dir"));
			assertThat(trusted.get("load_skill")).isEqualTo(liveDigest(context, "skillToolsProvider", "load_skill"));
			assertThat(trusted.values()).allSatisfy(digest -> assertThat(digest).matches("[0-9a-f]{64}"));
		}
	}

	@Test
	void sameNamedProviderFromAForeignFactoryOwnerIsNotTrusted() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "", WITHOUT_BUILT_INS)) {
			register(context, "foreignOwner", BeanDefinitionBuilder.genericBeanDefinition(ForeignOwner.class));
			register(context, "fileToolsProvider", factoryMethod("fileToolsProvider", "foreignOwner"));
			register(context, "skillToolsProvider", factoryMethod("skillToolsProvider", "foreignOwner"));
			assertThat(liveDigest(context, "fileToolsProvider", "read_file")).isNotEmpty();

			assertThat(policy(context).readOnlyDefinitions()).isEmpty();
		}
	}

	@Test
	void trustedOwnerClassWithADifferentFactoryMethodIsNotTrusted() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "", WITHOUT_BUILT_INS)) {
			register(context, "fsOwner", BeanDefinitionBuilder.genericBeanDefinition(FsPluginAutoConfiguration.class));
			register(context, "fileToolsProvider", factoryMethod("legacyUndo", "fsOwner"));

			assertThat(policy(context).readOnlyDefinitions()).isEmpty();
		}
	}

	@Test
	void staticFactoryMethodWithoutAFactoryBeanIsNotTrusted() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "", WITHOUT_BUILT_INS)) {
			register(context, "fileToolsProvider",
					BeanDefinitionBuilder.rootBeanDefinition(StaticFactory.class, "fileToolsProvider"));
			assertThat(liveDigest(context, "fileToolsProvider", "read_file")).isNotEmpty();

			assertThat(policy(context).readOnlyDefinitions()).isEmpty();
		}
	}

	@Test
	void foreignSkillProviderDoesNotWidenTheTrustedFileTools() {
		try (var context = ProviderProfileMigrationTest.start(this.home, "", "--agent.plugins.skills.enabled=false")) {
			register(context, "foreignOwner", BeanDefinitionBuilder.genericBeanDefinition(ForeignOwner.class));
			register(context, "skillToolsProvider", factoryMethod("skillToolsProvider", "foreignOwner"));

			Map<String, String> trusted = policy(context).readOnlyDefinitions();

			assertThat(trusted).containsOnlyKeys("read_file", "list_dir");
			assertThat(trusted.get("read_file")).isEqualTo(liveDigest(context, "fileToolsProvider", "read_file"))
				.isNotEqualTo(liveDigest(context, "skillToolsProvider", "read_file"));
		}
	}
}
