# 运行时动态数据源 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 ssh-jdbc-spring-boot-starter 添加运行时动态数据源管理，支持上下文驱动自动解析、动态注册/注销、Provider 刷新。

**Architecture:** 三层架构 — Layer 3 (ExecutionContext + SshJdbc 静态门面) → Layer 2 (SshJdbcRegistry 动态注册表增强) → Layer 1 (SshTunnelService 不变)。从底层向上构建，每个 Task 产出可独立测试的组件。

**Tech Stack:** Java 21, Spring Boot 3.5.4, JUnit 5, Mockito, ConcurrentHashMap

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `context/ExecutionContext.java` | 新建 | ThreadLocal 上下文，携带逻辑标识或完整连接参数 |
| `context/ConnectionInfoResolver.java` | 新建 | 上下文 → ConnectionInfo 解析接口 |
| `context/SshJdbcRegistry.java` | 修改 | 增加 unregister / register(ConnectionInfo) / getOrCreate / refresh |
| `context/SshJdbc.java` | 新建 | 静态门面，从上下文自动解析并执行 SQL |
| `autoconfigure/SshJdbcAutoConfiguration.java` | 修改 | 注入新组件、初始化 SshJdbc 静态引用 |
| `context/ExecutionContextTest.java` | 新建 | ExecutionContext 单元测试 |
| `context/SshJdbcRegistryTest.java` | 修改 | Registry 新功能测试 |
| `context/SshJdbcTest.java` | 新建 | SshJdbc 静态门面测试 |
| `autoconfigure/SshJdbcAutoConfigurationTest.java` | 修改 | AutoConfiguration 新功能测试 |

---

### Task 1: ExecutionContext

**Files:**
- Create: `src/main/java/com/github/topxiao/sshjdbc/context/ExecutionContext.java`
- Create: `src/test/java/com/github/topxiao/sshjdbc/context/ExecutionContextTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/github/topxiao/sshjdbc/context/ExecutionContextTest.java`:

```java
package com.github.topxiao.sshjdbc.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {

    @AfterEach
    void tearDown() {
        ExecutionContext.clear();
    }

    @Test
    void shouldSetAndGetContext() {
        ExecutionContext ctx = ExecutionContext.builder()
                .corpCode("midea")
                .apply();
        assertEquals("midea", ExecutionContext.current().getCorpCode());
        assertSame(ctx, ExecutionContext.current());
    }

    @Test
    void shouldClearContext() {
        ExecutionContext.builder().corpCode("midea").apply();
        ExecutionContext.clear();
        assertNull(ExecutionContext.current());
    }

    @Test
    void shouldReturnNullWhenNoContextSet() {
        assertNull(ExecutionContext.current());
    }

    @Test
    void shouldStoreAndRetrieveAttributes() {
        ExecutionContext.builder()
                .corpCode("midea")
                .put("env", "v4")
                .apply();
        assertEquals("v4", ExecutionContext.current().getAttribute("env"));
        assertNull(ExecutionContext.current().getAttribute("nonexistent"));
    }

    @Test
    void shouldDetectFullConnectionInfo() {
        ExecutionContext ctx = ExecutionContext.builder()
                .dbHost("10.0.1.100")
                .dbPort(5432)
                .dbDatabase("mydb")
                .dbUser("postgres")
                .dbPassword("secret")
                .build();
        assertTrue(ctx.hasFullConnectionInfo());
    }

    @Test
    void shouldNotHaveFullConnectionInfoWhenPartial() {
        ExecutionContext ctx = ExecutionContext.builder()
                .corpCode("midea")
                .put("env", "v4")
                .build();
        assertFalse(ctx.hasFullConnectionInfo());
    }

    @Test
    void shouldNotHaveFullConnectionInfoWhenMissingDbHost() {
        ExecutionContext ctx = ExecutionContext.builder()
                .dbPort(5432)
                .dbDatabase("mydb")
                .dbUser("postgres")
                .dbPassword("secret")
                .build();
        assertFalse(ctx.hasFullConnectionInfo());
    }

    @Test
    void shouldBuildWithAllDbParams() {
        ExecutionContext ctx = ExecutionContext.builder()
                .corpCode("midea")
                .put("env", "v4")
                .dbHost("10.0.1.100")
                .dbPort(5432)
                .dbDatabase("mydb")
                .dbUser("postgres")
                .dbPassword("secret")
                .apply();
        assertEquals("10.0.1.100", ctx.getDbHost());
        assertEquals(5432, ctx.getDbPort());
        assertEquals("mydb", ctx.getDbDatabase());
        assertEquals("postgres", ctx.getDbUser());
        assertEquals("secret", ctx.getDbPassword());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=ExecutionContextTest -pl .`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

