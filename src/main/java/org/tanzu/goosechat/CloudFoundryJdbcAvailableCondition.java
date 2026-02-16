package org.tanzu.goosechat;

import io.pivotal.cfenv.jdbc.CfJdbcEnv;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when VCAP_SERVICES contains a JDBC/PostgreSQL service (so a DataSource can be created).
 */
public class CloudFoundryJdbcAvailableCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        try {
            CfJdbcEnv cfJdbcEnv = new CfJdbcEnv();
            return cfJdbcEnv.findJdbcService() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
