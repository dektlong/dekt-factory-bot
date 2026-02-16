package org.tanzu.goosechat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Creates DocumentService only when both a DataSource and EmbeddingClient
 * actually exist at runtime. Uses @Autowired(required=false) instead of
 * @ConditionalOnBean to avoid bean-ordering issues.
 */
@Configuration
public class DocumentServiceConfig {

    private static final Logger logger = LoggerFactory.getLogger(DocumentServiceConfig.class);

    @Bean
    public DocumentService documentService(
            @Autowired(required = false) DataSource dataSource,
            @Autowired(required = false) EmbeddingClient embeddingClient) {
        if (dataSource == null || embeddingClient == null) {
            logger.info("RAG disabled (DataSource={}, EmbeddingClient={})",
                    dataSource != null ? "present" : "missing",
                    embeddingClient != null ? "present" : "missing");
            return new DocumentService(null, null);
        }
        return new DocumentService(dataSource, embeddingClient);
    }
}