Create `src/main/java/com/github/topxiao/sshjdbc/context/ExecutionContext.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=ExecutionContextTest -pl .`
Expected: 8 tests PASS

- [ ] **Step 5: Commit**

```bash
cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter
git add src/main/java/com/github/topxiao/sshjdbc/context/ExecutionContext.java src/test/java/com/github/topxiao/sshjdbc/context/ExecutionContextTest.java
git commit -m "feat: add ExecutionContext with ThreadLocal context for dynamic datasource resolution"
```

---

### Task 2: ConnectionInfoResolver

**Files:**
- Create: `src/main/java/com/github/topxiao/sshjdbc/context/ConnectionInfoResolver.java`

- [ ] **Step 1: Write the interface**

Create `src/main/java/com/github/topxiao/sshjdbc/context/ConnectionInfoResolver.java`:

```java
package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.provider.ConnectionInfo;

/**
 * Strategy interface for resolving {@link ConnectionInfo} from an
 * {@link ExecutionContext}.
 *
 * <p>Implementations are discovered as Spring beans. Multiple resolvers
 * are tried in {@code @Order} sequence; the first non-null result wins.
 *
 * <p>This is a {@link FunctionalInterface} so it can be supplied as a lambda.
 */
@FunctionalInterface
public interface ConnectionInfoResolver {

    /**
     * Resolve connection info from the given context.
     *
     * @param ctx the current execution context
     * @return resolved ConnectionInfo, or {@code null} if this resolver
     *         cannot handle the context
     */
    ConnectionInfo resolve(ExecutionContext ctx);
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn compile -pl .`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter
git add src/main/java/com/github/topxiao/sshjdbc/context/ConnectionInfoResolver.java
git commit -m "feat: add ConnectionInfoResolver interface for context-driven datasource resolution"
```

---

### Task 3: SshJdbcRegistry Enhancement

**Files:**
- Modify: `src/main/java/com/github/topxiao/sshjdbc/context/SshJdbcRegistry.java`
- Modify: `src/test/java/com/github/topxiao/sshjdbc/context/SshJdbcRegistryTest.java`

- [ ] **Step 1: Write the failing tests**

Add these tests to `src/test/java/com/github/topxiao/sshjdbc/context/SshJdbcRegistryTest.java`:

```java
package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.autoconfigure.SshTunnelProperties;
import com.github.topxiao.sshjdbc.jdbc.DataSourceCustomizer;
import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import com.github.topxiao.sshjdbc.provider.ConnectionInfoProvider;
import com.github.topxiao.sshjdbc.tunnel.SshTunnelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshJdbcRegistryTest {

    private SshJdbcRegistry registry;
    private SshJdbcTemplate primaryTemplate;
    private SshJdbcTemplate secondaryTemplate;
    private SshTunnelService tunnelService;
    private ConnectionInfoProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        tunnelService = mock(SshTunnelService.class);
        when(tunnelService.createOrGetTunnel(anyString(), anyInt())).thenReturn(15432);
        provider = mock(ConnectionInfoProvider.class);

        registry = new SshJdbcRegistry(tunnelService, null, provider);
        primaryTemplate = new SshJdbcTemplate(mock(NamedParameterJdbcTemplate.class));
        secondaryTemplate = new SshJdbcTemplate(mock(NamedParameterJdbcTemplate.class));
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    // ---- Existing tests (adapted) ----

    @Test
    void shouldRegisterAndGetTemplate() {
        registry.register("primary", primaryTemplate);
        assertEquals(primaryTemplate, registry.getTemplate("primary"));
    }

    @Test
    void shouldThrowWhenTemplateNotFound() {
        assertThrows(IllegalArgumentException.class, () -> registry.getTemplate("nonexistent"));
    }

    @Test
    void shouldReturnAllDatasourceNames() {
        registry.register("primary", primaryTemplate);
        registry.register("secondary", secondaryTemplate);
        assertEquals(Set.of("primary", "secondary"), registry.getDatasourceNames());
    }

    @Test
    void shouldReturnUnmodifiableTemplates() {
        registry.register("primary", primaryTemplate);
        Map<String, SshJdbcTemplate> templates = registry.getTemplates();
        assertThrows(UnsupportedOperationException.class,
            () -> templates.put("new", secondaryTemplate));
    }

    // ---- New: register(ConnectionInfo) ----

    @Test
    void shouldRegisterByConnectionInfo() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "postgres", "secret");
        registry.register("dynamic1", info);

        assertTrue(registry.getDatasourceNames().contains("dynamic1"));
        SshJdbcTemplate template = registry.getTemplate("dynamic1");
        assertNotNull(template);
        verify(tunnelService).createOrGetTunnel("10.0.1.100", 5432);
    }

    @Test
    void shouldReplaceWhenRegisteringSameName() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        ConnectionInfo info2 = new ConnectionInfo("10.0.2.200", 5432, "db2", "user", "pass");

        registry.register("ds1", info1);
        registry.register("ds1", info2);

        SshJdbcTemplate template = registry.getTemplate("ds1");
        assertNotNull(template);
        // Should have created two tunnels (different hosts)
        verify(tunnelService, times(2)).createOrGetTunnel(anyString(), anyInt());
    }

    @Test
    void shouldThrowWhenRegisterByConnectionInfoWithoutTunnelService() {
        SshJdbcRegistry basic = new SshJdbcRegistry();
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        assertThrows(IllegalStateException.class, () -> basic.register("ds1", info));
    }

    // ---- New: unregister ----

    @Test
    void shouldUnregisterByName() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");
        registry.register("ds1", info);

        registry.unregister("ds1");

        assertFalse(registry.getDatasourceNames().contains("ds1"));
        assertThrows(IllegalArgumentException.class, () -> registry.getTemplate("ds1"));
    }

    @Test
    void shouldThrowWhenUnregisterNonExistent() {
        assertThrows(IllegalArgumentException.class, () -> registry.unregister("nonexistent"));
    }

    // ---- New: getOrCreate ----

    @Test
    void shouldGetOrCreateNewTemplate() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        SshJdbcTemplate template = registry.getOrCreate(info);

        assertNotNull(template);
        verify(tunnelService).createOrGetTunnel("10.0.1.100", 5432);
    }

    @Test
    void shouldReturnCachedTemplateOnSecondCall() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        SshJdbcTemplate first = registry.getOrCreate(info);
        SshJdbcTemplate second = registry.getOrCreate(info);

        assertSame(first, second);
        verify(tunnelService, times(1)).createOrGetTunnel("10.0.1.100", 5432);
    }

    @Test
    void shouldReturnSameTemplateForRegisteredName() {
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");
        registry.register("ds1", info);

        SshJdbcTemplate fromCache = registry.getOrCreate(info);

        assertSame(registry.getTemplate("ds1"), fromCache);
    }

    @Test
    void shouldThrowWhenGetOrCreateWithoutTunnelService() {
        SshJdbcRegistry basic = new SshJdbcRegistry();
        ConnectionInfo info = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        assertThrows(IllegalStateException.class, () -> basic.getOrCreate(info));
    }

    // ---- New: refresh ----

    @Test
    void shouldRefreshFromProvider() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        when(provider.provide()).thenReturn(Map.of("ds1", info1));

        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("ds1"));
        verify(provider).provide();
    }

    @Test
    void shouldAddNewOnRefresh() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        ConnectionInfo info2 = new ConnectionInfo("10.0.2.200", 5432, "db2", "user", "pass");

        when(provider.provide()).thenReturn(Map.of("ds1", info1));
        registry.refresh();

        when(provider.provide()).thenReturn(Map.of("ds1", info1, "ds2", info2));
        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("ds2"));
    }

    @Test
    void shouldRemoveStaleOnRefresh() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");

        when(provider.provide()).thenReturn(Map.of("ds1", info1));
        registry.refresh();

        when(provider.provide()).thenReturn(Map.of());
        registry.refresh();

        assertFalse(registry.getDatasourceNames().contains("ds1"));
    }

    @Test
    void shouldUpdateChangedOnRefresh() {
        ConnectionInfo info1 = new ConnectionInfo("10.0.1.100", 5432, "db1", "user", "pass");
        ConnectionInfo info1Updated = new ConnectionInfo("10.0.1.100", 5432, "db1_v2", "user", "pass");

        when(provider.provide()).thenReturn(Map.of("ds1", info1));
        registry.refresh();

        when(provider.provide()).thenReturn(Map.of("ds1", info1Updated));
        registry.refresh();

        assertTrue(registry.getDatasourceNames().contains("ds1"));
    }

    @Test
    void shouldNotAffectManuallyRegisteredOnRefresh() {
        // Manually register
        registry.register("manual", primaryTemplate);

        // Refresh with empty provider
        when(provider.provide()).thenReturn(Map.of());
        registry.refresh();

        // Manual registration survives
        assertTrue(registry.getDatasourceNames().contains("manual"));
    }

    @Test
    void shouldThrowWhenRefreshWithoutProvider() {
        SshJdbcRegistry basic = new SshJdbcRegistry();
        assertThrows(IllegalStateException.class, basic::refresh);
    }

    // ---- shutdown ----

    @Test
    void shouldShutdownCleanly() {
        registry.register("primary", primaryTemplate);
        registry.register("secondary", secondaryTemplate);
        assertDoesNotThrow(() -> registry.shutdown());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=SshJdbcRegistryTest -pl .`
Expected: FAIL — methods not found on SshJdbcRegistry

- [ ] **Step 3: Write the enhanced SshJdbcRegistry**

Replace `src/main/java/com/github/topxiao/sshjdbc/context/SshJdbcRegistry.java` with:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=SshJdbcRegistryTest -pl .`
Expected: ALL tests PASS

- [ ] **Step 5: Run all existing tests to verify no regression**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -pl .`
Expected: ALL tests PASS (existing + new)

- [ ] **Step 6: Commit**

```bash
cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter
git add src/main/java/com/github/topxiao/sshjdbc/context/SshJdbcRegistry.java src/test/java/com/github/topxiao/sshjdbc/context/SshJdbcRegistryTest.java
git commit -m "feat: enhance SshJdbcRegistry with dynamic register/unregister/getOrCreate/refresh"
```

---

### Task 4: SshJdbc Static Facade

**Files:**
- Create: `src/main/java/com/github/topxiao/sshjdbc/context/SshJdbc.java`
- Create: `src/test/java/com/github/topxiao/sshjdbc/context/SshJdbcTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/github/topxiao/sshjdbc/context/SshJdbcTest.java`:

```java
package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SshJdbcTest {

    private SshJdbcRegistry registry;
    private SshJdbcTemplate mockTemplate;
    private NamedParameterJdbcTemplate namedTemplate;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        registry = mock(SshJdbcRegistry.class);
        namedTemplate = mock(NamedParameterJdbcTemplate.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        mockTemplate = new SshJdbcTemplate(namedTemplate);
        when(namedTemplate.getJdbcTemplate()).thenReturn(jdbcTemplate);

        SshJdbc.init(registry, List.of());
    }

    @AfterEach
    void tearDown() {
        ExecutionContext.clear();
        SshJdbc.reset();
    }

    // ---- Resolution: full connection info ----

    @Test
    void shouldResolveFromFullConnectionInfo() {
        ExecutionContext.builder()
                .dbHost("10.0.1.100").dbPort(5432)
                .dbDatabase("mydb").dbUser("postgres").dbPassword("secret")
                .apply();

        when(registry.getOrCreate(any(ConnectionInfo.class))).thenReturn(mockTemplate);

        SshJdbcTemplate result = SshJdbc.resolveTemplate();
        assertNotNull(result);
        verify(registry).getOrCreate(any(ConnectionInfo.class));
    }

    // ---- Resolution: via resolver ----

    @Test
    void shouldResolveViaResolver() {
        ExecutionContext.builder().corpCode("midea").put("env", "v4").apply();

        ConnectionInfo expectedInfo = new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");
        ConnectionInfoResolver resolver = ctx -> {
            if ("midea".equals(ctx.getCorpCode())) return expectedInfo;
            return null;
        };
        SshJdbc.init(registry, List.of(resolver));

        when(registry.getOrCreate(expectedInfo)).thenReturn(mockTemplate);

        SshJdbcTemplate result = SshJdbc.resolveTemplate();
        assertNotNull(result);
        verify(registry).getOrCreate(expectedInfo);
    }

    @Test
    void shouldTryMultipleResolversInOrder() {
        ExecutionContext.builder().corpCode("acme").apply();

        ConnectionInfoResolver resolver1 = ctx -> null; // can't handle
        ConnectionInfoResolver resolver2 = ctx ->
            new ConnectionInfo("10.0.2.200", 5432, "db2", "user", "pass");

        SshJdbc.init(registry, List.of(resolver1, resolver2));
        when(registry.getOrCreate(any(ConnectionInfo.class))).thenReturn(mockTemplate);

        SshJdbc.resolveTemplate();
        verify(registry).getOrCreate(argThat(info ->
            "10.0.2.200".equals(info.host())));
    }

    @Test
    void shouldThrowWhenNoContextSet() {
        assertThrows(IllegalStateException.class, SshJdbc::resolveTemplate);
    }

    @Test
    void shouldThrowWhenCannotResolve() {
        ExecutionContext.builder().corpCode("unknown").apply();
        SshJdbc.init(registry, List.of()); // no resolvers

        assertThrows(IllegalStateException.class, SshJdbc::resolveTemplate);
    }

    // ---- Static query delegation ----

    @Test
    void shouldDelegateQueryForList() {
        setupResolvedTemplate();
        when(namedTemplate.queryForList(eq("SELECT * FROM t"), anyMap()))
            .thenReturn(List.of(Map.of("id", 1)));

        List<Map<String, Object>> result = SshJdbc.queryForList(
            "SELECT * FROM t", Map.of());
        assertEquals(1, result.size());
    }

    @Test
    void shouldDelegateQueryForMap() {
        setupResolvedTemplate();
        Map<String, Object> expected = Map.of("id", 1, "name", "alice");
        when(namedTemplate.queryForMap(eq("SELECT * FROM t WHERE id=:id"), anyMap()))
            .thenReturn(expected);

        Map<String, Object> result = SshJdbc.queryForMap(
            "SELECT * FROM t WHERE id=:id", Map.of("id", 1));
        assertEquals(expected, result);
    }

    @Test
    void shouldDelegateQueryForObject() {
        setupResolvedTemplate();
        when(namedTemplate.queryForObject(eq("SELECT COUNT(*) FROM t"), anyMap(), eq(Integer.class)))
            .thenReturn(42);

        Integer result = SshJdbc.queryForObject("SELECT COUNT(*) FROM t", Map.of(), Integer.class);
        assertEquals(42, result);
    }

    @Test
    void shouldDelegateUpdate() {
        setupResolvedTemplate();
        when(namedTemplate.update(eq("UPDATE t SET name=:name"), anyMap()))
            .thenReturn(1);

        int result = SshJdbc.update("UPDATE t SET name=:name", Map.of("name", "alice"));
        assertEquals(1, result);
    }

    @Test
    void shouldDelegateExecute() {
        setupResolvedTemplate();
        SshJdbc.execute("CREATE TABLE test (id INT)");
        verify(jdbcTemplate).execute("CREATE TABLE test (id INT)");
    }

    @Test
    void shouldDelegateGetTemplateByName() {
        when(registry.getTemplate("primary")).thenReturn(mockTemplate);
        SshJdbcTemplate result = SshJdbc.getTemplate("primary");
        assertSame(mockTemplate, result);
    }

    // ---- Helper ----

    private void setupResolvedTemplate() {
        ExecutionContext.builder()
                .dbHost("10.0.1.100").dbPort(5432)
                .dbDatabase("mydb").dbUser("postgres").dbPassword("secret")
                .apply();
        when(registry.getOrCreate(any(ConnectionInfo.class))).thenReturn(mockTemplate);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=SshJdbcTest -pl .`
Expected: FAIL — class not found

- [ ] **Step 3: Write implementation**

Create `src/main/java/com/github/topxiao/sshjdbc/context/SshJdbc.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=SshJdbcTest -pl .`
Expected: ALL tests PASS

- [ ] **Step 5: Commit**

```bash
cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter
git add src/main/java/com/github/topxiao/sshjdbc/context/SshJdbc.java src/test/java/com/github/topxiao/sshjdbc/context/SshJdbcTest.java
git commit -m "feat: add SshJdbc static facade with context-driven datasource resolution"
```

---

### Task 5: AutoConfiguration Update

**Files:**
- Modify: `src/main/java/com/github/topxiao/sshjdbc/autoconfigure/SshJdbcAutoConfiguration.java`
- Modify: `src/test/java/com/github/topxiao/sshjdbc/autoconfigure/SshJdbcAutoConfigurationTest.java`

- [ ] **Step 1: Write the failing tests**

Add these tests to `src/test/java/com/github/topxiao/sshjdbc/autoconfigure/SshJdbcAutoConfigurationTest.java`:

```java
import com.github.topxiao.sshjdbc.context.ConnectionInfoResolver;
import com.github.topxiao.sshjdbc.context.ExecutionContext;
import com.github.topxiao.sshjdbc.context.SshJdbc;
import org.springframework.core.Ordered;
```

Append these test methods to the existing test class:

```java
    // ---- ConnectionInfoResolver wiring ----

    @Test
    void shouldDiscoverConnectionInfoResolverBeans() {
        ConnectionInfoResolver resolver = ctx ->
            new ConnectionInfo("10.0.1.100", 5432, "mydb", "user", "pass");

        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .withBean(ConnectionInfoResolver.class, () -> resolver)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConnectionInfoResolver.class);
                });
    }

    @Test
    void shouldWorkWithoutConnectionInfoResolver() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ConnectionInfoResolver.class);
                    // Registry still works
                    assertThat(context).hasSingleBean(SshJdbcRegistry.class);
                });
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -Dtest=SshJdbcAutoConfigurationTest -pl .`
Expected: Existing tests pass, new tests may pass or fail depending on current wiring

- [ ] **Step 3: Update AutoConfiguration**

Replace `src/main/java/com/github/topxiao/sshjdbc/autoconfigure/SshJdbcAutoConfiguration.java` with:

```java
package com.github.topxiao.sshjdbc.autoconfigure;

