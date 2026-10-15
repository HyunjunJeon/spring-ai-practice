package com.example.miniagent.core.model;

import java.util.List;

import com.example.miniagent.core.SlashCommand;

/** One dispatch point; providers contribute actions rather than duplicate slash names. */
public final class AuthCommand implements SlashCommand {

	public interface ProviderCommands {
		String providerId();
		void login(String args, CommandContext context);
		void logout(CommandContext context);
	}

	private final String name;
	private final List<ProviderCommands> providers;

	public AuthCommand(String name, List<ProviderCommands> providers) {
		if (!"login".equals(name) && !"logout".equals(name)) {
			throw new IllegalArgumentException("Unknown auth command: " + name);
		}
		if (providers.stream().map(ProviderCommands::providerId).distinct().count() != providers.size()) {
			throw new IllegalArgumentException("Duplicate auth provider");
		}
		this.name = name;
		this.providers = List.copyOf(providers);
	}

	@Override
	public String name() {
		return this.name;
	}

	@Override
	public String description() {
		return "/" + this.name + " [provider] (생략하면 현재 선택 provider)";
	}

	@Override
	public void execute(String args, CommandContext context) {
		String[] parts = args.strip().split("\\s+", 2);
		String selected = context.agent().selectedModel().ref().provider();
		boolean device = "device".equals(parts[0]) && "codex".equals(selected) && "login".equals(this.name);
		String id = parts[0].isEmpty() || device ? selected : parts[0];
		String rest = device ? "device" : parts.length == 2 ? parts[1] : "";
		ProviderCommands provider = this.providers.stream().filter(p -> p.providerId().equals(id)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Provider " + id
					+ " has no interactive authentication; configure its API key or local endpoint"));
		if ("login".equals(this.name)) {
			provider.login(rest, context);
		}
		else {
			if (!rest.isBlank()) {
				throw new IllegalArgumentException("Usage: /logout [provider]");
			}
			provider.logout(context);
		}
	}
}
