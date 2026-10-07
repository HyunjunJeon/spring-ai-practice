package com.example.miniagent.plugin.codex;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** /codex model, /codex effort 의 대화형 선택. 입력은 미리 적어 둔 문자열로 흉내 낸다. */
class CodexModelPickerTest {

	@TempDir
	Path dir;

	private CodexModelCatalog catalog;

	private final ByteArrayOutputStream output = new ByteArrayOutputStream();

	@BeforeEach
	void cache() throws Exception {
		Files.writeString(this.dir.resolve("models_cache.json"), """
				{"models": [
				  {"slug": "gpt-6.1-sol", "visibility": "list", "default_reasoning_level": "low",
				   "supported_reasoning_levels": [{"effort": "low"}, {"effort": "xhigh"}, {"effort": "ultra"}]},
				  {"slug": "gpt-6-luna", "visibility": "list", "default_reasoning_level": "medium",
				   "supported_reasoning_levels": [{"effort": "low"}, {"effort": "medium"}, {"effort": "xhigh"}, {"effort": "max"}]},
				  {"slug": "hidden-model", "visibility": "hide", "default_reasoning_level": "medium", "supported_reasoning_levels": []}
				]}
				""");
		this.catalog = new CodexModelCatalog(this.dir.resolve("models_cache.json"), JsonMapper.builder().build());
	}

	@Test
	void hiddenModelsAreNotListed() {
		assertThat(this.catalog.models()).extracting(CodexModelCatalog.ModelInfo::slug)
			.containsExactly("gpt-6.1-sol", "gpt-6-luna");
	}

	@Test
	void pickByNumberFromInteractiveList() {
		var model = model("gpt-6.1-sol", "xhigh");

		picker(model, "2\n").chooseModel("");

		assertThat(model.get().ref().model()).isEqualTo("gpt-6-luna");
		assertThat(model.get().options().get("effort")).isEqualTo("xhigh");
		assertThat(printed()).contains("*  1) gpt-6.1-sol", "   2) gpt-6-luna");
	}

	@Test
	void unsupportedEffortFallsBackToModelDefaultWhenSwitching() {
		var model = model("gpt-6.1-sol", "ultra");

		picker(model, "").chooseModel("gpt-6-luna");

		// gpt-6-luna 는 ultra 를 지원하지 않는다 → 그 모델의 기본값 medium
		assertThat(model.get().options().get("effort")).isEqualTo("medium");
	}

	@Test
	void unknownModelIsRejected() {
		var model = model("gpt-6-luna", "xhigh");

		picker(model, "").chooseModel("gpt-6.1-luna");

		assertThat(model.get().ref().model()).isEqualTo("gpt-6-luna");
		assertThat(printed()).contains("목록에 없는 모델");
	}

	@Test
	void effortIsValidatedAgainstCurrentModel() {
		var model = model("gpt-6-luna", "xhigh");

		picker(model, "").chooseEffort("ultra");
		assertThat(model.get().options().get("effort")).isEqualTo("xhigh");

		picker(model, "max\n").chooseEffort("");
		assertThat(model.get().options().get("effort")).isEqualTo("max");
	}

	private CodexModelPicker picker(AtomicReference<ModelSelection> model, String input) {
		var console = new AgentConsole(new BufferedReader(new StringReader(input)),
				new PrintStream(this.output, true, StandardCharsets.UTF_8), true);
		return new CodexModelPicker(this.catalog, model::get, model::set, console, "xhigh");
	}

	/** 선택 로직은 네트워크를 쓰지 않으므로 auth/client 없이 만든다. */
	private static AtomicReference<ModelSelection> model(String slug, String effort) {
		return new AtomicReference<>(new ModelSelection(new ModelRef("codex", slug), Map.of("effort", effort)));
	}

	private String printed() {
		return this.output.toString(StandardCharsets.UTF_8);
	}

}
