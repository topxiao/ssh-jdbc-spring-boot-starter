# 运行时动态数据源支持 — 设计文档

## 概述

为 ssh-jdbc-spring-boot-starter 添加运行时动态数据源管理能力。支持三种使用方式：

1. **按名称获取**（现有）— `registry.getTemplate("primary")`
2. **动态注册/注销**（新增）— `registry.register(name, info)` / `registry.unregister(name)`
3. **上下文驱动**（新增）— `SshJdbc.queryForList(sql, params)` 自动从 ThreadLocal 上下文解析

## 架构

三层架构，高层依赖低层，低层不感知高层：

```
┌──────────────────────────────────────────────┐
│ Layer 3: 上下文驱动                            │
│  ExecutionContext (ThreadLocal)               │
│  ConnectionInfoResolver (上下文 → 连接信息)    │
│  SshJdbc (静态门面)                           │
├──────────────────────────────────────────────┤
│ Layer 2: 动态注册表                            │
│  SshJdbcRegistry (增强版)                      │
│    register / unregister / refresh / getOrCreate │
├──────────────────────────────────────────────┤
│ Layer 1: 隧道管理 (现有不变)                    │
│  SshTunnelService                             │
└──────────────────────────────────────────────┘
```

## 组件设计

### ExecutionContext

ThreadLocal 上下文对象，携带数据源解析所需参数。

**位置：** `context/ExecutionContext.java`

**字段：**

| 字段 | 类型 | 说明 |
|------|------|------|
| corpCode | String | 企业/租户标识（逻辑参数） |
| attributes | Map<String, String> | 扩展属性（env 等） |
| dbHost | String | 完整连接参数：主机 |
| dbPort | Integer | 完整连接参数：端口 |
| dbDatabase | String | 完整连接参数：数据库名 |
| dbUser | String | 完整连接参数：用户名 |
| dbPassword | String | 完整连接参数：密码 |

**核心方法：**

```java
static ExecutionContext current()                    // 获取当前线程上下文
static void set(ExecutionContext ctx)                // 设置当前线程上下文
static void clear()                                  // 清除当前线程上下文
static Builder builder()                             // 创建构建器

boolean hasFullConnectionInfo()                       // dbHost/port/database/user/password 全部非空
String getAttribute(String key)                       // 获取扩展属性
```

**Builder 链式调用：**

```java
ExecutionContext.builder()
    .corpCode("midea")
    .put("env", "v4")
    .apply();                        // 设置 ThreadLocal 并返回

ExecutionContext.builder()
    .corpCode("midea")
    .dbHost("10.0.1.100")
    .dbPort(5432)
    .dbDatabase("mydb")
    .dbUser("postgres")
    .dbPassword("secret")
    .apply();
```

**线程安全：** 使用 `ThreadLocal<ExecutionContext>` 存储，每个线程独立。

---

### ConnectionInfoResolver

将逻辑上下文参数解析为连接信息的策略接口。

**位置：** `context/ConnectionInfoResolver.java`

```java
@FunctionalInterface
public interface ConnectionInfoResolver {
    /**
     * 从上下文解析出连接信息。
     * 返回 null 表示无法解析（框架继续尝试下一个 Resolver）。
     */
    ConnectionInfo resolve(ExecutionContext ctx);
}
```

**使用方式：** 注册为 Spring Bean，框架自动发现。支持多个 Resolver，按 `@Order` 排序，第一个返回非 null 的生效。

**用户实现示例：**

```java
@Component
public class CorpDatabaseResolver implements ConnectionInfoResolver {
    @Override
    public ConnectionInfo resolve(ExecutionContext ctx) {
        String corpCode = ctx.getCorpCode();
        String env = ctx.getAttribute("env");
        if (corpCode == null) return null;
        // 查库或查配置，返回对应的 ConnectionInfo
        return new ConnectionInfo(host, port, database, user, password);
    }
}
```

---

### SshJdbc（静态门面）

从 ThreadLocal 上下文自动解析数据源并执行 SQL 的静态工具类。

**位置：** `context/SshJdbc.java`

**内部状态（由 AutoConfiguration 注入）：**

```java
private static SshJdbcRegistry registry;
private static List<ConnectionInfoResolver> resolvers;
```

**解析链：**

```
1. ExecutionContext.current()
2. hasFullConnectionInfo()?
   ├── yes → new ConnectionInfo(dbHost, dbPort, dbDatabase, dbUser, dbPassword)
   └── no  → 遍历 resolvers（按 @Order），取第一个非 null
3. 解析失败 → 抛出 IllegalStateException
4. registry.getOrCreate(connectionInfo)
5. 在 template 上执行 SQL
```

**静态方法：**

