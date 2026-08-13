# CLAUDE.md

## 构建与验证

```bash
mvn test
mvn package -DskipTests
```

## 结构约定

- `autoconfigure/`：Spring Boot 自动配置与配置属性。
- `context/`：当前连接解析、静态门面与注册表。
- `provider/`：应用向 starter 提供连接信息的 SPI 和值对象。
- `jdbc/`：JDBC 模板封装及数据源定制扩展点。
- `tunnel/`：SSH 隧道生命周期。
- 新增公开 API 必须补单元测试和 README 使用说明。

## 兼容性约定

- 保留 `ExecutionContext + ConnectionInfoResolver` 旧用法。
- 应用自有上下文通过 provider SPI 接入，starter 不承载用户、认证或日志上下文。
- `SshJdbc` 只负责解析当前连接并委托 `SshJdbcRegistry`，不得自行创建隧道或数据源缓存。
- 改动后运行 `mvn test`；发布操作必须另行确认。
