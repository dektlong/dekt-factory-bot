package org.tanzu.goosechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stores document text as chunked vector embeddings in Postgres (pgvector) and
 * retrieves the most relevant chunks for a given query.
 * <p>
 * Created by {@link DocumentServiceConfig}. When DataSource or EmbeddingClient
 * is missing, {@link #isAvailable()} returns false and all operations are no-ops.
 */
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);

    private static final int CHUNK_SIZE = 800;
    private static final int CHUNK_OVERLAP = 200;
    private static final int DEFAULT_TOP_K = 5;

    private final DataSource dataSource;
    private final EmbeddingClient embeddingClient;
    private final boolean available;
    private int vectorDimensions = -1;

    public DocumentService(DataSource dataSource, EmbeddingClient embeddingClient) {
        this.dataSource = dataSource;
        this.embeddingClient = embeddingClient;
        if (dataSource != null && embeddingClient != null) {
            this.available = initSchema();
        } else {
            this.available = false;
        }
    }

    /** Whether both Postgres and embedding model are operational. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Create the pgvector extension and document_chunks table.
     * @return true if schema init succeeded
     */
    private boolean initSchema() {
        try {
            this.vectorDimensions = detectVectorDimensions();
            logger.info("Detected embedding vector dimensions: {}", vectorDimensions);

            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS document_chunks (
                        id UUID PRIMARY KEY,
                        document_id UUID NOT NULL,
                        filename TEXT,
                        chunk_index INT NOT NULL,
                        content TEXT NOT NULL,
                        embedding vector(%d),
                        created_at TIMESTAMPTZ DEFAULT now()
                    )
                    """.formatted(vectorDimensions));
                stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON document_chunks(document_id)
                    """);
            }
            logger.info("Document chunks schema initialized (vector dimension={})", vectorDimensions);
            return true;
        } catch (Exception e) {
            logger.error("Failed to initialize document schema. RAG will not be available.", e);
            return false;
        }
    }

    private int detectVectorDimensions() {
        List<Double> testVector = embeddingClient.embed("dimension test");
        return testVector.size();
    }

    /**
     * Ingest a document: chunk the text, embed each chunk, store in Postgres.
     * @return the generated document ID
     */
    public UUID ingestDocument(String filename, String text) {
        if (!available) throw new IllegalStateException("RAG is not available");

        UUID documentId = UUID.randomUUID();
        List<String> chunks = chunkText(text);
        logger.info("Ingesting document '{}': {} chunks (id={})", filename, chunks.size(), documentId);

        List<List<Double>> embeddings = embeddingClient.embed(chunks);

        String sql = """
            INSERT INTO document_chunks (id, document_id, filename, chunk_index, content, embedding)
            VALUES (?, ?, ?, ?, ?, ?::vector)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < chunks.size(); i++) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, documentId);
                ps.setString(3, filename);
                ps.setInt(4, i);
                ps.setString(5, chunks.get(i));
                ps.setString(6, vectorToString(embeddings.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
            logger.info("Stored {} chunks for document '{}' (id={})", chunks.size(), filename, documentId);
        } catch (SQLException e) {
            logger.error("Failed to store document chunks for '{}'", filename, e);
            throw new RuntimeException("Failed to store document", e);
        }

        return documentId;
    }

    public List<ChunkResult> retrieveRelevantChunks(String query) {
        return retrieveRelevantChunks(query, DEFAULT_TOP_K);
    }

    public List<ChunkResult> retrieveRelevantChunks(String query, int topK) {
        if (!available) return List.of();

        List<Double> queryEmbedding = embeddingClient.embed(query);

        String sql = """
            SELECT content, filename, 1 - (embedding <=> ?::vector) AS similarity
            FROM document_chunks
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """;

        List<ChunkResult> results = new ArrayList<>();
        String vecStr = vectorToString(queryEmbedding);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vecStr);
            ps.setString(2, vecStr);
            ps.setInt(3, topK);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ChunkResult(
                            rs.getString("content"),
                            rs.getString("filename"),
                            rs.getDouble("similarity")));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to retrieve chunks for query", e);
        }

        logger.debug("Retrieved {} chunks for query (top similarity={})",
                results.size(), results.isEmpty() ? "N/A" : String.format("%.4f", results.get(0).similarity()));
        return results;
    }

    public boolean hasDocuments() {
        if (!available) return false;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT EXISTS(SELECT 1 FROM document_chunks LIMIT 1)")) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
    }

    public void deleteDocument(UUID documentId) {
        if (!available) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM document_chunks WHERE document_id = ?")) {
            ps.setObject(1, documentId);
            int deleted = ps.executeUpdate();
            logger.info("Deleted {} chunks for document {}", deleted, documentId);
        } catch (SQLException e) {
            logger.error("Failed to delete document {}", documentId, e);
        }
    }

    // --- Chunking ---

    static List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        String cleanText = text.replaceAll("\\s+", " ").trim();
        int length = cleanText.length();
        int start = 0;

        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            if (end < length) {
                int lastPeriod = cleanText.lastIndexOf(". ", end);
                if (lastPeriod > start + CHUNK_SIZE / 2) {
                    end = lastPeriod + 1;
                }
            }
            String chunk = cleanText.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            start = end - CHUNK_OVERLAP;
            if (start < 0) start = 0;
            if (start >= end) start = end;
        }

        return chunks;
    }

    // --- Helpers ---

    private static String vectorToString(List<Double> vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(vector.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    public record ChunkResult(String content, String filename, double similarity) {}
}
