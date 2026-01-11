package org.tanzu.goosechat;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/diagnostics")
@CrossOrigin(origins = "*")
public class DiagnosticsController {

    private static final List<String> RELEVANT_PREFIXES = List.of(
        "GOOSE", "ANTHROPIC", "OPENAI", "GOOGLE", "DATABRICKS", "OLLAMA"
    );
    private static final List<String> EXACT_MATCHES = List.of("PATH", "HOME");
    private static final List<String> SENSITIVE_PATTERNS = List.of("API_KEY", "TOKEN");

    @GetMapping("/env")
    public Map<String, String> getEnvironment() {
        Map<String, String> filtered = new TreeMap<>();

        System.getenv().forEach((key, value) -> {
            if (isRelevantEnvVar(key)) {
                filtered.put(key, maskIfSensitive(key, value));
            }
        });

        return filtered;
    }

    private boolean isRelevantEnvVar(String key) {
        return EXACT_MATCHES.contains(key) ||
               RELEVANT_PREFIXES.stream().anyMatch(key::contains);
    }

    private String maskIfSensitive(String key, String value) {
        boolean isSensitive = SENSITIVE_PATTERNS.stream().anyMatch(key::contains);
        if (isSensitive && value != null && value.length() > 10) {
            return value.substring(0, 10) + "..." + value.substring(value.length() - 4);
        }
        return value;
    }
}