import com.github.topxiao.sshjdbc.context.ConnectionInfoResolver;
import com.github.topxiao.sshjdbc.context.SshJdbc;
import com.github.topxiao.sshjdbc.context.SshJdbcRegistry;
import com.github.topxiao.sshjdbc.jdbc.DataSourceCustomizer;
import com.github.topxiao.sshjdbc.jdbc.SshJdbcTemplate;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import com.github.topxiao.sshjdbc.provider.ConnectionInfoProvider;
import com.github.topxiao.sshjdbc.tunnel.SshTunnelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration that assembles all SSH JDBC starter components.
 *
 * <p>Activates when {@code ssh-jdbc.tunnel.host} is set. Binds tunnel and
 * datasource properties, creates an {@link SshTunnelService}, merges static
 * (YAML) and dynamic ({@link ConnectionInfoProvider}) datasources, opens SSH
 * tunnels, and registers {@link SshJdbcTemplate} instances in a
 * {@link SshJdbcRegistry}.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties
@ConditionalOnProperty(prefix = "ssh-jdbc.tunnel", name = "host")
public class SshJdbcAutoConfiguration {

    @Bean("sshJdbcTunnelProperties")
    @ConfigurationProperties(prefix = "ssh-jdbc.tunnel")
    public SshTunnelProperties sshJdbcTunnelProperties() {
        return new SshTunnelProperties();
    }

