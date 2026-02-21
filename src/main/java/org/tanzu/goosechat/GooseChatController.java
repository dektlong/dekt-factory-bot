package org.tanzu.goosechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.tanzu.goose.cf.GooseExecutor;
import org.tanzu.goose.cf.GooseExecutionException;
import org.tanzu.goose.cf.GooseOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * REST controller for managing conversational chat sessions with Goose CLI.
 * <p>
 * This controller provides endpoints for:
 * <ul>
 *   <li>Creating new conversation sessions</li>
 *   <li>Sending messages to existing sessions (with streaming responses)</li>
 *   <li>Closing conversation sessions</li>
 *   <li>Checking session status</li>
 * </ul>
 * </p>
 * 
 * <h3>Conversation Sessions</h3>
 * <p>
 * Each chat session maintains conversation context across multiple messages.
 * Sessions use Goose's native named session feature with {@code --name} and 
 * {@code --resume} flags to enable multi-turn conversations where Goose 
 * remembers previous exchanges. Session data is stored in Goose's SQLite 
 * database at {@code ~/.local/share/goose/sessions/sessions.db}.
 * </p>
 * 
 * @see <a href="https://block.github.io/goose/docs/guides/sessions/">Goose Session Management</a>
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = "goose.enabled", havingValue = "true", matchIfMissing = true)
public class GooseChatController {

    private static final Logger logger = LoggerFactory.getLogger(GooseChatController.class);
    private static final String SESSION_PREFIX = "chat-";
    
