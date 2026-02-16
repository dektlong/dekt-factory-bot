package org.tanzu.goosechat;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when embedding credentials can be resolved --
 * either from VCAP_SERVICES (GenAI embedding service) or from EMBEDDING_HOST / EMBEDDING_API_KEY env vars.
 */
public class EmbeddingCredentialsAvailableCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        try {
            EmbeddingProperties props = new EmbeddingProperties();
            return props.isAvailable();
        } catch (Exception e) {
            return false;
        }
    }
}
