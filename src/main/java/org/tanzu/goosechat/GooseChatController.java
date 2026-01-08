package org.tanzu.goosechat;

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
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    
    // Session metadata tracking (Goose handles actual session persistence)
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-cleanup");
        t.setDaemon(true);
        return t;
    });

    public GooseChatController(GooseExecutor executor) {
        this.executor = executor;
        logger.info("GooseChatController initialized with Goose native session support");
        
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
                sessionId,
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
     * Send a message to an existing session and stream the response via SSE.
     * 
     * @param sessionId the conversation session ID
     * @param request the message to send
     * @return SSE stream of response chunks
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(
            @PathVariable String sessionId,
            @RequestBody SendMessageRequest request) {
        logger.info("Sending message to session {}: {} chars", sessionId, request.message().length());
        
        SseEmitter emitter = new SseEmitter(600_000L); // 10 minutes timeout
        
        executorService.execute(() -> {
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
                // Note: The wrapper automatically reads GOOSE_PROVIDER and GOOSE_MODEL
                // from environment variables if not specified here
                GooseOptions.Builder optionsBuilder = GooseOptions.builder()
                    .timeout(Duration.ofMinutes(10));
                
                // Override provider/model if session specifies them
                if (session.provider() != null && !session.provider().isEmpty()) {
                    optionsBuilder.provider(session.provider());
                }
                if (session.model() != null && !session.model().isEmpty()) {
                    optionsBuilder.model(session.model());
                }

                GooseOptions options = optionsBuilder.build();

                // Execute Goose with heartbeat support using native session management
                // First message starts new session, subsequent messages resume
                try {
                    String prompt = request.message();
                    boolean isFirstMessage = session.messageCount() == 0;
                    
                    // Use Goose's native --name and --resume flags for session continuity
                    // See: https://block.github.io/goose/docs/guides/sessions/session-management/
                    Future<String> future = executorService.submit(() -> 
                        executor.executeInSession(sessionId, prompt, !isFirstMessage, options)
                    );

                    // Send heartbeat messages every 30 seconds while waiting for response
                    long heartbeatIntervalMs = 30_000L;
                    String response;
                    
                    while (true) {
                        try {
                            response = future.get(heartbeatIntervalMs, TimeUnit.MILLISECONDS);
                            break;
                        } catch (TimeoutException e) {
                            // Still waiting for Goose - send heartbeat to keep connection alive
                            emitter.send(SseEmitter.event()
                                .name("heartbeat")
                                .data("Still processing..."));
                            logger.info("Sent heartbeat for session {}", sessionId);
                        }
                    }
                    
                    // Split response into lines for streaming effect
                    String[] lines = response.split("\n");
                    for (String line : lines) {
                        emitter.send(SseEmitter.event()
                            .name("message")
                            .data(line));
                    }
                    
                    // Increment message count
                    session.incrementMessageCount();
                    
                    emitter.complete();
                    logger.info("Message sent successfully to session {}", sessionId);
                    
                } catch (GooseExecutionException e) {
                    logger.error("Goose execution failed for session {}", sessionId, e);
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("Execution failed: " + e.getMessage()));
                    emitter.completeWithError(e);
                }
            } catch (Exception e) {
                logger.error("Unexpected error during message send to session {}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event()
                        .name("error")
                        .data("An unexpected error occurred"));
                } catch (IOException ex) {
                    logger.error("Failed to send error event", ex);
                }
                emitter.completeWithError(e);
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

    public record SendMessageRequest(String message) {}

    public record CloseSessionResponse(
        boolean success,
        String message
    ) {}

    public record SessionStatusResponse(
        String sessionId,
        boolean active
    ) {}

    /**
     * Internal session tracking record.
     */
    private static class ConversationSession {
        private final String id;
        private final String provider;
        private final String model;
        private final Duration inactivityTimeout;
        private volatile long lastActivity;
        private volatile int messageCount;

        ConversationSession(String id, String provider, String model, 
                           Duration inactivityTimeout, long createdAt) {
            this.id = id;
            this.provider = provider;
            this.model = model;
            this.inactivityTimeout = inactivityTimeout;
            this.lastActivity = createdAt;
            this.messageCount = 0;
        }

        String id() { return id; }
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

