package com.github.topxiao.sshjdbc.autoconfigure;

import lombok.Data;
import lombok.ToString;

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
 *       password: ${DB_PASSWORD}
 * }</pre>
 */
@Data
public class SshDataSourceProperties {

    /** Named datasource definitions, keyed by datasource name. */
    private Map<String, DataSourceProperties> datasources = new HashMap<>();

    /** Maximum number of cached datasource pools, including named pools. */
    private int maxCachedDatasources = 100;

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
        @ToString.Exclude
        private String password;

        /** JDBC driver class name. */
        @Deprecated(forRemoval = true)
        private String driverClassName = "org.postgresql.Driver";
    }
}
