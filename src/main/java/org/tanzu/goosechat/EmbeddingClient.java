package org.tanzu.goosechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Client for OpenAI-compatible embeddings API.
 * Reads credentials from the {@code dekt-genai-embed} service in VCAP_SERVICES (api_base, api_key, model_name),
 * falling back to EMBEDDING_HOST / EMBEDDING_API_KEY / EMBEDDING_MODEL env vars.
 * Enable with app.embedding.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "app.embedding.enabled", havingValue = "true")
@ConditionalOnBean(EmbeddingProperties.class)
public class EmbeddingClient {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingClient.class);

    private final RestClient restClient;
    private final String model;

    public EmbeddingClient(EmbeddingProperties properties) {
        String baseUrl = properties.getBaseUrl();
        String apiKey = properties.getApiKey();
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Embedding requires api_base and api_key. Bind a GenAI embedding service (e.g. dekt-genai-embed) or set EMBEDDING_HOST and EMBEDDING_API_KEY.");
        }
        this.model = properties.getModel();
        String embeddingsPath = baseUrl.endsWith("/") ? baseUrl + "v1/embeddings" : baseUrl + "/v1/embeddings";
        this.restClient = RestClient.builder()
                .baseUrl(embeddingsPath)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        logger.info("Embedding client configured: model={}, base={}", model, baseUrl);
    }

    /**
     * Get embedding vector for the given input text (OpenAI-compatible request).
     */
    public List<Double> embed(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input must not be blank");
        }
        EmbedRequest request = new EmbedRequest(model, input);
        EmbedResponse response = restClient.post()
                .body(request)
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null || response.data == null || response.data.isEmpty()) {
            throw new IllegalStateException("Empty embedding response");
        }
        return response.data.get(0).embedding;
    }

    /**
     * Get embedding vectors for multiple inputs in one request.
     */
    public List<List<Double>> embed(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must not be empty");
        }
        EmbedRequest request = new EmbedRequest(model, inputs);
        EmbedResponse response = restClient.post()
                .body(request)
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null || response.data == null) {
            throw new IllegalStateException("Empty embedding response");
        }
        return response.data.stream()
                .map(d -> d.embedding)
                .toList();
    }

    // --- Request/response DTOs (OpenAI-compatible) ---

    private record EmbedRequest(String model, Object input) {
        EmbedRequest(String model, String input) {
            this(model, (Object) input);
        }
        EmbedRequest(String model, List<String> input) {
            this(model, (Object) input);
        }
    }

    private static class EmbedResponse {
        List<EmbeddingData> data;
    }

    private static class EmbeddingData {
        List<Double> embedding;
        @JsonProperty("index")
        Integer index;
    }
}
