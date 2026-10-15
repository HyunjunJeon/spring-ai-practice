package com.example.miniagent.core.delegation;

/**
 * 신뢰하는 Java 우편함 도구 표지. 메서드는 없다.
 *
 * <p>이 표지는 도구가 정확한 관리 계보(managed lineage)를 스스로 검증하고 파일시스템 경로를 갖지 않는다는 뜻이다.
 * 그래서 이 표지를 붙인 도구는 자식 staging 과 scope 검사를 건너뛴다. 이 검사를 우회하는 신뢰 경계이므로
 * {@code messaging.MessageTool} 만 붙이고, ArchitectureTest 가 다른 구현을 막는다.
 */
public interface TrustedMailboxTool {
}
