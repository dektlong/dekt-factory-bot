package org.tanzu.goosechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST API for ingesting documents into the vector store.
 * Endpoints return graceful responses when RAG is not available.
 */
@RestController
@RequestMapping("/api/documents")
@CrossOrigin(origins = "*")
public class DocumentController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingestDocument(@RequestBody IngestRequest request) {
        if (!documentService.isAvailable()) {
            return ResponseEntity.ok(Map.of("success", false, "error", "RAG not available"));
        }
        if (request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        try {
            UUID documentId = documentService.ingestDocument(request.filename(), request.text());
            return ResponseEntity.ok(Map.of(
                    "documentId", documentId.toString(),
                    "filename", request.filename() != null ? request.filename() : "",
                    "success", true));
        } catch (Exception e) {
            logger.error("Document ingestion failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to ingest document: " + e.getMessage(),
                    "success", false));
        }
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable UUID documentId) {
        if (!documentService.isAvailable()) {
            return ResponseEntity.notFound().build();
        }
        documentService.deleteDocument(documentId);
        return ResponseEntity.ok(Map.of("deleted", documentId.toString()));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "ragEnabled", documentService.isAvailable(),
                "hasDocuments", documentService.hasDocuments()));
    }

    public record IngestRequest(String filename, String text) {}
}
