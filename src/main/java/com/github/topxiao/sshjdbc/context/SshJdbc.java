package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Static facade that resolves the current {@link ExecutionContext} into a
 * datasource and delegates SQL operations.
 *
 * <p>Resolution chain:
 * <ol>
 *   <li>If context has full connection params → create ConnectionInfo directly</li>
 *   <li>Otherwise, try each registered {@link ConnectionInfoResolver}</li>
 * </ol>
 */
@Slf4j
public class SshJdbc {

    private static volatile SshJdbcRegistry registry;
    private static volatile List<ConnectionInfoResolver> resolvers = List.of();

    /** Initialize with registry and resolvers. Called by auto-configuration. */
    public static void init(SshJdbcRegistry registry, List<ConnectionInfoResolver> resolvers) {
        SshJdbc.registry = registry;
        SshJdbc.resolvers = resolvers != null ? resolvers : List.of();
        log.info("SshJdbc 静态门面已初始化, {} 个 Resolver", SshJdbc.resolvers.size());
    }

    /** Reset state (for testing). */
    static void reset() {
        registry = null;
        resolvers = List.of();
    }

    // ---- Resolution ----

    /** Resolve a template from the current ExecutionContext. */
    public static SshJdbcTemplate resolveTemplate() {
        ExecutionContext ctx = ExecutionContext.current();
        if (ctx == null) {
            throw new IllegalStateException("ExecutionContext 未设置，请先调用 ExecutionContext.builder()...apply()");
        }

        ConnectionInfo info = resolveConnectionInfo(ctx);
        return registry.getOrCreate(info);
    }

    /** Get template by datasource name (delegates to registry). */
    public static SshJdbcTemplate getTemplate(String name) {
        return registry.getTemplate(name);
    }

    // ---- Static query methods ----

    public static List<Map<String, Object>> queryForList(String sql, Map<String, ?> params) {
        return resolveTemplate().queryForList(sql, params);
    }

    public static Map<String, Object> queryForMap(String sql, Map<String, ?> params) {
        return resolveTemplate().queryForMap(sql, params);
    }

    public static <T> T queryForObject(String sql, Map<String, ?> params, Class<T> requiredType) {
        return resolveTemplate().queryForObject(sql, params, requiredType);
    }

    public static int update(String sql, Map<String, ?> params) {
        return resolveTemplate().update(sql, params);
    }

    @SafeVarargs
    public static final int[] batchUpdate(String sql, Map<String, ?>... batchArgs) {
        return resolveTemplate().batchUpdate(sql, batchArgs);
    }

    public static void execute(String sql) {
        resolveTemplate().execute(sql);
    }

    // ---- Internal ----

    private static ConnectionInfo resolveConnectionInfo(ExecutionContext ctx) {
        // 1. Full params in context
        if (ctx.hasFullConnectionInfo()) {
            return new ConnectionInfo(
                    ctx.getDbHost(), ctx.getDbPort(), ctx.getDbDatabase(),
                    ctx.getDbUser(), ctx.getDbPassword());
        }

        // 2. Try resolvers
        for (ConnectionInfoResolver resolver : resolvers) {
            ConnectionInfo info = resolver.resolve(ctx);
            if (info != null) {
                return info;
            }
        }

        throw new IllegalStateException(
            "无法从 ExecutionContext 解析数据源连接信息。"
            + "corpCode=" + ctx.getCorpCode()
            + ", attributes=" + ctx.getAttributes());
    }
}
