package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import com.github.topxiao.sshjdbc.provider.CurrentConnectionInfoProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

/**
 * Static facade that resolves the current {@link ExecutionContext} into a
 * datasource and delegates SQL operations.
 *
 * <p>Resolution chain:
 * <ol>
 *   <li>Ask the application's {@link CurrentConnectionInfoProvider}, if configured</li>
 *   <li>If the starter context has full connection params, create ConnectionInfo directly</li>
 *   <li>Otherwise, try each registered {@link ConnectionInfoResolver}</li>
 * </ol>
 */
@Slf4j
public class SshJdbc {

    private static volatile SshJdbcRegistry registry;
    private static volatile List<ConnectionInfoResolver> resolvers = List.of();
    private static volatile CurrentConnectionInfoProvider currentConnectionInfoProvider;

    /** Initialize with registry and resolvers. Called by auto-configuration. */
    public static void init(SshJdbcRegistry registry, List<ConnectionInfoResolver> resolvers) {
        init(registry, resolvers, null);
    }

    /** Initialize with registry, legacy resolvers and an application context adapter. */
    public static void init(SshJdbcRegistry registry, List<ConnectionInfoResolver> resolvers,
                            CurrentConnectionInfoProvider currentConnectionInfoProvider) {
        SshJdbc.registry = registry;
        SshJdbc.resolvers = resolvers != null ? resolvers : List.of();
        SshJdbc.currentConnectionInfoProvider = currentConnectionInfoProvider;
        log.info("SshJdbc 静态门面已初始化, {} 个 Resolver, application provider={}",
                SshJdbc.resolvers.size(), currentConnectionInfoProvider != null);
    }

    /** Reset state (for testing). */
    static void reset() {
        registry = null;
        resolvers = List.of();
        currentConnectionInfoProvider = null;
    }

    // ---- Resolution ----

    /** Resolve a template from the application provider or legacy ExecutionContext chain. */
    public static SshJdbcTemplate resolveTemplate() {
        requireInitialized();
        if (currentConnectionInfoProvider != null) {
            ConnectionInfo info = currentConnectionInfoProvider.getCurrent();
            if (info != null) {
                return registry.getOrCreate(info);
            }
        }

        ExecutionContext ctx = ExecutionContext.current();
        if (ctx == null) {
            throw new IllegalStateException("ExecutionContext 未设置，请先调用 ExecutionContext.builder()...apply()");
        }

        ConnectionInfo info = resolveConnectionInfo(ctx);
        return registry.getOrCreate(info);
    }

    /** Get template by datasource name (delegates to registry). */
    public static SshJdbcTemplate getTemplate(String name) {
        requireInitialized();
        return registry.getTemplate(name);
    }

    static void clear(SshJdbcRegistry expectedRegistry) {
        if (registry == expectedRegistry) {
            reset();
        }
    }

    /** Resolve the template for the current application execution. */
    public static SshJdbcTemplate getTemplate() {
        return resolveTemplate();
    }

    /** Resolve and expose the underlying Spring JdbcTemplate. */
    public static JdbcTemplate getJdbcTemplate() {
        return resolveTemplate().getJdbcTemplate();
    }

    // ---- Static query methods ----

    public static List<Map<String, Object>> queryForList(String sql, Map<String, ?> params) {
        return resolveTemplate().queryForList(sql, params);
    }

    public static List<Map<String, Object>> queryForList(String sql) {
        return resolveTemplate().queryForList(sql);
    }

    public static <T> List<T> queryForList(String sql, Class<T> elementType) {
        return getJdbcTemplate().queryForList(sql, elementType);
    }

    public static <T> List<T> queryForList(String sql, Map<String, ?> params, Class<T> elementType) {
        return resolveTemplate().queryForList(sql, params, elementType);
    }

    public static <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        return getJdbcTemplate().query(sql, rowMapper);
    }

    public static <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> rowMapper) {
        return resolveTemplate().query(sql, params, rowMapper);
    }

    public static Map<String, Object> queryForMap(String sql) {
        return getJdbcTemplate().queryForMap(sql);
    }

    public static Map<String, Object> queryForMap(String sql, Map<String, ?> params) {
        return resolveTemplate().queryForMap(sql, params);
    }

    public static <T> T queryForObject(String sql, Map<String, ?> params, Class<T> requiredType) {
        return resolveTemplate().queryForObject(sql, params, requiredType);
    }

    public static <T> T queryForObject(String sql, Class<T> requiredType) {
        return getJdbcTemplate().queryForObject(sql, requiredType);
    }

    public static int update(String sql, Map<String, ?> params) {
        return resolveTemplate().update(sql, params);
    }

    public static int update(String sql) {
        return resolveTemplate().update(sql);
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
            + ", attributeKeys=" + ctx.getAttributes().keySet());
    }

    private static void requireInitialized() {
        if (registry == null) {
            throw new IllegalStateException("SshJdbc 未初始化，请确认 starter 自动配置已启用");
        }
    }
}
