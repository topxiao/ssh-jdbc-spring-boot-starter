package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.DataSourceCustomizer;
import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import com.github.topxiao.sshjdbc.provider.ConnectionInfoProvider;
import com.github.topxiao.sshjdbc.tunnel.SshTunnelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that indexes {@link SshJdbcTemplate} instances by datasource name
 * and supports dynamic registration, unregistration, cache-based lookup,
 * and provider refresh.
 */
@Slf4j
public class SshJdbcRegistry {

    private final Map<String, SshJdbcTemplate> templates = new ConcurrentHashMap<>();
    private final Map<String, SshJdbcTemplate> cacheKeyTemplates = new ConcurrentHashMap<>();
    private final Map<String, ConnectionInfo> connectionInfos = new ConcurrentHashMap<>();
    private final Set<String> providerManagedNames = ConcurrentHashMap.newKeySet();

    private final SshTunnelService tunnelService;
    private final DataSourceCustomizer customizer;
    private final ConnectionInfoProvider provider;

    /** Basic constructor — supports named register/get only. */
    public SshJdbcRegistry() {
        this(null, null, null);
    }

    /** Full constructor — supports all dynamic operations. */
    public SshJdbcRegistry(SshTunnelService tunnelService,
                           DataSourceCustomizer customizer,
                           ConnectionInfoProvider provider) {
        this.tunnelService = tunnelService;
        this.customizer = customizer;
        this.provider = provider;
    }

    // ---- Named access (existing, backward-compatible) ----

    public void register(String name, SshJdbcTemplate template) {
        templates.put(name, template);
    }

    public SshJdbcTemplate getTemplate(String datasourceName) {
        SshJdbcTemplate template = templates.get(datasourceName);
        if (template == null) {
            throw new IllegalArgumentException(
                "未找到数据源: " + datasourceName
                + "，可用数据源: " + templates.keySet());
        }
        return template;
    }

    public Set<String> getDatasourceNames() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    public Map<String, SshJdbcTemplate> getTemplates() {
        return Collections.unmodifiableMap(templates);
    }

    // ---- Dynamic registration ----

    /** Register a datasource by ConnectionInfo — auto-creates tunnel + template. */
    public void register(String name, ConnectionInfo info) {
        requireTunnelService("register");
        synchronized (templates) {
            // Close old if replacing
            SshJdbcTemplate old = templates.get(name);
            if (old != null) {
                closeDataSource(old);
                ConnectionInfo oldInfo = connectionInfos.get(name);
                if (oldInfo != null) {
                    cacheKeyTemplates.remove(oldInfo.cacheKey());
                }
            }

            SshJdbcTemplate template = createTemplate(name, info);
            templates.put(name, template);
            cacheKeyTemplates.put(info.cacheKey(), template);
            connectionInfos.put(name, info);
            log.info("动态注册数据源: {}", name);
        }
    }

    /** Remove a datasource by name, closing its DataSource. */
    public void unregister(String name) {
        synchronized (templates) {
            SshJdbcTemplate removed = templates.remove(name);
            if (removed == null) {
                throw new IllegalArgumentException("未找到数据源: " + name);
            }
            ConnectionInfo info = connectionInfos.remove(name);
            if (info != null) {
                cacheKeyTemplates.remove(info.cacheKey());
            }
            providerManagedNames.remove(name);
            closeDataSource(removed);
            log.info("动态注销数据源: {}", name);
        }
    }

    // ---- Cache-based lookup ----

    /** Get or create a template by ConnectionInfo, cached by cacheKey. */
    public SshJdbcTemplate getOrCreate(ConnectionInfo info) {
        requireTunnelService("getOrCreate");
        String cacheKey = info.cacheKey();

        SshJdbcTemplate existing = cacheKeyTemplates.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        synchronized (templates) {
            existing = cacheKeyTemplates.get(cacheKey);
            if (existing != null) {
                return existing;
            }
            SshJdbcTemplate template = createTemplate(cacheKey, info);
            cacheKeyTemplates.put(cacheKey, template);
            log.debug("按需创建模板: {}", cacheKey);
            return template;
        }
    }

    // ---- Provider refresh ----

