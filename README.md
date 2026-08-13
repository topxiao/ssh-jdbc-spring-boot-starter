# ssh-jdbc-spring-boot-starter

通过 SSH 隧道连接远程 PostgreSQL 数据库的 Spring Boot Starter。

## 特性

- 基于 SSH 隧道的 JDBC 连接，无需直连数据库
- 支持多命名数据源
- 可插拔的连接信息提供者（动态数据源）
- 可直接适配应用已有的请求/执行上下文
- 可自定义 DataSource 构建
- 隧道缓存、空闲清理

## 快速开始

### 1. 引入依赖

添加 JitPack 仓库和依赖（无需认证）：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.topxiao</groupId>
    <artifactId>ssh-jdbc-spring-boot-starter</artifactId>
    <version>v0.4.0</version>
</dependency>
```

### 2. 配置

```yaml
ssh-jdbc:
  tunnel:
    host: your-ssh-server.com
    port: 22
    user: ssh-user
    private-key-path: /path/to/id_rsa
    # 二选一：固定指纹，或通过 known-hosts-path 指定 known_hosts 文件。
    host-key-fingerprint: SHA256:replace-with-your-server-fingerprint
  datasources:
    primary:
      host: 10.0.1.100
      port: 5432
      database: mydb
      username: postgres
      password: ${DB_PASSWORD}
```

### 3. 使用

通过 `SshJdbcRegistry` 按名称获取模板：

```java
@RestController
public class MyController {

    private final SshJdbcRegistry registry;

