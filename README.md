# ssh-jdbc-spring-boot-starter

Spring Boot Starter for JDBC access through SSH tunnels.

## Features

- SSH tunnel-based JDBC connectivity
- Multiple named datasources
- Pluggable connection info providers
- Customizable DataSource
- Tunnel caching, idle cleanup, auto-reconnect

## Quick Start

### 1. Add dependency

```xml
<dependency>
    <groupId>com.github.topxiao</groupId>
    <artifactId>ssh-jdbc-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure

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

### 3. Use

```java
@RestController
public class MyController {

    private final SshJdbcTemplate sshJdbc;

    public MyController(@Qualifier("primarySshJdbcTemplate") SshJdbcTemplate sshJdbc) {
        this.sshJdbc = sshJdbc;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> getUsers() {
        return sshJdbc.queryForList(
            "SELECT * FROM users WHERE org = :org",
            Map.of("org", "engineering"));
    }
}
```

## Dynamic Datasources

Implement `ConnectionInfoProvider` to provide datasources at runtime:

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

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `ssh-jdbc.tunnel.host` | - | SSH server host (required) |
| `ssh-jdbc.tunnel.port` | 22 | SSH server port |
| `ssh-jdbc.tunnel.user` | - | SSH username |
| `ssh-jdbc.tunnel.private-key-path` | - | Path to SSH private key |
| `ssh-jdbc.tunnel.private-key-passphrase` | - | Private key passphrase |
| `ssh-jdbc.tunnel.max-connections` | 50 | Max tunnel connections |
| `ssh-jdbc.tunnel.idle-timeout-ms` | 600000 | Idle timeout in ms |
| `ssh-jdbc.datasources.<name>.host` | - | Remote DB host |
| `ssh-jdbc.datasources.<name>.port` | 5432 | Remote DB port |
| `ssh-jdbc.datasources.<name>.database` | - | Database name |
| `ssh-jdbc.datasources.<name>.username` | - | DB username |
| `ssh-jdbc.datasources.<name>.password` | - | DB password |