    private final GooseExecutor executor;
    private final GooseConfigInjector configInjector;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    
    // Session metadata tracking (Goose handles actual session persistence)
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-cleanup");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public GooseChatController(GooseExecutor executor,
                              GooseConfigInjector configInjector,
                              DocumentService documentService) {
        this.executor = executor;
        this.configInjector = configInjector;
        this.documentService = documentService;
        logger.info("GooseChatController initialized (RAG {})", documentService.isAvailable() ? "enabled" : "disabled");
        
        // Schedule periodic session cleanup
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Create a new conversation session.
     * 
     * @param request optional session configuration
     * @return session ID and success status
     */
    @PostMapping("/sessions")
    public ResponseEntity<CreateSessionResponse> createSession(
            @RequestBody(required = false) CreateSessionRequest request) {
        logger.info("Creating new conversation session");
        
        try {
            if (!executor.isAvailable()) {
                logger.error("Goose CLI is not available");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new CreateSessionResponse(null, false, "Goose CLI is not available"));
            }

            // Generate session ID with prefix for Goose named sessions
            // This creates names like "chat-a1b2c3d4" which Goose stores in its SQLite DB
            String sessionId = SESSION_PREFIX + UUID.randomUUID().toString().substring(0, 8);
            
            // Apply custom configuration if provided
            long inactivityTimeoutMinutes = 30; // default
            String provider = null;
            String model = null;
            
            if (request != null) {
                if (request.sessionInactivityTimeoutMinutes() != null) {
                    inactivityTimeoutMinutes = request.sessionInactivityTimeoutMinutes();
                }
                provider = request.provider();
                model = request.model();
            }

            ConversationSession session = new ConversationSession(
                provider,
                model,
                Duration.ofMinutes(inactivityTimeoutMinutes),
                System.currentTimeMillis()
            );
            
            sessions.put(sessionId, session);
            
            logger.info("Created conversation session: {} with provider: {}", sessionId, provider);
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreateSessionResponse(sessionId, true, null));
                
        } catch (Exception e) {
            logger.error("Failed to create conversation session", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CreateSessionResponse(null, false, 
                    "Failed to create session: " + e.getMessage()));
        }
    }

    /**
     * Send a message to an existing session and stream the response via SSE (GET).
     * <p>
     * Uses the native browser EventSource API (GET requests only) which handles
     * proxy buffering and chunked encoding better than fetch-based SSE parsing.
     * This is critical for Cloud Foundry's Go Router.
     * </p>
     * <p>
     * For messages that include large context (e.g. imported PDF text), use
     * {@link #streamMessagePost} instead to avoid URL length limits.
     * </p>
     *
     * @param sessionId the conversation session ID
     * @param message the message to send (URL-encoded)
     * @return SSE stream of response events
     */
    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable String sessionId,
            @RequestParam String message,
            HttpServletResponse response) {
        String effectiveMessage = prependSkillDirectives(message);
        effectiveMessage = prependRagContext(effectiveMessage);
        return streamMessageInternal(sessionId, effectiveMessage, response);
    }

    /**
     * Send a message with optional document context (e.g. imported PDF text) via POST.
     * Use this when the client attaches a document so the combined payload is not
     * limited by URL length.
     *
     * @param sessionId the conversation session ID
     * @param request body with message and optional documentContext
     * @return SSE stream of response events
     */
    @PostMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessagePost(
            @PathVariable String sessionId,
            @RequestBody StreamMessageRequest request,
            HttpServletResponse response) {
        String message = request != null && request.message() != null ? request.message() : "";
        String documentContext = request != null ? request.documentContext() : null;

        // Prepend skill routing directives, then try RAG context
        String effectiveMessage = prependSkillDirectives(message);
        String preRagMessage = effectiveMessage;
        effectiveMessage = prependRagContext(effectiveMessage);

        // If RAG didn't add context and the client sent inline document text, use that
        if (effectiveMessage.equals(preRagMessage)
                && documentContext != null && !documentContext.isBlank()) {
            logger.info("Using inline document context ({} chars) for session message", documentContext.length());
            effectiveMessage = "The user has provided the following document. "
                    + "Use it to answer the question that follows.\n\n"
                    + "--- DOCUMENT START ---\n" + documentContext.trim() + "\n--- DOCUMENT END ---\n\n"
                    + "User question: " + message;
        }

        return streamMessageInternal(sessionId, effectiveMessage, response);
    }

    /**
     * Detect skill-related keywords in the user message and prepend explicit
     * directives telling the LLM to EXECUTE the installed skill rather than
     * provide generic instructions. This compensates for models (e.g. GenAI
     * proxy models) that may not reliably follow multi-step skill workflows
     * without additional prompt reinforcement.
     */
    private String prependSkillDirectives(String userMessage) {
        String lower = userMessage.toLowerCase();
        StringBuilder directives = new StringBuilder();

        if (lower.contains("google chat")) {
            directives.append("SKILL DIRECTIVE: You have the 'google-chat-poster' skill installed. ")
                .append("You MUST use it to actually post to Google Chat — do NOT just explain how. ")
                .append("Load the skill, read GOOGLE_CHAT_SPACES from the environment, ")
                .append("and execute the curl/script commands to post the message. ")
                .append("If the user's prompt contains multiple tasks, aggregate ALL results ")
                .append("into a single formatted Google Chat message before posting.\n\n");
        }

        if (lower.contains("supply chain")) {
            directives.append("SKILL DIRECTIVE: You have the 'supplychain-motivator' skill installed. ")
                .append("You MUST use it to check the daily target and generate a motivation message — ")
                .append("do NOT just describe supply chain concepts.\n\n");
        }

        if (lower.contains("audit") && lower.contains("factory")) {
            directives.append("SKILL DIRECTIVE: You have the 'factory-audit' skill installed. ")
                .append("You MUST use it to perform the factory application audit — ")
                .append("do NOT just explain audit concepts.\n\n");
        }

        if (directives.isEmpty()) {
            return userMessage;
        }

        logger.info("Skill directives prepended for message ({} chars added)", directives.length());
        return directives.toString() + userMessage;
    }

    /**
     * If RAG is available and documents have been ingested, retrieve the most
     * relevant chunks for the user message and prepend them as context.
     */
    private String prependRagContext(String userMessage) {
        if (!documentService.isAvailable() || !documentService.hasDocuments()) {
            return userMessage;
        }
        try {
            var chunks = documentService.retrieveRelevantChunks(userMessage, 5);
            if (chunks.isEmpty()) {
                return userMessage;
            }
            StringBuilder ctx = new StringBuilder();
            ctx.append("Use the following document excerpts to help answer the question. ");
            ctx.append("If the excerpts do not contain relevant information, say so.\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                var c = chunks.get(i);
                ctx.append("--- Excerpt ").append(i + 1);
                if (c.filename() != null && !c.filename().isBlank()) {
                    ctx.append(" (from ").append(c.filename()).append(")");
                }
                ctx.append(" ---\n").append(c.content()).append("\n\n");
            }
            ctx.append("---\n\nUser question: ").append(userMessage);
            logger.info("RAG: prepended {} chunks as context ({} chars)", chunks.size(), ctx.length());
            return ctx.toString();
        } catch (Exception e) {
            logger.warn("RAG retrieval failed, falling back to plain message", e);
            return userMessage;
        }
    }

    /**
     * Internal SSE streaming: send effective message to session and stream response.
     */
    private SseEmitter streamMessageInternal(String sessionId, String message, HttpServletResponse response) {
        logger.info("Streaming message to session {}: {} chars", sessionId, message.length());

        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout

        executorService.execute(() -> {
            Stream<String> jsonStream = null;
            ScheduledFuture<?> heartbeat = null;
            final AtomicBoolean streamCompleted = new AtomicBoolean(false);
            try {
                if (!executor.isAvailable()) {
                    logger.error("Goose CLI is not available");
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Goose CLI is not available"));
                    emitter.complete();
                    return;
                }

                ConversationSession session = sessions.get(sessionId);
                if (session == null || !isSessionActive(sessionId)) {
                    logger.error("Session {} is not active or does not exist", sessionId);
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Session not found or has expired"));
                    emitter.complete();
                    return;
                }

                // Update last activity
                session.updateLastActivity();

                // Send initial status event to confirm processing started
                emitter.send(SseEmitter.event()
                    .name("status")
                    .data("Processing your request..."));

                // Build options for this session
                GooseOptions options = buildGooseOptions(session);

                // Inject OAuth tokens for authenticated MCP servers before execution
                configInjector.injectOAuthTokens(sessionId);

                // Token batching to work around proxy buffering (e.g., Cloud Foundry Go Router)
                final int[] tokenCount = {0};
                final StringBuilder tokenBatch = new StringBuilder();
                final long[] lastSendTime = {System.currentTimeMillis()};
                final int BATCH_SIZE_THRESHOLD = 10;
                final long BATCH_TIME_THRESHOLD_MS = 100;

                // Heartbeat: send periodic keepalive SSE events to prevent proxy
                // idle-timeout disconnects (e.g. CF Go Router) during the ENTIRE
                // streaming session. Multi-skill prompts can cause long silent
                // periods while Goose executes tools, during which no SSE data
                // flows and proxies may drop the idle connection.
                final long[] lastDataSentTime = {System.currentTimeMillis()};
                final long HEARTBEAT_INTERVAL_MS = 8_000;
                final long IDLE_THRESHOLD_MS = 6_000;
                heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
                    if (streamCompleted.get()) {
                        return;
                    }
                    long timeSinceLastData = System.currentTimeMillis() - lastDataSentTime[0];
                    if (timeSinceLastData >= IDLE_THRESHOLD_MS) {
                        try {
                            emitter.send(SseEmitter.event()
                                .name("heartbeat")
                                .data("waiting"));
                            lastDataSentTime[0] = System.currentTimeMillis();
                            logger.debug("Session {} heartbeat sent (idle for {}ms)", sessionId, timeSinceLastData);
                        } catch (IOException e) {
                            logger.debug("Session {} heartbeat send failed (client disconnected?)", sessionId);
                        }
                    }
                }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

                // Server-side retry for cold-start: on a fresh container Goose must
                // download skills from GitHub, initialize MCP servers, and compile
                // extensions. The first several invocations often produce 0 tokens
                // or throw execution errors while this completes. Retry internally
                // on the SAME SSE connection — no client reconnect needed.
                final int MAX_COLD_START_RETRIES = 5;
                final long[] COLD_START_RETRY_DELAYS = {5_000, 8_000, 10_000, 15_000, 15_000};

                for (int attempt = 0; attempt <= MAX_COLD_START_RETRIES; attempt++) {
                    if (attempt > 0) {
                        // Close previous stream before retrying
                        if (jsonStream != null) {
                            jsonStream.close();
                            jsonStream = null;
                        }

                        long delay = COLD_START_RETRY_DELAYS[Math.min(attempt - 1, COLD_START_RETRY_DELAYS.length - 1)];
                        logger.info("Session {} cold-start server-retry {}/{} after {}ms",
                            sessionId, attempt, MAX_COLD_START_RETRIES, delay);
                        Thread.sleep(delay);

                        emitter.send(SseEmitter.event()
                            .name("status")
                            .data("Initializing AI agent... (attempt " + (attempt + 1) + ")"));
                        lastDataSentTime[0] = System.currentTimeMillis();

                        configInjector.injectOAuthTokens(sessionId);

                        // Reset counters for new attempt
                        tokenCount[0] = 0;
                        tokenBatch.setLength(0);
                        lastSendTime[0] = System.currentTimeMillis();
                    }

                    try {
                        boolean isFirstMessage = session.messageCount() == 0;
                        jsonStream = executor.executeInSessionStreamingJson(
                            sessionId, message, !isFirstMessage, options
                        );

                        jsonStream.forEach(jsonLine -> {
                            lastDataSentTime[0] = System.currentTimeMillis();
                            try {
                                JsonNode event = objectMapper.readTree(jsonLine);
                                String eventType = event.has("type") ? event.get("type").asText() : "";

                                switch (eventType) {
                                    case "message" -> {
                                        String activityJson = extractToolActivityFromMessage(event, sessionId);
                                        if (activityJson != null) {
                                            emitter.send(SseEmitter.event()
                                                .name("activity")
                                                .data(activityJson));
                                        }

                                        String token = extractTextFromMessage(event);
                                        if (token != null) {
                                            tokenBatch.append(token);
                                            tokenCount[0]++;

                                            long now = System.currentTimeMillis();
                                            boolean shouldFlush = tokenCount[0] % BATCH_SIZE_THRESHOLD == 0
                                                || (now - lastSendTime[0]) > BATCH_TIME_THRESHOLD_MS
                                                || token.contains("\n");

                                            if (shouldFlush && !tokenBatch.isEmpty()) {
                                                String tokenJson = objectMapper.writeValueAsString(tokenBatch.toString());
                                                emitter.send(SseEmitter.event()
                                                    .name("token")
                                                    .data(tokenJson));
                                                tokenBatch.setLength(0);
                                                lastSendTime[0] = now;
                                            }
                                        }
                                    }
                                    case "notification" -> {
                                        String activityJson = formatNotificationActivity(event, sessionId);
                                        if (activityJson != null) {
                                            emitter.send(SseEmitter.event()
                                                .name("activity")
                                                .data(activityJson));
                                        }
                                    }
                                    case "complete" -> {
                                        if (!tokenBatch.isEmpty()) {
                                            String tokenJson = objectMapper.writeValueAsString(tokenBatch.toString());
                                            emitter.send(SseEmitter.event()
                                                .name("token")
                                                .data(tokenJson));
                                            tokenBatch.setLength(0);
                                        }
                                        processCompleteEvent(jsonLine, emitter, sessionId);
                                    }
                                }
                            } catch (IOException e) {
                                logger.error("Error sending SSE event for session {}", sessionId, e);
                                throw new RuntimeException("SSE send failed", e);
                            } catch (Exception e) {
                                logger.warn("Failed to parse JSON event for session {}: {}", sessionId, jsonLine, e);
                            }
                        });

                        // Flush any remaining tokens after stream ends
                        if (!tokenBatch.isEmpty()) {
                            try {
                                String tokenJson = objectMapper.writeValueAsString(tokenBatch.toString());
                                emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(tokenJson));
                            } catch (IOException e) {
                                logger.error("Error flushing final tokens for session {}", sessionId, e);
                            }
                        }
                    } catch (GooseExecutionException e) {
                        // During cold-start, Goose may fail while initializing
                        // extensions/skills — treat as retriable if we have attempts left
                        logger.warn("Session {} Goose execution failed on attempt {} — {}",
                            sessionId, attempt + 1, e.getMessage());
                        tokenCount[0] = 0;
                        tokenBatch.setLength(0);
                    } catch (RuntimeException e) {
                        if (e.getMessage() != null && e.getMessage().contains("SSE send failed")) {
                            throw e; // Client disconnected — no point retrying
                        }
                        logger.warn("Session {} unexpected error on attempt {} — {}",
                            sessionId, attempt + 1, e.getMessage());
                        tokenCount[0] = 0;
                        tokenBatch.setLength(0);
                    }

                    logger.info("Session {} attempt {} produced {} tokens", sessionId, attempt + 1, tokenCount[0]);

                    // If we got tokens, or this is not a cold-start (messageCount > 0), stop retrying
                    if (tokenCount[0] > 0 || session.messageCount() > 0) {
                        break;
                    }

                    if (attempt < MAX_COLD_START_RETRIES) {
                        logger.warn("Session {} produced 0 tokens on attempt {} — will retry server-side",
                            sessionId, attempt + 1);
                    }
                }

                // Signal heartbeat to stop
                streamCompleted.set(true);

                // Finalize: send SSE complete (with tokens) or retry (no visible text)
                if (tokenCount[0] > 0) {
                    session.incrementMessageCount();
                    emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(String.valueOf(tokenCount[0])));
                    emitter.complete();
                } else {
                    logger.warn("Session {} produced 0 tokens after {} server-side retries — signalling client retry",
                        sessionId, MAX_COLD_START_RETRIES + 1);
                    emitter.send(SseEmitter.event()
                        .name("retry")
                        .data("Empty response from Goose — MCP servers may still be initializing"));
                    emitter.complete();
                }
                logger.info("Streaming message completed for session {}", sessionId);
                    
            } catch (GooseExecutionException e) {
                streamCompleted.set(true);
                logger.error("Goose execution failed for session {}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Execution failed: " + e.getMessage()));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
            } catch (Exception e) {
                streamCompleted.set(true);
                logger.error("Unexpected error during message send to session {}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("An unexpected error occurred"));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
            } finally {
                // Stop heartbeat keepalive
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                }
                // Ensure stream is closed to clean up subprocess
                if (jsonStream != null) {
                    jsonStream.close();
                }
            }
        });

        emitter.onTimeout(() -> {
            logger.warn("SSE connection timed out for session {}", sessionId);
            emitter.complete();
        });

        emitter.onError((e) -> {
            logger.error("SSE error for session {}", sessionId, e);
        });

        return emitter;
    }

    /**
     * Extract text content from a message event.
     */
    private String extractTextFromMessage(JsonNode event) {
        JsonNode content = event.at("/message/content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                // Look for text content items
                String contentType = item.has("type") ? item.get("type").asText() : "";
                if ("text".equals(contentType) && item.has("text")) {
                    String text = item.get("text").asText();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Extract tool request activity from a message event.
     * Tool requests appear as content items with toolRequest type.
     * 
     * JSON structure from Goose:
     * {
     *   "type": "toolRequest",
     *   "id": "tool123",
     *   "toolCall": {
     *     "status": "success",
     *     "value": { "name": "extension__tool", "arguments": {...} }
     *   }
     * }
     * 
     * @return JSON string for the activity event, or null if no tool activity
     */
    private String extractToolActivityFromMessage(JsonNode event, String sessionId) {
        JsonNode content = event.at("/message/content");
        if (!content.isArray()) {
            return null;
        }
        
        for (JsonNode item : content) {
            String contentType = item.has("type") ? item.get("type").asText() : "";
            
            // Handle tool requests (Goose uses camelCase: "toolRequest")
            if ("tool_request".equals(contentType) || "toolRequest".equals(contentType)) {
                try {
                    String id = item.has("id") ? item.get("id").asText() : UUID.randomUUID().toString();
                    JsonNode toolCall = item.has("toolCall") ? item.get("toolCall") : item.get("tool_call");
                    
                    String toolName = "unknown";
                    JsonNode arguments = objectMapper.createObjectNode();
                    
                    if (toolCall != null) {
                        // The toolCall has a nested structure: { "status": "...", "value": { "name": "...", "arguments": {...} } }
                        JsonNode value = toolCall.has("value") ? toolCall.get("value") : toolCall;
                        
                        if (value != null) {
                            toolName = value.has("name") ? value.get("name").asText() : "unknown";
                            arguments = value.has("arguments") ? value.get("arguments") : arguments;
                        }
                    }
                    
                    // Parse extension from tool name (format: extension__tool or extension/tool)
                    var parsedTool = parseToolName(toolName);
                    String extensionId = parsedTool.extensionId();
                    String shortToolName = parsedTool.toolName();
                    
                    // Build activity JSON
                    var activityNode = objectMapper.createObjectNode();
                    activityNode.put("id", id);
                    activityNode.put("type", "tool_request");
                    activityNode.put("toolName", shortToolName);
                    activityNode.put("extensionId", extensionId);
                    activityNode.put("status", "running");
                    activityNode.put("timestamp", System.currentTimeMillis());
                    activityNode.set("arguments", arguments);
                    
                    return objectMapper.writeValueAsString(activityNode);
                } catch (Exception e) {
                    logger.warn("Failed to extract tool activity for session {}", sessionId, e);
                }
            }
            
            // Handle tool responses
            if ("tool_response".equals(contentType) || "toolResponse".equals(contentType)) {
                try {
                    String id = item.has("id") ? item.get("id").asText() : "";
                    boolean isError = item.has("is_error") && item.get("is_error").asBoolean();
                    
                    var activityNode = objectMapper.createObjectNode();
                    activityNode.put("id", id);
                    activityNode.put("type", "tool_response");
                    activityNode.put("status", isError ? "error" : "completed");
                    activityNode.put("timestamp", System.currentTimeMillis());
                    
                    return objectMapper.writeValueAsString(activityNode);
                } catch (Exception e) {
                    logger.warn("Failed to extract tool response for session {}", sessionId, e);
                }
            }
        }
        return null;
    }

    /**
     * Format a notification event as an activity JSON.
     */
    private String formatNotificationActivity(JsonNode event, String sessionId) {
        try {
            String extensionId = event.has("extension_id") ? event.get("extension_id").asText() : "";
            JsonNode data = event.get("data");
            
            if (data == null) {
                return null;
            }
            
            var activityNode = objectMapper.createObjectNode();
            activityNode.put("id", UUID.randomUUID().toString());
            activityNode.put("type", "notification");
            activityNode.put("extensionId", extensionId);
            activityNode.put("timestamp", System.currentTimeMillis());
            activityNode.put("status", "info");
            
            // Extract message from notification data
            if (data.has("log") && data.get("log").has("message")) {
                activityNode.put("message", data.get("log").get("message").asText());
            } else if (data.has("message")) {
                activityNode.put("message", data.get("message").asText());
            } else if (data.has("progress")) {
                JsonNode progress = data.get("progress");
                double progressValue = progress.has("progress") ? progress.get("progress").asDouble() : 0;
                String progressMsg = progress.has("message") ? progress.get("message").asText() : "";
                activityNode.put("message", String.format("%.0f%% %s", progressValue * 100, progressMsg));
            } else {
                // Fallback: stringify the data
                activityNode.put("message", data.toString());
            }
            
            logger.debug("Session {} notification from {}: {}", sessionId, extensionId, 
                activityNode.has("message") ? activityNode.get("message").asText() : "");
            
            return objectMapper.writeValueAsString(activityNode);
        } catch (Exception e) {
            logger.warn("Failed to format notification for session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Log the Goose "complete" event but do NOT send SSE complete to the client.
     * The post-loop code decides whether to send SSE complete (tokens produced)
     * or SSE retry (zero tokens, e.g. tool-only response or cold-start).
     */
    private void processCompleteEvent(String jsonLine, SseEmitter emitter, String sessionId) throws IOException {
        try {
            JsonNode event = objectMapper.readTree(jsonLine);
            int totalTokens = event.has("total_tokens") ? event.get("total_tokens").asInt() : 0;
            logger.info("Session {} Goose reported completion with {} total LLM tokens", sessionId, totalTokens);
        } catch (Exception e) {
            logger.warn("Failed to parse complete event for session {}: {}", sessionId, jsonLine, e);
        }
    }

    /**
     * Close a conversation session.
     * 
     * @param sessionId the session to close
     * @return success status
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<CloseSessionResponse> closeSession(@PathVariable String sessionId) {
        logger.info("Closing conversation session: {}", sessionId);
        
        try {
            ConversationSession removed = sessions.remove(sessionId);
            if (removed != null) {
                logger.info("Session {} closed successfully", sessionId);
            }
            return ResponseEntity.ok(
                new CloseSessionResponse(true, "Session closed successfully")
            );
        } catch (Exception e) {
            logger.error("Failed to close session {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CloseSessionResponse(false, 
                    "Failed to close session: " + e.getMessage()));
        }
    }

    /**
     * Check if a session is active.
     * 
     * @param sessionId the session to check
     * @return session status
     */
    @GetMapping("/sessions/{sessionId}/status")
    public ResponseEntity<SessionStatusResponse> getSessionStatus(@PathVariable String sessionId) {
        logger.debug("Checking status for session: {}", sessionId);
        
        try {
            boolean active = isSessionActive(sessionId);
            return ResponseEntity.ok(new SessionStatusResponse(sessionId, active));
        } catch (Exception e) {
            logger.error("Failed to check session status for {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new SessionStatusResponse(sessionId, false));
        }
    }

    /**
     * Check if a session is active (exists and hasn't timed out).
     */
    private boolean isSessionActive(String sessionId) {
        ConversationSession session = sessions.get(sessionId);
        if (session == null) {
            return false;
        }
        
        long timeSinceLastActivity = System.currentTimeMillis() - session.lastActivity();
        return timeSinceLastActivity < session.inactivityTimeout().toMillis();
    }

    /**
     * Build GooseOptions for a session.
     * <p>
     * Provider/model configuration is handled via environment variables
     * set by the buildpack at container startup (OPENAI_API_KEY, OPENAI_HOST,
     * GOOSE_PROVIDER, GOOSE_MODEL).
     * </p>
     * <p>
     * <strong>Important:</strong> When using GenAI services (detected by OPENAI_HOST
     * and OPENAI_API_KEY being set), we must pass the API key and base URL explicitly
     * to GooseOptions so that the SSE normalizing proxy is activated. The proxy fixes
     * SSE format incompatibilities between GenAI proxies and Goose CLI.
     * </p>
     * <p>
     * Priority:
     * <ol>
     *   <li>Session-specified provider/model (if provided)</li>
     *   <li>Environment variables (set by buildpack from GenAI service or config)</li>
     * </ol>
     * </p>
     */
    private GooseOptions buildGooseOptions(ConversationSession session) {
        GooseOptions.Builder optionsBuilder = GooseOptions.builder()
            .timeout(Duration.ofMinutes(10));

        // Apply session-specific configuration if provided
        if (session.provider() != null && !session.provider().isEmpty()) {
            optionsBuilder.provider(session.provider());
        }
        if (session.model() != null && !session.model().isEmpty()) {
            optionsBuilder.model(session.model());
        }

        // Pass OPENAI_API_KEY and OPENAI_HOST to GooseOptions so that the
        // SSE normalizing proxy is activated. This is required because GenAI
        // proxies return SSE in "data:{...}" format but Goose expects "data: {...}"
        String openaiApiKey = System.getenv("OPENAI_API_KEY");
        String openaiHost = System.getenv("OPENAI_HOST");
        
        if (openaiApiKey != null && !openaiApiKey.isEmpty() &&
            openaiHost != null && !openaiHost.isEmpty()) {
            logger.debug("Enabling SSE proxy for GenAI: host={}", openaiHost);
            optionsBuilder.apiKey(openaiApiKey);
            optionsBuilder.baseUrl(openaiHost);
        }

        return optionsBuilder.build();
    }

    /**
     * Clean up expired sessions.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            ConversationSession session = entry.getValue();
            boolean expired = (now - session.lastActivity()) >= session.inactivityTimeout().toMillis();
            if (expired) {
                logger.info("Cleaning up expired session: {}", entry.getKey());
            }
            return expired;
        });
    }

    // Request/Response records

    /** Request body for POST stream: message and optional document context (e.g. PDF text). */
    public record StreamMessageRequest(String message, String documentContext) {}

    public record CreateSessionRequest(
        String provider,      // anthropic, openai, google, databricks, ollama
        String model,
        Integer sessionInactivityTimeoutMinutes
    ) {}

    public record CreateSessionResponse(
        String sessionId,
        boolean success,
        String message
    ) {}

    public record CloseSessionResponse(
        boolean success,
        String message
    ) {}

    public record SessionStatusResponse(
        String sessionId,
        boolean active
    ) {}

    /**
     * Parsed tool name containing the extension ID and tool name components.
     */
    private record ParsedToolName(String extensionId, String toolName) {}

    /**
     * Parse a tool name into extension ID and short tool name.
     * Handles formats: "extension__tool" or "extension/tool"
     */
    private ParsedToolName parseToolName(String fullToolName) {
        String delimiter = fullToolName.contains("__") ? "__" :
                          fullToolName.contains("/") ? "/" : null;

        if (delimiter == null) {
            return new ParsedToolName("", fullToolName);
        }

        String[] parts = fullToolName.split(delimiter, 2);
        return new ParsedToolName(parts[0], parts.length > 1 ? parts[1] : fullToolName);
    }

    /**
     * Internal session tracking class.
     */
    private static class ConversationSession {
        private final String provider;
        private final String model;
        private final Duration inactivityTimeout;
        private volatile long lastActivity;
        private volatile int messageCount;

        ConversationSession(String provider, String model, 
                           Duration inactivityTimeout, long createdAt) {
            this.provider = provider;
            this.model = model;
            this.inactivityTimeout = inactivityTimeout;
            this.lastActivity = createdAt;
            this.messageCount = 0;
        }

        String provider() { return provider; }
        String model() { return model; }
        Duration inactivityTimeout() { return inactivityTimeout; }
        long lastActivity() { return lastActivity; }
        int messageCount() { return messageCount; }
        
        void updateLastActivity() { 
            this.lastActivity = System.currentTimeMillis(); 
        }
        
        void incrementMessageCount() {
            this.messageCount++;
        }
    }
}

