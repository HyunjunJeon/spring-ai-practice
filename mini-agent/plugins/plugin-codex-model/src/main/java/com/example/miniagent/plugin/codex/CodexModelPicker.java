package com.example.miniagent.plugin.codex;

import java.util.List;
import java.util.Optional;
import java.util.HashMap;
import java.util.function.Supplier;
import java.util.function.Consumer;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.model.ModelRef;
import com.example.miniagent.core.model.ModelSelection;
import org.jspecify.annotations.Nullable;

/**
 * {@code /codex models}, {@code /codex model [번호|slug]}, {@code /codex effort [level]} 의 동작.
 *
 * <p>인자 없이 부르면 번호 목록을 보여주고 고르게 한다. 모델을 바꿀 때 현재 reasoning effort 를 새 모델이
 * 지원하지 않으면(예: ultra → gpt-6-luna) 그 모델의 기본값으로 맞춘다. 백엔드는 지원하지 않는 조합을 400 으로 거부한다.
 */
public class CodexModelPicker {

	private final CodexModelCatalog catalog;

	private final Supplier<ModelSelection> selection;

	private final Consumer<ModelSelection> select;

	private final String defaultEffort;

	private final AgentConsole console;

	public CodexModelPicker(CodexModelCatalog catalog, Supplier<ModelSelection> selection,
			Consumer<ModelSelection> select, AgentConsole console, String defaultEffort) {
		this.catalog = catalog;
		this.selection = selection;
		this.select = select;
		this.console = console;
		this.defaultEffort = defaultEffort;
	}

	public void listModels() {
		List<CodexModelCatalog.ModelInfo> models = this.catalog.models();
		if (models.isEmpty()) {
			this.console.println("모델 목록이 없습니다: " + this.catalog.cacheFile() + "  (Codex CLI 를 한 번 실행하면 생깁니다)");
			return;
		}
		for (int i = 0; i < models.size(); i++) {
			CodexModelCatalog.ModelInfo m = models.get(i);
			boolean current = m.slug().equals(this.selection.get().ref().model());
			this.console.println("%s %2d) %-16s effort: %s".formatted(current ? "*" : " ", i + 1, m.slug(),
					String.join(" ", m.efforts())));
		}
	}

	public void chooseModel(String argument) {
		String choice = argument.strip();
		if (choice.isEmpty()) {
			listModels();
			if (this.catalog.models().isEmpty()) {
				return;
			}
			choice = ask("번호 또는 slug [Enter=취소]: ");
			if (choice == null || choice.isBlank()) {
				return;
			}
		}
		Optional<CodexModelCatalog.ModelInfo> picked = resolve(choice);
		String slug = picked.map(CodexModelCatalog.ModelInfo::slug).orElse(choice);
		if (picked.isEmpty() && !this.catalog.models().isEmpty()) {
			this.console.error("목록에 없는 모델입니다: " + choice + "  (/codex models 로 확인)");
			return;
		}
		String effort = effort();
		if (picked.isPresent() && !picked.get().supports(effort)) {
			String fallback = picked.get().defaultEffort();
			effort = fallback;
			this.console.info("%s 는 effort '%s' 를 지원하지 않아 기본값 '%s' 로 바꿨습니다.".formatted(slug, effort, fallback));
		}
		select(slug, effort);
		this.console.println("model = %s, reasoning effort = %s".formatted(slug, effort));
	}

	public void chooseEffort(String argument) {
		Optional<CodexModelCatalog.ModelInfo> current = this.catalog.find(this.selection.get().ref().model());
		String effort = argument.strip();
		if (effort.isEmpty()) {
			current.ifPresent(m -> this.console.println("%s 가 지원하는 effort: %s (기본 %s)".formatted(m.slug(),
					String.join(" ", m.efforts()), m.defaultEffort())));
			effort = ask("effort [현재 %s, Enter=유지]: ".formatted(effort()));
			if (effort == null || effort.isBlank()) {
				return;
			}
			effort = effort.strip();
		}
		if (current.isPresent() && !current.get().supports(effort)) {
			this.console.error("%s 는 effort '%s' 를 지원하지 않습니다. 가능: %s".formatted(current.get().slug(), effort,
					String.join(" ", current.get().efforts())));
			return;
		}
		select(this.selection.get().ref().model(), effort);
		this.console.println("reasoning effort = " + effort);
	}

	private String effort() {
		return this.selection.get().options().getOrDefault("effort", this.defaultEffort);
	}

	private void select(String slug, String effort) {
		var options = new HashMap<>(this.selection.get().options());
		options.put("effort", effort);
		this.select.accept(new ModelSelection(new ModelRef("codex", slug), options));
	}

	/** 번호(1부터) 또는 slug. */
	private Optional<CodexModelCatalog.ModelInfo> resolve(String choice) {
		List<CodexModelCatalog.ModelInfo> models = this.catalog.models();
		if (choice.matches("\\d+")) {
			int index = Integer.parseInt(choice) - 1;
			return (index >= 0 && index < models.size()) ? Optional.of(models.get(index)) : Optional.empty();
		}
		return models.stream().filter(m -> m.slug().equals(choice)).findFirst();
	}

	private @Nullable String ask(String prompt) {
		if (!this.console.isInteractive()) {
			return null;
		}
		return this.console.readLine(prompt);
	}

}
