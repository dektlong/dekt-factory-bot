package org.tanzu.goosechat;

import com.zaxxer.hikari.HikariDataSource;
import io.pivotal.cfenv.jdbc.CfJdbcEnv;
import io.pivotal.cfenv.jdbc.CfJdbcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Creates a DataSource from a bound PostgreSQL (or JDBC) Cloud Foundry service.
 * Only active when VCAP_SERVICES contains a matching service.
 */
@Configuration
public class CloudFoundryDataSourceConfig {

    private static final Logger logger = LoggerFactory.getLogger(CloudFoundryDataSourceConfig.class);

    @Bean
    @Conditional(CloudFoundryJdbcAvailableCondition.class)
    public DataSource dataSource() {
        CfJdbcEnv cfJdbcEnv = new CfJdbcEnv();
        CfJdbcService jdbcService = cfJdbcEnv.findJdbcService();
        if (jdbcService == null) {
            throw new IllegalStateException("Condition required JDBC service but none found");
        }
        logger.info("Configuring DataSource from bound JDBC/Postgres service");

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcService.getJdbcUrl());
        ds.setUsername(jdbcService.getUsername());
        ds.setPassword(jdbcService.getPassword());
        String driver = jdbcService.getDriverClassName();
        ds.setDriverClassName(driver != null && !driver.isBlank() ? driver : "org.postgresql.Driver");
        return ds;
    }
}
