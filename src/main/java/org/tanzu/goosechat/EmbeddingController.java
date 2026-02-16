package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for embeddings (OpenAI-compatible). Active when EmbeddingClient is available.
 */
@RestController
@RequestMapping("/api/embeddings")
public class EmbeddingController {

    private final EmbeddingClient embeddingClient;

    public EmbeddingController(@Autowired(required = false) EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> embed(@RequestBody Map<String, Object> body) {
        if (embeddingClient == null) {
            return ResponseEntity.ok(Map.of("error", "Embedding not available"));
        }
        Object input = body.get("input");
        if (input instanceof String text) {
            List<Double> embedding = embeddingClient.embed(text);
            return ResponseEntity.ok(Map.of("embedding", embedding, "dimensions", embedding.size()));
        }
        if (input instanceof List<?> list && list.stream().allMatch(s -> s instanceof String)) {
            @SuppressWarnings("unchecked")
            List<String> texts = list.stream().map(o -> (String) o).toList();
            List<List<Double>> embeddings = embeddingClient.embed(texts);
            return ResponseEntity.ok(new HashMap<>(Map.of("embeddings", (Object) embeddings)));
        }
        return ResponseEntity.badRequest().build();
    }
}
