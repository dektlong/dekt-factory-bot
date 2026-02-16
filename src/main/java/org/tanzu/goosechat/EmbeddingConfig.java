package org.tanzu.goosechat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(name = "app.embedding.enabled", havingValue = "true")
    @Conditional(EmbeddingCredentialsAvailableCondition.class)
    public EmbeddingProperties embeddingProperties() {
        return new EmbeddingProperties();
    }
}