| 方法 | 说明 |
|------|------|
| `queryForList(sql, params)` | 命名参数查询返回列表 |
| `queryForMap(sql, params)` | 命名参数查询单行 |
| `queryForObject(sql, params, type)` | 命名参数查询单值 |
| `query(sql, params, rowMapper)` | 自定义行映射查询 |
| `update(sql, params)` | 命名参数更新 |
| `batchUpdate(sql, batchArgs...)` | 批量更新 |
| `execute(sql)` | 执行 DDL |
| `getTemplate()` | 获取当前上下文解析出的 SshJdbcTemplate |
| `getTemplate(name)` | 按名称获取（透传 Registry） |

---

### SshJdbcRegistry（增强）

**位置：** `context/SshJdbcRegistry.java`（现有文件，增强）

**现有方法（不变）：**

- `register(String name, SshJdbcTemplate template)` — 注册已创建的模板
- `getTemplate(String datasourceName)` — 按名称获取
- `getDatasourceNames()` — 返回所有已注册名称
- `getTemplates()` — 返回不可变 Map

**新增方法：**

#### `void register(String name, ConnectionInfo info)`

动态注册数据源。自动创建 SSH 隧道、DataSource、SshJdbcTemplate。

参数：
- `name` — 数据源名称
- `info` — 连接信息

行为：
1. 调用 `tunnelService.createOrGetTunnel(info.host(), info.port())` 获取本地端口
2. 构建 DataSource（应用 DataSourceCustomizer 如果存在）
3. 创建 `NamedParameterJdbcTemplate` → `SshJdbcTemplate`
4. 如果同名已存在，先关闭旧 DataSource 再替换
5. 记录 `name → ConnectionInfo` 映射（供 refresh 使用）

#### `void unregister(String name)`

动态注销数据源。关闭 DataSource、移除模板。

参数：
- `name` — 数据源名称

行为：
1. 从 templates 中移除
2. 如果旧模板的 DataSource 实现了 `AutoCloseable`，调用 `close()`
3. 不关闭 SSH 隧道（隧道可能被其他数据源共享，由 SshTunnelService 统一管理）

抛出：`IllegalArgumentException` 如果名称不存在

#### `SshJdbcTemplate getOrCreate(ConnectionInfo info)`

按连接参数获取或创建模板。用于上下文驱动场景。

参数：
- `info` — 连接信息

行为：
1. 计算缓存 key：`info.cacheKey()`（host:port:database:username）
2. 在内部 `Map<String, SshJdbcTemplate>` 中按 key 查找
3. 未命中 → 调用 `register` 的内部逻辑创建新模板
4. 返回模板

#### `void refresh()`

重新调用 ConnectionInfoProvider，对比差异，增减数据源。

行为：
1. 调用 `ConnectionInfoProvider.provide()` 获取最新数据源列表
2. 与当前已注册的对比：
   - 新增的 → `register(name, info)`
   - 删除的 → `unregister(name)`
   - 变更的（ConnectionInfo 不同）→ 先 unregister 再 register

**线程安全：** 内部 Map 改为 `ConcurrentHashMap`，register/unregister/getOrCreate 使用同步块保证原子性。

**新增依赖：** Registry 需要持有 `SshTunnelService`、`DataSourceCustomizer`、`ConnectionInfoProvider` 的引用（由 AutoConfiguration 注入）。

---

## AutoConfiguration 变更

`SshJdbcAutoConfiguration` 增强：

1. 注入 `ConnectionInfoResolver` beans 到 `SshJdbc` 静态门面
2. 将 `SshTunnelService`、`DataSourceCustomizer`、`ConnectionInfoProvider` 传入 `SshJdbcRegistry`
3. 注册 `SshJdbc` 的静态引用（通过 `@PostConstruct` 或 Bean 初始化回调）

---

## 向后兼容

- 现有 `registry.getTemplate("primary")` 用法完全不变
- `ConnectionInfoProvider` 仍然在启动时调用一次
- `DataSourceCustomizer` 对所有数据源生效（包括动态创建的）
- `SshJdbc` 静态门面是可选功能，不使用则无影响
- 新增的 `ExecutionContext`、`ConnectionInfoResolver` 不引入新依赖

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `context/ExecutionContext.java` | 新建 | ThreadLocal 上下文 |
| `context/ConnectionInfoResolver.java` | 新建 | 逻辑标识解析接口 |
| `context/SshJdbc.java` | 新建 | 静态查询门面 |
| `context/SshJdbcRegistry.java` | 修改 | 增加 unregister/refresh/getOrCreate |
| `autoconfigure/SshJdbcAutoConfiguration.java` | 修改 | 注入新组件 |
| `autoconfigure/SshAutoConfigurationTest.java` | 修改 | 测试新功能 |