    @Bean("sshJdbcDataSourceProperties")
    @ConfigurationProperties(prefix = "ssh-jdbc")
    public SshDataSourceProperties sshJdbcDataSourceProperties() {
        return new SshDataSourceProperties();
    }

    @Bean
    public SshTunnelService sshJdbcTunnelService(SshTunnelProperties props) {
        SshTunnelService service = new SshTunnelService(props);
        service.init();
        return service;
    }

    @Bean
    public SshJdbcRegistry sshJdbcRegistry(
            SshTunnelProperties tunnelProps,
            SshDataSourceProperties dataSourceProps,
            SshTunnelService sshJdbcTunnelService,
            ObjectProvider<ConnectionInfoProvider> providerOpt,
            ObjectProvider<DataSourceCustomizer> customizerOpt,
            ObjectProvider<List<ConnectionInfoResolver>> resolversOpt) {

        DataSourceCustomizer customizer = customizerOpt.getIfAvailable();
        ConnectionInfoProvider provider = providerOpt.getIfAvailable();

        SshJdbcRegistry registry = new SshJdbcRegistry(
                sshJdbcTunnelService, customizer, provider);

        // 1. Collect yml static datasources
        Map<String, ConnectionInfo> merged = new LinkedHashMap<>();
        for (Map.Entry<String, SshDataSourceProperties.DataSourceProperties> entry
                : dataSourceProps.getDatasources().entrySet()) {
            SshDataSourceProperties.DataSourceProperties ds = entry.getValue();
            merged.put(entry.getKey(), new ConnectionInfo(
                    ds.getHost(), ds.getPort(), ds.getDatabase(),
                    ds.getUsername(), ds.getPassword()));
        }

        // 2. Merge dynamic datasources (if ConnectionInfoProvider bean exists)
        if (provider != null) {
            Map<String, ConnectionInfo> dynamic = provider.provide();
            if (dynamic != null) {
                for (Map.Entry<String, ConnectionInfo> entry : dynamic.entrySet()) {
                    ConnectionInfo existing = merged.put(entry.getKey(), entry.getValue());
                    if (existing != null) {
                        log.info("动态数据源 '{}' 覆盖了静态配置", entry.getKey());
                    } else {
                        log.info("添加动态数据源 '{}'", entry.getKey());
                    }
                }
            }
        }

        // 3. Register all merged datasources
        for (Map.Entry<String, ConnectionInfo> entry : merged.entrySet()) {
            String name = entry.getKey();
            ConnectionInfo info = entry.getValue();

            try {
                int localPort = sshJdbcTunnelService.createOrGetTunnel(info.host(), info.port());
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
                registry.register(name, template);
                log.info("已注册 SshJdbcTemplate: {}", name);
            } catch (Exception e) {
                throw new RuntimeException(
                        "创建 SshJdbcTemplate 失败 (数据源: " + name + "): " + e.getMessage(), e);
            }
        }

        // 4. Initialize SshJdbc static facade
        List<ConnectionInfoResolver> resolvers = resolversOpt.getIfAvailable();
        SshJdbc.init(registry, resolvers != null ? resolvers : List.of());

        return registry;
    }
}
```

- [ ] **Step 4: Run all tests to verify**

Run: `cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter && mvn test -pl .`
Expected: ALL tests PASS

- [ ] **Step 5: Commit**

```bash
cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter
git add src/main/java/com/github/topxiao/sshjdbc/autoconfigure/SshJdbcAutoConfiguration.java src/test/java/com/github/topxiao/sshjdbc/autoconfigure/SshJdbcAutoConfigurationTest.java
git commit -m "feat: update AutoConfiguration to wire dynamic registry, resolvers, and SshJdbc facade"
```

---

### Task 6: README Update

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add dynamic datasource documentation**

In `README.md`, add a new section after "## 动态数据源" covering the three new features:

```markdown
## 运行时动态数据源

