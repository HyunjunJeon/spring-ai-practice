package com.example.miniagent.app;

import java.util.Set;
import java.util.stream.Stream;

import com.example.miniagent.core.AgentAutoConfiguration;
import com.example.miniagent.core.model.AgentChatClientFactory;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENTS.md 의 구조 규칙을 바이트코드 수준에서 검사한다.
 *
 * <p>agent-app 은 core 와 모든 플러그인이 한 클래스패스에 모이는 유일한 모듈이라 모듈 경계 규칙을 여기서 본다.
 * Maven 의존성은 "컴파일 가능 여부"만 막고, 이 테스트는 "누가 무엇을 호출하는가"까지 막는다.
 * 클래스 이름을 문자열로만 적은 참조(예: {@code RunJournalAutoConfiguration} 의 플러그인 이름)는 바이트코드
 * 의존이 아니므로 잡히지 않는다.
 */
class ArchitectureTest {

	private static final String CORE = "com.example.miniagent.core..";

	private static final String PLUGINS = "com.example.miniagent.plugin..";

	private static final String APP = "com.example.miniagent.app..";

	private static final String CORE_ROOT = "com.example.miniagent.core";

	/** 테스트 픽스처를 빼고 운영 코드만 읽는다. 가져오기는 비싸므로 한 번만 한다. */
	private static final JavaClasses CLASSES = new ClassFileImporter()
		.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("com.example.miniagent");

	@Test
	void importsProductionClassesFromEveryModule() {
		assertThat(CLASSES.containPackage("com.example.miniagent.core")).isTrue();
		assertThat(CLASSES.containPackage("com.example.miniagent.plugin.tui")).isTrue();
		assertThat(CLASSES.containPackage("com.example.miniagent.app")).isTrue();
		assertThat(CLASSES.contain(ArchitectureTest.class)).isFalse();
	}

	@Test
	void coreDependsOnNeitherPluginsNorApp() {
		noClasses().that().resideInAPackage(CORE)
			.should().dependOnClassesThat().resideInAnyPackage(PLUGINS, APP)
			.because("core 는 SPI 를 정의하고 플러그인은 Spring 빈으로 기여한다 (AGENTS.md CONVENTIONS)")
			.check(CLASSES);
	}

	@Test
	void pluginsDoNotDependOnApp() {
		noClasses().that().resideInAPackage(PLUGINS)
			.should().dependOnClassesThat().resideInAPackage(APP)
			.because("실행 조립은 agent-app 이 플러그인을 고르는 방향으로만 흐른다")
			.check(CLASSES);
	}

	@Test
	void pluginsDoNotDependOnEachOther() {
		slices().matching("com.example.miniagent.plugin.(*)..")
			.should().notDependOnEachOther()
			.because("Trace 와 TUI 처럼 플러그인끼리는 core 의 TraceEvent 같은 공용 타입으로만 통신한다")
			.check(CLASSES);
	}

	@Test
	void onlyCoreAssemblesTheToolLoop() {
		noClasses().that().doNotHaveFullyQualifiedName(AgentAutoConfiguration.class.getName())
			.and().doNotHaveFullyQualifiedName(AgentChatClientFactory.ScopedAssembly.class.getName())
			.should().accessTargetWhere(toolLoopBuilder())
			.because("도구 루프는 ChatClient 당 ToolCallingAdvisor 하나다. 두 번째 루프나 두 번째 ToolAdvisor 를 만들지 않는다")
			.check(CLASSES);
	}

	@Test
	void chatModelsDoNotExecuteTools() {
		noClasses().that().implement(ChatModel.class)
			.should().dependOnClassesThat().areAssignableTo(ToolCallingManager.class)
			.orShould().callMethod(ToolCallback.class, "call", String.class)
			.orShould().callMethod(ToolCallback.class, "call", String.class, ToolContext.class)
			.because("ChatModel.call() 은 tool call 을 돌려줄 뿐이고 실행은 ToolCallingAdvisor 가 한다")
			.check(CLASSES);
	}

	@Test
	void autoConfigurationsAreRegisteredInImportsFiles() {
		Set<String> registered = Set.copyOf(ImportCandidates
			.load(AutoConfiguration.class, ArchitectureTest.class.getClassLoader()).getCandidates());
		classes().that().areAnnotatedWith(AutoConfiguration.class)
			.should(beListedIn(registered))
			.because("등록하지 않은 자동 구성은 조용히 로드되지 않는다")
			.check(CLASSES);
	}

	@Test
	void domainPackagesKeepTheirInternalOrder() {
		coreDependencies("approval", "run").check(CLASSES);
		coreDependencies("model").check(CLASSES);
		coreDependencies("model.registry", "model", "run").check(CLASSES);
	}

	@Test
	void onlyTheMailboxToolIsTrusted() {
		trustedMailboxRule().check(CLASSES);
	}

	// 1-5단계 동안만 둔다. 6단계에서 계층 규칙(coreLayerRule)이 두 검사를 대신하므로 지운다.
	@Test
	void delegationDoesNotDependOnMessaging() {
		delegationMessagingGuard().check(CLASSES);
	}

	@Test
	void engineDoesNotDependOnBuiltInFeatures() {
		engineFeatureGuard().check(CLASSES);
	}

	/** Final rule (step 6): no package cycle anywhere under core, root package included. */
	static ArchRule coreCycleRule() {
		return slices().assignedFrom(corePackages())
			.should().beFreeOfCycles()
			.because("core 의 패키지는 core 루트까지 포함해 한 방향으로만 의존한다 (패키지 단위 순환 금지)");
	}

