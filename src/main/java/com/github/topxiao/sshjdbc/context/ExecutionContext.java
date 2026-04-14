package com.github.topxiao.sshjdbc.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * ThreadLocal context carrying datasource resolution parameters.
 *
 * <p>Can hold either logical identifiers (corpCode + attributes) for
 * resolver-based lookup, or full connection parameters for direct use.
 */
public class ExecutionContext {

    private static final ThreadLocal<ExecutionContext> HOLDER = new ThreadLocal<>();

    // Logical identifiers (for ConnectionInfoResolver)
    private String corpCode;
    private Map<String, String> attributes;

    // Full connection parameters (direct use, bypasses resolver)
    private String dbHost;
    private Integer dbPort;
    private String dbDatabase;
    private String dbUser;
    private String dbPassword;

    private ExecutionContext() {
        this.attributes = new HashMap<>();
    }

    public static ExecutionContext current() {
        return HOLDER.get();
    }

    public static void set(ExecutionContext ctx) {
        HOLDER.set(ctx);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasFullConnectionInfo() {
        return dbHost != null && dbPort != null && dbDatabase != null
                && dbUser != null && dbPassword != null;
    }

    public String getCorpCode() { return corpCode; }
    public String getAttribute(String key) { return attributes.get(key); }
    public Map<String, String> getAttributes() { return Collections.unmodifiableMap(attributes); }
    public String getDbHost() { return dbHost; }
    public Integer getDbPort() { return dbPort; }
    public String getDbDatabase() { return dbDatabase; }
    public String getDbUser() { return dbUser; }
    public String getDbPassword() { return dbPassword; }

    public static class Builder {
        private final ExecutionContext ctx = new ExecutionContext();

        public Builder corpCode(String corpCode) {
            ctx.corpCode = corpCode;
            return this;
        }

        public Builder put(String key, String value) {
            ctx.attributes.put(key, value);
            return this;
        }

        public Builder dbHost(String dbHost) {
            ctx.dbHost = dbHost;
            return this;
        }

        public Builder dbPort(int dbPort) {
            ctx.dbPort = dbPort;
            return this;
        }

        public Builder dbDatabase(String dbDatabase) {
            ctx.dbDatabase = dbDatabase;
            return this;
        }

        public Builder dbUser(String dbUser) {
            ctx.dbUser = dbUser;
            return this;
        }

        public Builder dbPassword(String dbPassword) {
            ctx.dbPassword = dbPassword;
            return this;
        }

        /** Build without setting ThreadLocal. */
        public ExecutionContext build() {
            return ctx;
        }

        /** Build and set as current ThreadLocal context. */
        public ExecutionContext apply() {
            HOLDER.set(ctx);
            return ctx;
        }
    }
}
