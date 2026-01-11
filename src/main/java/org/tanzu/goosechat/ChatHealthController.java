package org.tanzu.goosechat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tanzu.goose.cf.GooseExecutor;

import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatHealthController {

    private final Optional<GooseExecutor> executor;

    public ChatHealthController(@Autowired(required = false) GooseExecutor executor) {
        this.executor = Optional.ofNullable(executor);
    }

    @GetMapping("/health")
    public HealthResponse health() {
        if (executor.isEmpty()) {
            return new HealthResponse(
                false, 
                "not configured", 
                getConfiguredProvider(),
                getConfiguredModel(),
                "Goose CLI is not configured. Please ensure GOOSE_CLI_PATH and an LLM provider API key are set."
            );
        }
        
        GooseExecutor exec = executor.get();
        boolean available = exec.isAvailable();
        String version = available ? exec.getVersion() : "unavailable";
        String message = available ? "Goose CLI is ready" : "Goose CLI binary not found or not configured";
        
        return new HealthResponse(
            available, 
            version, 
            getConfiguredProvider(),
            getConfiguredModel(),
            message
        );
    }

    private String getConfiguredProvider() {
        return getEnvOrElse("GOOSE_PROVIDER__TYPE",
               getEnvOrElse("GOOSE_PROVIDER",
               inferProviderFromApiKeys()));
    }

    private String inferProviderFromApiKeys() {
        if (isEnvSet("ANTHROPIC_API_KEY")) return "anthropic";
        if (isEnvSet("OPENAI_API_KEY")) return "openai";
        if (isEnvSet("GOOGLE_API_KEY")) return "google";
        if (isEnvSet("DATABRICKS_HOST")) return "databricks";
        if (isEnvSet("OLLAMA_HOST")) return "ollama";
        return "unknown";
    }

    private String getConfiguredModel() {
        return getEnvOrElse("GOOSE_PROVIDER__MODEL",
               getEnvOrElse("GOOSE_MODEL", "default"));
    }

    private String getEnvOrElse(String name, String fallback) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : fallback;
    }

    private boolean isEnvSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty();
    }

    public record HealthResponse(
        boolean available, 
        String version, 
        String provider,
        String model,
        String message
    ) {}
}