    /** Re-invoke ConnectionInfoProvider and diff-add-remove datasources. */
    public void refresh() {
        if (provider == null) {
            throw new IllegalStateException(
                "未配置 ConnectionInfoProvider，无法刷新");
        }

        Map<String, ConnectionInfo> latest = provider.provide();
        if (latest == null) {
            latest = Map.of();
        }

        synchronized (templates) {
            // Determine which provider-managed names to remove
            Set<String> toRemove = new HashSet<>(providerManagedNames);
            toRemove.removeAll(latest.keySet());

            for (String name : toRemove) {
                unregisterInternal(name);
            }

            // Add or update
            for (Map.Entry<String, ConnectionInfo> entry : latest.entrySet()) {
                String name = entry.getKey();
                ConnectionInfo newInfo = entry.getValue();
                ConnectionInfo existingInfo = connectionInfos.get(name);

                if (existingInfo == null || !existingInfo.equals(newInfo)) {
                    registerInternal(name, newInfo);
                }
                providerManagedNames.add(name);
            }
        }

        log.info("数据源刷新完成: 当前 {} 个 (provider 管理 {} 个)",
                templates.size(), providerManagedNames.size());
    }

    // ---- Lifecycle ----

    /** Close all DataSources on shutdown. */
    public void shutdown() {
        for (SshJdbcTemplate template : templates.values()) {
            closeDataSource(template);
        }
        for (SshJdbcTemplate template : cacheKeyTemplates.values()) {
            closeDataSource(template);
        }
        templates.clear();
        cacheKeyTemplates.clear();
        connectionInfos.clear();
        providerManagedNames.clear();
        log.info("SshJdbcRegistry 已关闭");
    }

    // ---- Internal helpers ----

    private void registerInternal(String name, ConnectionInfo info) {
        SshJdbcTemplate old = templates.get(name);
        if (old != null) {
            closeDataSource(old);
            ConnectionInfo oldInfo = connectionInfos.get(name);
            if (oldInfo != null) {
                cacheKeyTemplates.remove(oldInfo.cacheKey());
            }
        }
        SshJdbcTemplate template = createTemplate(name, info);
        templates.put(name, template);
        cacheKeyTemplates.put(info.cacheKey(), template);
        connectionInfos.put(name, info);
        log.info("注册数据源: {}", name);
    }

    private void unregisterInternal(String name) {
        SshJdbcTemplate removed = templates.remove(name);
        if (removed != null) {
            closeDataSource(removed);
            ConnectionInfo info = connectionInfos.remove(name);
            if (info != null) {
                cacheKeyTemplates.remove(info.cacheKey());
            }
        }
        providerManagedNames.remove(name);
        log.info("注销数据源: {}", name);
    }

    private SshJdbcTemplate createTemplate(String name, ConnectionInfo info) {
        try {
            int localPort = tunnelService.createOrGetTunnel(info.host(), info.port());
            String jdbcUrl = info.jdbcUrlWithLocalPort(localPort);

            DataSourceBuilder<?> builder = DataSourceBuilder.create()
                    .url(jdbcUrl)
                    .username(info.username())
                    .password(info.password())
                    .driverClassName("org.postgresql.Driver");

            DataSource dataSource;
            if (customizer != null) {
                dataSource = customizer.customize(builder, name);
            } else {
                dataSource = builder.build();
            }

            NamedParameterJdbcTemplate namedTemplate =
                    new NamedParameterJdbcTemplate(dataSource);
            return new SshJdbcTemplate(namedTemplate);

        } catch (Exception e) {
            throw new RuntimeException(
                    "创建 SshJdbcTemplate 失败 (数据源: " + name + "): " + e.getMessage(), e);
        }
    }

    private void closeDataSource(SshJdbcTemplate template) {
        try {
            DataSource ds = template.getJdbcTemplate().getDataSource();
            if (ds instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("关闭 DataSource 失败", e);
        }
    }

    private void requireTunnelService(String operation) {
        if (tunnelService == null) {
            throw new IllegalStateException(
                "Registry 未配置 SshTunnelService，不支持 " + operation + " 操作");
        }
    }
}