    public MyController(SshJdbcRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> getUsers() {
        SshJdbcTemplate sshJdbc = registry.getTemplate("primary");
        return sshJdbc.queryForList(
            "SELECT * FROM users WHERE org = :org",
            Map.of("org", "engineering"));
    }
}
```

## 动态数据源

实现 `ConnectionInfoProvider` 接口，运行时动态提供数据源。动态数据源会与 YAML 静态配置合并，同名时动态优先：

```java
@Component
public class MyProvider implements ConnectionInfoProvider {
    @Override
    public Map<String, ConnectionInfo> provide() {
        return Map.of(
            "dynamic1", new ConnectionInfo("10.0.3.100", 5432, "db1", "user", dbPassword)
        );
    }
}
```

## 运行时动态数据源

### 适配应用已有上下文（推荐）

如果应用已经有自己的请求或执行上下文，不需要复制到 starter 的
`ExecutionContext`。提供一个 `CurrentConnectionInfoProvider` Bean 即可：

```java
@Bean
CurrentConnectionInfoProvider currentConnectionInfoProvider(MyContext context) {
    return () -> {
        MyDatabaseInfo db = context.currentDatabase();
        if (db == null) return null;
        return new ConnectionInfo(
            db.host(), db.port(), db.database(), db.username(), db.password());
    };
}
```

之后直接使用 starter 门面：

```java
SshJdbc.getTemplate().update(sql, params);
JdbcTemplate jdbc = SshJdbc.getJdbcTemplate();
```

Provider 返回 `null` 时，会继续尝试 starter 自带的
`ExecutionContext + ConnectionInfoResolver` 兼容链。

### 动态注册/注销

```java
@Autowired
private SshJdbcRegistry registry;

// 动态注册
ConnectionInfo info = new ConnectionInfo("10.0.3.100", 5432, "newdb", "user", dbPassword);
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
    .corpCode("acme")
    .put("env", "v4")
    .apply();

// 自动解析数据源并查询
List<Map<String, Object>> rows = SshJdbc.queryForList(
    "SELECT * FROM users WHERE org = :org",
    Map.of("org", "engineering"));

// 或者直接传入完整连接参数
ExecutionContext.builder()
    .dbHost("10.0.1.100").dbPort(5432)
    .dbDatabase("mydb").dbUser("postgres").dbPassword(dbPassword)
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
| `SshJdbc.getTemplate()` | 获取当前连接的 `SshJdbcTemplate` |
| `SshJdbc.getJdbcTemplate()` | 获取当前连接底层 `JdbcTemplate` |
| `SshJdbc.getTemplate(name)` | 按名称获取模板 |

## 自定义 DataSource

实现 `DataSourceCustomizer` 接口，自定义每个数据源的 DataSource 构建（如连接池配置）：

```java
@Component
public class MyCustomizer implements DataSourceCustomizer {
    @Override
    public DataSource customize(DataSourceBuilder<?> builder, String datasourceName) {
        // 自定义连接池配置
        HikariDataSource ds = builder.type(HikariDataSource.class).build();
        ds.setMaximumPoolSize(20);
        ds.setConnectionTimeout(30000);
        return ds;
    }
}
```

## 配置参考

### 隧道配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ssh-jdbc.tunnel.host` | - | SSH 服务器地址（必填，填入后 Starter 自动激活） |
| `ssh-jdbc.tunnel.port` | 22 | SSH 端口 |
| `ssh-jdbc.tunnel.user` | - | SSH 用户名 |
| `ssh-jdbc.tunnel.private-key-path` | - | SSH 私钥文件路径 |
| `ssh-jdbc.tunnel.private-key-passphrase` | - | 私钥密码（可选） |
| `ssh-jdbc.tunnel.host-key-fingerprint` | - | 推荐：固定的 SSH 主机密钥指纹 |
| `ssh-jdbc.tunnel.known-hosts-path` | `~/.ssh/known_hosts` | 指定 known_hosts 文件；未配置指纹时使用 |
| `ssh-jdbc.tunnel.connect-timeout-ms` | 10000 | SSH 建连超时（毫秒） |
| `ssh-jdbc.tunnel.timeout-ms` | 30000 | SSH Socket 超时（毫秒） |
| `ssh-jdbc.tunnel.max-connections` | 50 | 最大隧道连接数 |
| `ssh-jdbc.tunnel.idle-timeout-ms` | 600000 | 空闲超时（毫秒） |

### 数据源配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `ssh-jdbc.datasources.<name>.host` | - | 远程数据库地址 |
| `ssh-jdbc.datasources.<name>.port` | 5432 | 远程数据库端口 |
| `ssh-jdbc.datasources.<name>.database` | - | 数据库名 |
| `ssh-jdbc.datasources.<name>.username` | - | 数据库用户名 |
| `ssh-jdbc.datasources.<name>.password` | - | 数据库密码 |
| `ssh-jdbc.max-cached-datasources` | 100 | 最大缓存 DataSource/连接池数量 |

## SshJdbcTemplate API

| 方法 | 说明 |
|------|------|
| `queryForList(sql)` | 查询返回列表 |
| `queryForList(sql, params)` | 命名参数查询返回列表 |
| `queryForMap(sql, params)` | 命名参数查询单行 |
| `queryForObject(sql, params, type)` | 命名参数查询单值 |
| `query(sql, params, rowMapper)` | 自定义行映射查询 |
| `update(sql)` | 执行更新（无参数） |
| `update(sql, params)` | 命名参数更新 |
| `batchUpdate(sql, batchArgs...)` | 批量更新 |
| `execute(sql)` | 执行 DDL |
| `getNamedParameterJdbcTemplate()` | 获取底层 NamedParameterJdbcTemplate |
| `getJdbcTemplate()` | 获取底层 JdbcTemplate |

## 原理

```
应用 → SshJdbcTemplate → NamedParameterJdbcTemplate
                              ↓
                    localhost:随机端口（SSH 隧道）
                              ↓
                  SSH 跳板机 (ssh-jdbc.tunnel.*)
                              ↓
                    远程数据库 (datasource.*)
```

Starter 自动配置流程：

1. 读取 `ssh-jdbc.tunnel.*` 配置，创建 `SshTunnelService`
2. 收集 YAML 静态数据源 + `ConnectionInfoProvider` 动态数据源
3. 为每个数据源建立 SSH 隧道，创建 `SshJdbcTemplate`
4. 注册到 `SshJdbcRegistry`，按名称获取使用

## 安全说明

- Starter 默认执行 SSH 主机密钥校验，不再接受任意主机密钥。
- 生产环境推荐配置 `host-key-fingerprint`；也可以配置 `known-hosts-path`。
- 本地转发端口仅绑定 loopback，不会监听外部网卡。
- 数据库密码和私钥口令不会出现在配置对象的 `toString()` 中。
- 密码、私钥口令应通过环境变量或外部密钥管理系统注入，不要写入仓库。

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
