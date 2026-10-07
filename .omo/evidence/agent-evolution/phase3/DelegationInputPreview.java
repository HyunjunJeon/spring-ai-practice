package com.example.miniagent.core.delegation;

import java.nio.file.Path;
import java.util.List;

/** Isolated wire smoke check; full command/backend integration remains unverified. */
public final class DelegationInputPreview {
	public static void main(String[] args) throws Exception {
		var input = DelegationInput.parse("""
				{"planId":"example","children":[{"childId":"research","goal":"read","role":"RESEARCH","tools":["read_file"]}]}
				""");
		var plan = input.plan(Path.of(args[0]).toRealPath());
		if (!plan.children().getFirst().scope().toolNames().contains("read_file")
				|| plan.children().getFirst().scope().apply())
			throw new AssertionError("Wrong scope");
		DelegationInput.JSON.readTree(DelegationInput.SCHEMA);
		for (String malformed : List.of(
				"{\"planId\":\"a\",\"planId\":\"b\",\"children\":[]}",
				"{\"planId\":\"a\",\"children\":[],\"parentAuthority\":{}}",
				"{\"planId\":\"a\",\"children\":[]} {}")) {
			boolean rejected = false;
			try { DelegationInput.parse(malformed); }
			catch (RuntimeException expected) { rejected = true; }
			if (!rejected) throw new AssertionError("Accepted malformed input");
		}
		System.out.println("WIRE_SCHEMA_AND_SCOPE_OK");
	}
}