### 动态注册/注销

```java
@Autowired
private SshJdbcRegistry registry;

// 动态注册
ConnectionInfo info = new ConnectionInfo("10.0.3.100", 5432, "newdb", "user", "pass");
registry.register("dynamic1", info);

// 使用
SshJdbcTemplate template = registry.getTemplate("dynamic1");

// 动态注销
registry.unregister("dynamic1");

// 刷新 Provider
registry.refresh();
```

### 上下文驱动

通过 `ExecutionContext` + `ConnectionInfoResolver` 自动解析数据源：

```java
// 1. 实现 Resolver
@Component
public class CorpDatabaseResolver implements ConnectionInfoResolver {
    @Override
    public ConnectionInfo resolve(ExecutionContext ctx) {
        String corpCode = ctx.getCorpCode();
        if (corpCode == null) return null;
        // 根据 corpCode 查库/查配置返回 ConnectionInfo
        return new ConnectionInfo(host, port, database, user, password);
    }
}

// 2. 使用
ExecutionContext.builder()
    .corpCode("midea")
    .put("env", "v4")
    .apply();

// 自动解析数据源并查询
List<Map<String, Object>> rows = SshJdbc.queryForList(
    "SELECT * FROM users WHERE org = :org",
    Map.of("org", "engineering"));

// 或者直接传入完整连接参数
ExecutionContext.builder()
    .dbHost("10.0.1.100").dbPort(5432)
    .dbDatabase("mydb").dbUser("postgres").dbPassword("secret")
    .apply();

List<Map<String, Object>> rows = SshJdbc.queryForList("SELECT * FROM t", Map.of());
```

