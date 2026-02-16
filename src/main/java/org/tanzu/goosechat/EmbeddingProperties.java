package org.tanzu.goosechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;

/**
 * Resolves embedding model credentials from Cloud Foundry VCAP_SERVICES.
 * <p>
 * Tanzu GenAI services expose credentials per binding with fields:
 * {@code api_base}, {@code api_key}, {@code model_name}, {@code model_capabilities}.
 * <p>
 * This class scans all "genai" services in VCAP_SERVICES and picks the one whose
 * {@code model_capabilities} includes "embedding", or whose instance name matches
 * the configured service name (default: "dekt-genai-embed").
 * <p>
 * Fallback: EMBEDDING_HOST / EMBEDDING_API_KEY / EMBEDDING_MODEL env vars.
 */
public class EmbeddingProperties {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingProperties.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public EmbeddingProperties() {
        this(System.getenv("VCAP_SERVICES"),
             System.getenv("EMBEDDING_SERVICE_NAME"),
             System.getenv("EMBEDDING_HOST"),
             System.getenv("EMBEDDING_API_KEY"),
             System.getenv("EMBEDDING_MODEL"));
    }

    /**
     * Visible-for-testing constructor.
     */
    EmbeddingProperties(String vcapServices, String serviceNameHint,
                        String envHost, String envApiKey, String envModel) {
        String resolvedHost = "";
        String resolvedKey = "";
        String resolvedModel = "";

        // 1. Try VCAP_SERVICES
        if (vcapServices != null && !vcapServices.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(vcapServices);
                JsonNode embeddingCreds = findEmbeddingService(root, serviceNameHint);
                if (embeddingCreds != null) {
                    resolvedHost = textOrEmpty(embeddingCreds, "api_base");
                    resolvedKey = textOrEmpty(embeddingCreds, "api_key");
                    resolvedModel = textOrEmpty(embeddingCreds, "model_name");
                    logger.info("Resolved embedding credentials from VCAP_SERVICES: model={}, base={}",
                            resolvedModel, resolvedHost);
                }
            } catch (Exception e) {
                logger.warn("Failed to parse VCAP_SERVICES for embedding service", e);
            }
        }

        // 2. Fallback to explicit env vars
        if (resolvedHost.isBlank() && envHost != null && !envHost.isBlank()) {
            resolvedHost = envHost.trim();
        }
        if (resolvedKey.isBlank() && envApiKey != null && !envApiKey.isBlank()) {
            resolvedKey = envApiKey.trim();
        }
        if (resolvedModel.isBlank() && envModel != null && !envModel.isBlank()) {
            resolvedModel = envModel.trim();
        }
        if (resolvedModel.isBlank()) {
            resolvedModel = "text-embedding-3-small";
        }

        this.baseUrl = resolvedHost;
        this.apiKey = resolvedKey;
        this.model = resolvedModel;
    }

    /**
     * Scan VCAP_SERVICES for a GenAI service with embedding capability.
     * <p>
     * Strategy:
     * <ol>
     *   <li>Match by instance name (EMBEDDING_SERVICE_NAME or default "dekt-genai-embed")</li>
     *   <li>Match by model_capabilities containing "embedding"</li>
     * </ol>
     *
     * @return the credentials node, or null if not found
     */
    private static JsonNode findEmbeddingService(JsonNode vcapRoot, String serviceNameHint) {
        String targetName = (serviceNameHint != null && !serviceNameHint.isBlank())
                ? serviceNameHint.trim()
                : "dekt-genai-embed";

        // Iterate over all service types (e.g. "genai", "user-provided", etc.)
        Iterator<Map.Entry<String, JsonNode>> fields = vcapRoot.fields();
        JsonNode capabilityMatch = null;

        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode servicesArray = entry.getValue();
            if (!servicesArray.isArray()) continue;

            for (JsonNode service : servicesArray) {
                String instanceName = textOrEmpty(service, "instance_name");
                if (instanceName.isBlank()) {
                    instanceName = textOrEmpty(service, "name");
                }
                JsonNode creds = service.get("credentials");
                if (creds == null) continue;

                // Match by instance name
                if (targetName.equalsIgnoreCase(instanceName)) {
                    return creds;
                }

                // Match by capability
                JsonNode capabilities = creds.get("model_capabilities");
                if (capabilities != null && capabilities.isArray()) {
                    for (JsonNode cap : capabilities) {
                        if ("embedding".equalsIgnoreCase(cap.asText())) {
                            capabilityMatch = creds;
                        }
                    }
                }
            }
        }

        return capabilityMatch;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText("") : "";
    }

    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }

    public boolean isAvailable() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }
}
