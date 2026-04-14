# ssh-jdbc-spring-boot-starter

通过 SSH 隧道连接远程数据库的 Spring Boot Starter。

## 特性

- 基于 SSH 隧道的 JDBC 连接，无需直连数据库
- 支持多命名数据源
- 可插拔的连接信息提供者（动态数据源）
- 可自定义 DataSource 构建
- 隧道缓存、空闲清理、断线重连

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
    <version>v0.1.0</version>
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
  datasources:
    primary:
      host: 10.0.1.100
      port: 5432
      database: mydb
      username: postgres
      password: secret
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
            "dynamic1", new ConnectionInfo("10.0.3.100", 5432, "db1", "user", "pass")
        );
    }
}
```

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
