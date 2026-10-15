package com.example.miniagent.plugin.tui;

import java.text.Normalizer;

import dev.tamboui.text.CharWidth;

/**
 * 터미널 표시 폭. 한글/한자/전각 문자는 2칸을 차지한다. 상자 테두리를 맞추려면 글자 수가 아니라 이 폭으로 패딩해야 한다.
 *
 * <p>화면을 그리는 TamboUI 와 같은 계산을 써야 테두리가 맞으므로 {@link CharWidth} 에 위임한다.
 * macOS 파일 이름처럼 자모가 분리된(NFD) 한글은 폭이 틀리게 계산되므로 먼저 NFC 로 합친다.
 */
final class DisplayWidth {

	private DisplayWidth() {
	}

	static String nfc(String text) {
		return Normalizer.normalize(text, Normalizer.Form.NFC);
	}

	static int of(String text) {
		return CharWidth.of(nfc(text));
	}

	static String padRight(String text, int width) {
		int pad = width - of(text);
		return (pad > 0) ? text + " ".repeat(pad) : text;
	}

	static String truncate(String text, int width) {
		String normalized = nfc(text);
		return (CharWidth.of(normalized) <= width) ? normalized
				: CharWidth.truncateWithEllipsis(normalized, width, CharWidth.TruncatePosition.END);
	}

}
