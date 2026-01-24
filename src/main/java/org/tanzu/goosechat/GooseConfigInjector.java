package org.tanzu.goosechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.tanzu.goose.cf.GooseConfiguration;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.McpServerInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to inject OAuth tokens into Goose configuration before execution.
 * <p>
 * This component modifies the config.yaml file to include Authorization headers
 * for MCP servers that the user has authenticated with.
 * </p>
 */
@Component
public class GooseConfigInjector {

    private static final Logger logger = LoggerFactory.getLogger(GooseConfigInjector.class);

    private final GooseExecutor executor;
    private final McpOAuthController oauthController;
    
    // Track which sessions have tokens injected to avoid duplicate work
    private final Map<String, Long> lastInjectionTime = new ConcurrentHashMap<>();

    public GooseConfigInjector(GooseExecutor executor, McpOAuthController oauthController) {
        this.executor = executor;
        this.oauthController = oauthController;
    }

    /**
     * Inject OAuth tokens for authenticated MCP servers into the Goose config.
     * <p>
     * This modifies the config.yaml to include Authorization headers for servers
     * that the user (identified by sessionId) has authenticated with.
     * </p>
     *
     * @param sessionId the user's session ID
     */
    public void injectOAuthTokens(String sessionId) {
        try {
            GooseConfiguration config = executor.getConfiguration();
            Path configPath = findConfigPath();
            
            if (configPath == null || !Files.exists(configPath)) {
                logger.warn("Config file not found, cannot inject OAuth tokens");
                return;
            }

            String configContent = Files.readString(configPath);
            boolean modified = false;

            for (McpServerInfo server : config.mcpServers()) {
                if (!server.requiresAuth()) {
                    continue;
                }

                Optional<String> accessToken = oauthController.getAccessToken(server.name(), sessionId);
                if (accessToken.isEmpty()) {
                    logger.debug("No OAuth token for server {} in session {}", server.name(), sessionId);
                    continue;
                }

                String token = accessToken.get();
                logger.info("Injecting OAuth token for server {} in session {}", server.name(), sessionId);

                // Check if we need to add or update the Authorization header
                configContent = injectAuthorizationHeader(configContent, server.name(), token);
                modified = true;
            }

            if (modified) {
                Files.writeString(configPath, configContent);
                lastInjectionTime.put(sessionId, System.currentTimeMillis());
                logger.info("Updated config.yaml with OAuth tokens for session {}", sessionId);
            }

        } catch (IOException e) {
            logger.error("Failed to inject OAuth tokens into config", e);
        }
    }

    /**
     * Inject or update the Authorization header for a specific server in the config.
     * <p>
     * Uses line-by-line processing for more robust YAML manipulation.
     * </p>
     */
    private String injectAuthorizationHeader(String configContent, String serverName, String token) {
        String[] lines = configContent.split("\n");
        StringBuilder result = new StringBuilder();
        
        boolean inTargetServer = false;
        boolean foundHeaders = false;
        boolean injectedAuth = false;
        int serverIndent = -1;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            int currentIndent = line.length() - line.stripLeading().length();
            
            // Check if we're entering the target server block
            if (trimmed.equals(serverName + ":") && currentIndent == 2) {
                inTargetServer = true;
                serverIndent = currentIndent;
                result.append(line).append("\n");
                continue;
            }
            
            // Check if we're leaving the server block (new server at same indent)
            if (inTargetServer && currentIndent == serverIndent && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                // Before leaving, inject headers if we haven't yet
                if (!injectedAuth) {
                    result.append("    headers:\n");
                    result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
                    injectedAuth = true;
                }
                inTargetServer = false;
            }
            
            if (inTargetServer) {
                // Check for headers section
                if (trimmed.equals("headers:") && currentIndent == 4) {
                    foundHeaders = true;
                    result.append(line).append("\n");
                    
                    // Look ahead to see if Authorization already exists
                    boolean authExists = false;
                    for (int j = i + 1; j < lines.length; j++) {
                        String nextLine = lines[j].trim();
                        int nextIndent = lines[j].length() - lines[j].stripLeading().length();
                        if (nextIndent <= 4 && !nextLine.isEmpty()) break;
                        if (nextLine.startsWith("Authorization:")) {
                            authExists = true;
                            break;
                        }
                    }
                    
                    if (!authExists) {
                        // Add Authorization header right after headers:
                        result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
                        injectedAuth = true;
                    }
                    continue;
                }
                
                // Update existing Authorization header
                if (foundHeaders && trimmed.startsWith("Authorization:") && currentIndent == 6) {
                    result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
                    injectedAuth = true;
                    continue;
                }
            }
            
            result.append(line).append("\n");
        }
        
        // If we never found headers section and we're still in the target server at EOF
        if (inTargetServer && !injectedAuth) {
            result.append("    headers:\n");
            result.append("      Authorization: \"Bearer ").append(token).append("\"\n");
        }
        
        return result.toString();
    }

    /**
     * Find the Goose config.yaml path.
     */
    private Path findConfigPath() {
        String home = System.getenv("HOME");
        if (home != null) {
            Path configPath = Paths.get(home, ".config", "goose", "config.yaml");
            if (Files.exists(configPath)) {
                return configPath;
            }
        }
        
        // Cloud Foundry path
        Path cfPath = Paths.get("/home/vcap/app/.config/goose/config.yaml");
        if (Files.exists(cfPath)) {
            return cfPath;
        }
        
        return null;
    }

    /**
     * Remove OAuth tokens from the config when a session ends or user disconnects.
     */
    public void removeOAuthTokens(String sessionId, String serverName) {
        // For now, tokens remain in config until manually removed
        // A more sophisticated implementation would track per-session configs
        logger.debug("Token removal requested for server {} session {}", serverName, sessionId);
    }
}
