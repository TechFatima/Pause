package com.pause.backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calls Vultr's OpenAI-compatible chat-completions endpoint. */
@Component
public class VultrLlmClient implements LlmClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public VultrLlmClient(
            ObjectMapper objectMapper,
            @Value("${llm.base-url:}") String baseUrl,
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:}") String model
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        try {
            if (apiKey == null || apiKey.isBlank()
                    || baseUrl == null || baseUrl.isBlank()
                    || model == null || model.isBlank()) {
                throw new IllegalStateException();
            }

            Map<String, Object> requestBody = new RedactedRequestBody();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            requestBody.put("max_tokens", 600);
            requestBody.put("temperature", 0.2);
            String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";

            String responseBody = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new IllegalStateException();
            }
            return content.asText();
        } catch (Exception ignored) {
            // Do not expose the provider's response, prompt, key, or exception details.
            throw new IllegalStateException("LLM service unavailable");
        }
    }

    /** Spring's debug log prints the request object's toString; keep prompts out of that log. */
    private static class RedactedRequestBody extends LinkedHashMap<String, Object> {
        @Override
        public String toString() {
            return "Vultr chat request [redacted]";
        }
    }
}
