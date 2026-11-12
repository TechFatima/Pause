package com.pause.backend;

/** Sends a system and user prompt to the configured language model. */
public interface LlmClient {

    String complete(String systemPrompt, String userPrompt);
}
