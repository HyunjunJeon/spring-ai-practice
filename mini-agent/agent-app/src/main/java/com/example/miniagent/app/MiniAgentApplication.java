package com.example.miniagent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MiniAgentApplication {

	public static void main(String[] args) {
		// REPL 이 끝나면 컨텍스트를 닫고 JVM 을 종료한다(MCP stdio 프로세스 같은 비데몬 스레드 정리).
		System.exit(SpringApplication.exit(SpringApplication.run(MiniAgentApplication.class, args)));
	}

}
