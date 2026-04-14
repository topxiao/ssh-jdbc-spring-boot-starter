package com.github.topxiao.sshjdbc.provider;

/**
 * Immutable data carrier for a remote PostgreSQL connection.
 *
 * @param host     remote database host
 * @param port     remote database port
 * @param database database name
 * @param username login user
 * @param password login password
 */
public record ConnectionInfo(
        String host,
        int port,
        String database,
        String username,
        String password
) {
    /**
     * Key used to cache tunnel / template instances.  Intentionally excludes password
     * so that two connections differing only in password still share the same tunnel.
     */
    public String cacheKey() {
        return host + ":" + port + ":" + database + ":" + username;
    }

    /** Standard JDBC URL pointing at the remote host. */
    public String jdbcUrl() {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database;
    }

    /** JDBC URL that points at localhost through an SSH tunnel on the given local port. */
    public String jdbcUrlWithLocalPort(int localPort) {
        return "jdbc:postgresql://localhost:" + localPort + "/" + database;
    }
}