	private static SliceAssignment corePackages() {
		return new SliceAssignment() {
			@Override
			public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
				String pkg = javaClass.getPackageName();
				return (pkg.equals(CORE_ROOT) || pkg.startsWith(CORE_ROOT + "."))
						? SliceIdentifier.of(pkg) : SliceIdentifier.ignore();
			}

			@Override
			public String getDescription() {
				return "each package under " + CORE_ROOT + ", the root package included";
			}
		};
	}

	/** Final rule (step 6): coarse layers; the cycle rule and the domain rules fix the order inside a layer. */
	static ArchRule coreLayerRule() {
		return layeredArchitecture().consideringOnlyDependenciesInLayers()
			.layer("Domain").definedBy(CORE_ROOT + ".model", CORE_ROOT + ".run", CORE_ROOT + ".model.registry",
					CORE_ROOT + ".approval", CORE_ROOT + ".change", CORE_ROOT + ".delegation")
			.layer("Engine").definedBy(CORE_ROOT)
			.layer("Integration").definedBy(CORE_ROOT + ".execution", CORE_ROOT + ".run.command",
					CORE_ROOT + ".change.command", CORE_ROOT + ".model.command", CORE_ROOT + ".delegation.runtime")
			.layer("Composition").definedBy(CORE_ROOT + ".autoconfigure")
			.layer("Features").definedBy(CORE_ROOT + ".artifact", CORE_ROOT + ".messaging", CORE_ROOT + ".goal",
					CORE_ROOT + ".lesson")
			.whereLayer("Features").mayNotBeAccessedByAnyLayer()
			.whereLayer("Composition").mayOnlyBeAccessedByLayers("Features")
			.whereLayer("Integration").mayOnlyBeAccessedByLayers("Composition", "Features")
			.whereLayer("Engine").mayOnlyBeAccessedByLayers("Integration", "Composition", "Features")
			.because("값과 저장소(Domain) <- SPI 와 Agent(Engine) <- 실행·명령(Integration) <- 조립(Composition) <- 내장 기능(Features)");
	}

	/** Domain order guard: classes in {@code pkg} may use only {@code pkg} and {@code allowed} inside core. */
	static ArchRule coreDependencies(String pkg, String... allowed) {
		String[] permitted = Stream.concat(Stream.of(pkg), Stream.of(allowed))
			.map(p -> CORE_ROOT + "." + p).toArray(String[]::new);
		return noClasses().that().resideInAPackage(CORE_ROOT + "." + pkg)
			.should().dependOnClassesThat(resideInAPackage(CORE_ROOT + "..").and(not(resideInAnyPackage(permitted))))
			.because("Domain 내부 순서: model <- run <- model.registry, approval <- change <- delegation");
	}

	/** Temporary guard (steps 1-5, superseded by the layer rule in step 6): I6 marker inversion. */
	static ArchRule delegationMessagingGuard() {
		return noClasses().that().resideInAPackage(CORE_ROOT + ".delegation..")
			.should().dependOnClassesThat().resideInAPackage(CORE_ROOT + ".messaging..")
			.because("delegation 은 TrustedMailboxTool 표지만 보고 messaging.MessageTool 을 모른다");
	}

	/** Temporary guard (steps 1-5, superseded by the layer rule in step 6): I1 ControlInput inversion. */
	static ArchRule engineFeatureGuard() {
		return noClasses().that().resideInAPackage(CORE_ROOT)
			.and().doNotHaveFullyQualifiedName(CORE_ROOT + ".AgentAutoConfiguration")
			.should().dependOnClassesThat().resideInAnyPackage(CORE_ROOT + ".goal..", CORE_ROOT + ".lesson..",
					CORE_ROOT + ".messaging..", CORE_ROOT + ".artifact..")
			.because("AgentInputHandler 는 ControlInput 으로 제어 명령을 판별한다");
	}

	/** Permanent rule (step 1): the marker that skips staging/scope checks stays on the mailbox tool only. */
	static ArchRule trustedMailboxRule() {
		return classes().that().implement(CORE_ROOT + ".delegation.TrustedMailboxTool")
			.should().haveFullyQualifiedName(CORE_ROOT + ".messaging.MessageTool")
			.because("TrustedMailboxTool 은 staging·scope 검사를 건너뛰는 표지다. 계보를 스스로 검증하는 MessageTool 만 붙인다");
	}

	private static DescribedPredicate<JavaAccess<?>> toolLoopBuilder() {
		return DescribedPredicate.describe("ToolCallingAdvisor 또는 DefaultToolCallingManager 의 builder()",
				access -> access.getName().equals("builder")
						&& (access.getTargetOwner().isAssignableTo(ToolCallingAdvisor.class)
								|| access.getTargetOwner().isAssignableTo(DefaultToolCallingManager.class)));
	}

	private static ArchCondition<JavaClass> beListedIn(Set<String> registered) {
		return new ArchCondition<>("be listed in META-INF/spring/...AutoConfiguration.imports") {
			@Override
			public void check(JavaClass item, ConditionEvents events) {
				if (!registered.contains(item.getName())) {
					events.add(SimpleConditionEvent.violated(item,
							item.getName() + " 가 AutoConfiguration.imports 에 없다"));
				}
			}
		};
	}

}
