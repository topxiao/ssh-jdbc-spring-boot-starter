package com.github.topxiao.sshjdbc.autoconfigure;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for data sources accessed through SSH tunnels.
 *
 * <p>Binds to the {@code ssh-jdbc.datasources} prefix in application configuration.
 * Each named entry under {@code datasources} defines a separate database connection.
 *
 * <p>Example configuration:
 * <pre>{@code
 * ssh-jdbc:
 *   datasources:
 *     primary:
 *       host: 10.0.1.100
 *       port: 5432
 *       database: mydb
 *       username: postgres
 *       password: secret
 * }</pre>
 */
@Data
public class SshDataSourceProperties {

    /** Named datasource definitions, keyed by datasource name. */
    private Map<String, DataSourceProperties> datasources = new HashMap<>();

    /**
     * Properties for a single datasource.
     */
    @Data
    public static class DataSourceProperties {
        /** Database host (remote host accessible through the SSH tunnel). */
        private String host;

        /** Database port (default 5432 for PostgreSQL). */
        private int port = 5432;

        /** Database name. */
        private String database;

        /** Database username. */
        private String username;

        /** Database password. */
        private String password;

        /** JDBC driver class name. */
        private String driverClassName = "org.postgresql.Driver";
    }
}