### ExecutionContext API

| 方法 | 说明 |
|------|------|
| `ExecutionContext.builder().corpCode(x).apply()` | 设置逻辑标识 |
| `ExecutionContext.builder().put(key, value).apply()` | 设置扩展属性 |
| `ExecutionContext.builder().dbHost(x).dbPort(n)...apply()` | 设置完整连接参数 |
| `ExecutionContext.clear()` | 清除当前线程上下文 |
| `ctx.hasFullConnectionInfo()` | 是否有完整连接参数 |

### SshJdbc 静态方法

| 方法 | 说明 |
|------|------|
| `SshJdbc.queryForList(sql, params)` | 上下文自动解析 + 查询 |
| `SshJdbc.queryForMap(sql, params)` | 上下文自动解析 + 单行查询 |
| `SshJdbc.queryForObject(sql, params, type)` | 上下文自动解析 + 单值查询 |
| `SshJdbc.update(sql, params)` | 上下文自动解析 + 更新 |
| `SshJdbc.execute(sql)` | 上下文自动解析 + 执行 DDL |
| `SshJdbc.getTemplate(name)` | 按名称获取模板 |
```

- [ ] **Step 2: Commit**

```bash
cd D:\Workspace\GitHub\topxiao\ssh-jdbc-spring-boot-starter
git add README.md
git commit -m "docs: add runtime dynamic datasource and context-driven usage to README"
```
