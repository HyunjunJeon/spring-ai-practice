package com.example.miniagent.core.model;

import java.io.IOException;
import java.util.Optional;

/** Selection persistence independent of conversation memory. Missing is not corrupt. */
public interface SessionModelStore {

	Optional<ModelSelection> load(String sessionId) throws IOException;

	void save(String sessionId, ModelSelection selection) throws IOException;

}
