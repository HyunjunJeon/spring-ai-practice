package com.example.miniagent.plugin.claude;

import java.util.List;

import com.example.miniagent.core.AgentConsole;
import com.example.miniagent.core.SlashCommand;
import com.example.miniagent.core.model.AuthCommand;

/** Claude OAuth 로그인과 자체 저장소 로그아웃 명령. */
public class ClaudeAuthCommands implements AuthCommand.ProviderCommands {

	private final ClaudeAuth auth;

	private final AgentConsole console;

	public ClaudeAuthCommands(ClaudeAuth auth, AgentConsole console) {
		this.auth = auth;
		this.console = console;
	}

	public List<SlashCommand> commands() {
		return List.of(SlashCommand.of("login", "Claude 계정으로 로그인", (args, context) -> login()),
				SlashCommand.of("logout", "mini-agent Claude 토큰 삭제", (args, context) -> logout()));
	}

	@Override
	public String providerId() {
		return "claude";
	}

	@Override
	public void login(String args, SlashCommand.CommandContext context) {
		if (!args.isBlank()) {
			throw new IllegalArgumentException("Usage: /login claude");
		}
		login();
	}

	@Override
	public void logout(SlashCommand.CommandContext context) {
		logout();
	}

	private void login() {
		if (!this.console.isInteractive()) {
			throw new IllegalStateException("Claude login requires an interactive console");
		}
		ClaudeOAuth.Authorization authorization = this.auth.oauth().authorization();
		this.console.println("브라우저에서 Claude 계정으로 로그인한 뒤 반환된 code#state 또는 URL을 붙여넣으세요.");
		this.console.println(authorization.uri().toString());
		String pasted = this.console.readLine("Claude authorization response: ");
		if (pasted == null || pasted.isBlank()) {
			throw new IllegalStateException("Claude authorization response was not provided");
		}
		this.auth.save(this.auth.oauth().exchange(pasted, authorization));
		this.console.println("Claude 로그인 완료. 토큰 위치: " + this.auth.store());
	}

	private void logout() {
		this.console.println(this.auth.logout() ? "Claude 토큰을 삭제했습니다: " + this.auth.store()
				: "저장된 Claude 토큰이 없습니다.");
	}

}
