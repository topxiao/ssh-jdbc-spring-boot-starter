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
    private final Map<ConnectionInfo, SshJdbcTemplate> connectionTemplates = new ConcurrentHashMap<>();
    private final Map<String, ConnectionInfo> connectionInfos = new ConcurrentHashMap<>();
    private final Set<String> providerManagedNames = ConcurrentHashMap.newKeySet();
    private final Map<SshJdbcTemplate, ConnectionInfo> templateConnectionInfos =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private final SshTunnelService tunnelService;
    private final DataSourceCustomizer customizer;
    private final ConnectionInfoProvider provider;
    private final int maxCachedDatasources;

    /** Basic constructor — supports named register/get only. */
    public SshJdbcRegistry() {
        this(null, null, null, 100);
    }

    /** Full constructor — supports all dynamic operations. */
    public SshJdbcRegistry(SshTunnelService tunnelService,
                           DataSourceCustomizer customizer,
                           ConnectionInfoProvider provider) {
        this(tunnelService, customizer, provider, 100);
    }

    public SshJdbcRegistry(SshTunnelService tunnelService,
                           DataSourceCustomizer customizer,
                           ConnectionInfoProvider provider,
                           int maxCachedDatasources) {
        this.tunnelService = tunnelService;
        this.customizer = customizer;
        this.provider = provider;
        if (maxCachedDatasources <= 0) {
            throw new IllegalArgumentException("maxCachedDatasources must be greater than zero");
        }
        this.maxCachedDatasources = maxCachedDatasources;
    }

    // ---- Named access (existing, backward-compatible) ----

    public void register(String name, SshJdbcTemplate template) {
        synchronized (templates) {
            if (!templates.containsKey(name) && !containsTemplate(template)) {
                ensureCapacity();
            }
            SshJdbcTemplate old = templates.put(name, template);
            ConnectionInfo oldInfo = connectionInfos.remove(name);
            if (oldInfo != null && old != null) {
                connectionTemplates.remove(oldInfo, old);
            }
            if (old != null && old != template) {
                closeDataSource(old);
            }
        }
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
            ensureCapacityForNewName(name);
            // Build first so a failed replacement leaves the current datasource usable.
            SshJdbcTemplate template = createTemplate(name, info);
            SshJdbcTemplate old = templates.put(name, template);
            ConnectionInfo oldInfo = connectionInfos.put(name, info);
            connectionTemplates.put(info, template);
            if (old != null && old != template) {
                if (oldInfo != null) {
                    connectionTemplates.remove(oldInfo, old);
                }
                closeDataSource(old);
            }
            log.info("动态注册数据源: {}", name);
        }
    }

    /** Register a datasource owned by ConnectionInfoProvider refresh lifecycle. */
    public void registerProviderManaged(String name, ConnectionInfo info) {
        register(name, info);
        providerManagedNames.add(name);
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
                connectionTemplates.remove(info, removed);
            }
            providerManagedNames.remove(name);
            closeDataSource(removed);
            log.info("动态注销数据源: {}", name);
        }
    }

    // ---- Cache-based lookup ----

    /** Get or create a template by the complete ConnectionInfo, including credentials. */
    public SshJdbcTemplate getOrCreate(ConnectionInfo info) {
        requireTunnelService("getOrCreate");
        SshJdbcTemplate existing = connectionTemplates.get(info);
        if (existing != null) {
            return existing;
        }

        synchronized (templates) {
            existing = connectionTemplates.get(info);
            if (existing != null) {
                return existing;
            }
            ensureCapacity();
            SshJdbcTemplate template = createTemplate(info.cacheKey(), info);
            connectionTemplates.put(info, template);
            log.debug("按需创建模板: {}", info.cacheKey());
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
        Set<SshJdbcTemplate> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(templates.values());
        unique.addAll(connectionTemplates.values());
        for (SshJdbcTemplate template : unique) {
            closeDataSource(template);
        }
        templates.clear();
        connectionTemplates.clear();
        connectionInfos.clear();
        providerManagedNames.clear();
        templateConnectionInfos.clear();
        SshJdbc.clear(this);
        log.info("SshJdbcRegistry 已关闭");
    }

    // ---- Internal helpers ----

    private void registerInternal(String name, ConnectionInfo info) {
        ensureCapacityForNewName(name);
        SshJdbcTemplate template = createTemplate(name, info);
        SshJdbcTemplate old = templates.put(name, template);
        ConnectionInfo oldInfo = connectionInfos.put(name, info);
        connectionTemplates.put(info, template);
        if (old != null && old != template) {
            if (oldInfo != null) {
                connectionTemplates.remove(oldInfo, old);
            }
            closeDataSource(old);
        }
        log.info("注册数据源: {}", name);
    }

    private void unregisterInternal(String name) {
        SshJdbcTemplate removed = templates.remove(name);
        if (removed != null) {
            closeDataSource(removed);
            ConnectionInfo info = connectionInfos.remove(name);
            if (info != null) {
                connectionTemplates.remove(info, removed);
            }
        }
        providerManagedNames.remove(name);
        log.info("注销数据源: {}", name);
    }

    private SshJdbcTemplate createTemplate(String name, ConnectionInfo info) {
        boolean tunnelAcquired = false;
        try {
            int localPort = tunnelService.acquireTunnel(info.host(), info.port());
            tunnelAcquired = true;
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
            SshJdbcTemplate template = new SshJdbcTemplate(namedTemplate);
            templateConnectionInfos.put(template, info);
            return template;

        } catch (Exception e) {
            if (tunnelAcquired) {
                tunnelService.releaseTunnel(info.host(), info.port());
            }
            throw new RuntimeException(
                    "创建 SshJdbcTemplate 失败 (数据源: " + name + "): " + e.getMessage(), e);
        }
    }

    private void closeDataSource(SshJdbcTemplate template) {
        try {
            if (template == null || template.getJdbcTemplate() == null) {
                return;
            }
            DataSource ds = template.getJdbcTemplate().getDataSource();
            if (ds instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("关闭 DataSource 失败", e);
        } finally {
            ConnectionInfo info = templateConnectionInfos.remove(template);
            if (info != null && tunnelService != null) {
                tunnelService.releaseTunnel(info.host(), info.port());
            }
        }
    }

    private void requireTunnelService(String operation) {
        if (tunnelService == null) {
            throw new IllegalStateException(
                "Registry 未配置 SshTunnelService，不支持 " + operation + " 操作");
        }
    }

    private void ensureCapacityForNewName(String name) {
        if (!templates.containsKey(name)) {
            ensureCapacity();
        }
    }

    private boolean containsTemplate(SshJdbcTemplate candidate) {
        for (SshJdbcTemplate template : templates.values()) {
            if (template == candidate) {
                return true;
            }
        }
        for (SshJdbcTemplate template : connectionTemplates.values()) {
            if (template == candidate) {
                return true;
            }
        }
        return false;
    }

    private void ensureCapacity() {
        Set<SshJdbcTemplate> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        unique.addAll(templates.values());
        unique.addAll(connectionTemplates.values());
        if (unique.size() >= maxCachedDatasources) {
            throw new IllegalStateException(
                    "已达到最大 DataSource 缓存数: " + maxCachedDatasources);
        }
    }
}
