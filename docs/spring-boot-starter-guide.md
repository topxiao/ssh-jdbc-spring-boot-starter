# Spring Boot Starter 开发、发布与使用指南

> 以 `ssh-jdbc-spring-boot-starter` 为实例，完整讲解如何从零开发一个 Starter、发布到 GitHub、通过 JitPack 构建依赖、以及其他人如何引入使用。

---

## 目录

1. [什么是 Spring Boot Starter](#1-什么是-spring-boot-starter)
2. [项目结构](#2-项目结构)
3. [核心开发步骤](#3-核心开发步骤)
4. [pom.xml 配置要点](#4-pomxml-配置要点)
5. [自动配置注册（Spring Boot 3.x）](#5-自动配置注册spring-boot-3x)
6. [条件装配](#6-条件装配)
7. [打包发布到 GitHub](#7-打包发布到-github)
8. [通过 JitPack 构建远程依赖](#8-通过-jitpack-构建远程依赖)
9. [其他人如何引入使用](#9-其他人如何引入使用)
10. [版本升级流程](#10-版本升级流程)
11. [常见问题排查](#11-常见问题排查)

---

## 1. 什么是 Spring Boot Starter

Starter 本质上是一个 **包含自动配置类的 JAR 包**。当其他项目把它加入依赖后，Spring Boot 会自动：

1. 扫描到自动配置类
2. 根据条件装配决定是否激活
3. 把 Starter 里的 Bean 注册到容器中

用户只需添加依赖 + 写配置，无需任何 `@Import` 或 `@ComponentScan`。

---

## 2. 项目结构

一个标准的 Starter 项目结构如下：

```
ssh-jdbc-spring-boot-starter/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/github/topxiao/sshjdbc/
│   │   │       ├── autoconfigure/          ← 自动配置（入口）
│   │   │       │   ├── SshJdbcAutoConfiguration.java
│   │   │       │   ├── SshTunnelProperties.java
│   │   │       │   └── SshDataSourceProperties.java
│   │   │       ├── context/                ← 业务组件
│   │   │       │   ├── ExecutionContext.java
│   │   │       │   ├── ConnectionInfoResolver.java
│   │   │       │   ├── SshJdbc.java
│   │   │       │   └── SshJdbcRegistry.java
│   │   │       ├── jdbc/
│   │   │       │   ├── SshJdbcTemplate.java
│   │   │       │   └── DataSourceCustomizer.java
│   │   │       ├── provider/
│   │   │       │   ├── ConnectionInfo.java
│   │   │       │   └── ConnectionInfoProvider.java
│   │   │       └── tunnel/
│   │   │           ├── SshTunnelService.java
│   │   │           └── TunnelInfo.java
│   │   └── resources/
│   │       └── META-INF/
│   │           └── spring/
│   │               └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── test/
│       └── java/
│           └── com/github/topxiao/sshjdbc/
│               ├── autoconfigure/
│               │   └── SshJdbcAutoConfigurationTest.java
│               ├── context/
│               │   ├── ExecutionContextTest.java
│               │   ├── SshJdbcRegistryTest.java
│               │   └── SshJdbcTest.java
│               └── ...更多测试
└── README.md
```

### 关键目录说明

| 目录 | 作用 |
|------|------|
| `autoconfigure/` | 自动配置类，Starter 的入口，只有这个包会被 Spring Boot 自动扫描 |
| `context/` `jdbc/` `provider/` `tunnel/` | 业务逻辑，按职责分包 |
| `META-INF/spring/` | Spring Boot 3.x 自动配置注册文件（**必须**） |
| `test/` | 单元测试 + 自动配置测试 |

---

## 3. 核心开发步骤

开发一个 Starter 分为以下几步：

### 步骤 1：创建 Maven 项目

使用 `spring-boot-starter-parent` 作为父 POM：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.4</version>
    <relativePath/>
</parent>
```

### 步骤 2：设置 GAV 坐标

```
groupId:    com.github.<你的GitHub用户名>
artifactId: <名字>-spring-boot-starter
version:    0.1.0-SNAPSHOT
```

> **JitPack 约定**：`groupId` 使用 `com.github.<用户名>`，JitPack 会自动将 GitHub 仓库映射到这个 groupId。

### 步骤 3：编写自动配置类

自动配置类是 Starter 的核心，它负责：

1. 读取配置属性
2. 创建并注册 Bean
3. 根据条件决定是否激活

```java
@Slf4j
@AutoConfiguration                          // Spring Boot 3.x 注解
@EnableConfigurationProperties              // 启用配置属性绑定
@ConditionalOnProperty(prefix = "ssh-jdbc.tunnel", name = "host")  // 条件装配
public class SshJdbcAutoConfiguration {

    // 1. 配置属性 Bean
    @Bean
    @ConfigurationProperties(prefix = "ssh-jdbc.tunnel")
    public SshTunnelProperties sshJdbcTunnelProperties() {
        return new SshTunnelProperties();
    }

    // 2. 核心服务 Bean
    @Bean
    public SshTunnelService sshJdbcTunnelService(SshTunnelProperties props) {
        SshTunnelService service = new SshTunnelService(props);
        service.init();
        return service;
    }

    // 3. 可选依赖使用 ObjectProvider
    @Bean
    public SshJdbcRegistry sshJdbcRegistry(
            SshTunnelService tunnelService,
            ObjectProvider<ConnectionInfoProvider> providerOpt,     // 可选
            ObjectProvider<DataSourceCustomizer> customizerOpt) {   // 可选

        ConnectionInfoProvider provider = providerOpt.getIfAvailable();
        DataSourceCustomizer customizer = customizerOpt.getIfAvailable();
        return new SshJdbcRegistry(tunnelService, customizer, provider);
    }
}
```

### 步骤 4：编写配置属性类

```java
@Data
public class SshTunnelProperties {
    private String host;
    private int port = 22;
    private String user;
    private String privateKeyPath;
    private String privateKeyPassphrase;
    private int maxConnections = 50;
    private long idleTimeoutMs = 600000;
}
```

### 步骤 5：注册自动配置

创建文件（**这一步容易被遗忘，没有它 Starter 不会生效**）：

```
src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件内容（每行一个全限定类名）：

```
com.github.topxiao.sshjdbc.autoconfigure.SshJdbcAutoConfiguration
```

> **Spring Boot 2.x vs 3.x 区别**：
> - Spring Boot 2.x：`META-INF/spring.factories`，格式为 `key=value`
> - Spring Boot 3.x：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，每行一个类名

### 步骤 6：编写测试

自动配置测试使用 `ApplicationContextRunner`：

```java
class SshJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SshJdbcAutoConfiguration.class));

    @Test
    void shouldNotActivateWithoutHost() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SshTunnelService.class);
        });
    }

    @Test
    void shouldActivateWhenHostSet() {
        runner
            .withPropertyValues(
                "ssh-jdbc.tunnel.host=127.0.0.1",
                "ssh-jdbc.tunnel.port=22",
                "ssh-jdbc.tunnel.user=test",
                "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(SshTunnelService.class);
                assertThat(context).hasSingleBean(SshJdbcRegistry.class);
            });
    }
}
```

---

## 4. pom.xml 配置要点

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- 1. 继承 Spring Boot Parent -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.4</version>
        <relativePath/>
    </parent>

    <!-- 2. GAV 坐标（JitPack 用 com.github.<用户名>） -->
    <groupId>com.github.topxiao</groupId>
    <artifactId>ssh-jdbc-spring-boot-starter</artifactId>
    <version>0.2.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- 3. 必须的依赖：自动配置 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>

        <!-- 4. Starter 的功能依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.hierynomus</groupId>
            <artifactId>sshj</artifactId>
            <version>0.38.0</version>
        </dependency>

        <!-- 5. provided 范围：用户项目自己带 -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- 6. optional：不传递给用户 -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- 7. 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 依赖 scope 说明

| scope | 含义 | 使用场景 |
|-------|------|----------|
| **compile**（默认） | 打包进 Starter，传递给用户 | Starter 核心功能依赖 |
| **provided** | 编译用，不打包，用户自己提供 | 数据库驱动等，用户可能用不同版本 |
| **optional** | 打包进 Starter，但不传递给用户 | Lombok 等编译期工具 |
| **test** | 仅测试用 | 测试框架 |

---

## 5. 自动配置注册（Spring Boot 3.x）

### 注册文件位置

```
src/main/resources/META-INF/spring/
    org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 文件内容

每行一个自动配置类的全限定名：

```
com.github.topxiao.sshjdbc.autoconfigure.SshJdbcAutoConfiguration
```

如果有多个自动配置类，每行写一个：

```
com.github.topxiao.sshjdbc.autoconfigure.SshJdbcAutoConfiguration
com.github.topxiao.sshjdbc.autoconfigure.AnotherAutoConfiguration
```

### 工作原理

Spring Boot 启动时会扫描所有 JAR 包中的这个文件，把里面列出的类当作自动配置候选类，然后根据条件装配决定是否实例化。

---

## 6. 条件装配

Starter 只有在特定条件下才应该激活，避免在不需要的项目中产生副作用。

### 常用条件注解

| 注解 | 含义 | 示例 |
|------|------|------|
| `@ConditionalOnProperty` | 配置项存在/有特定值时激活 | `@ConditionalOnProperty(prefix="ssh-jdbc.tunnel", name="host")` |
| `@ConditionalOnClass` | classpath 中存在某个类时激活 | `@ConditionalOnClass(DataSource.class)` |
| `@ConditionalOnMissingBean` | 容器中没有某个 Bean 时才创建 | `@ConditionalOnMissingBean(SshTunnelService.class)` |
| `@ConditionalOnBean` | 容器中存在某个 Bean 时才激活 | `@ConditionalOnBean(DataSource.class)` |

### 最佳实践

**必须有条件装配**。如果没有条件，Starter 会在所有引入它的项目中都激活，导致冲突。

```java
// 推荐：用户必须配置了 ssh-jdbc.tunnel.host 才激活
@ConditionalOnProperty(prefix = "ssh-jdbc.tunnel", name = "host")

// 不推荐：无条件激活
// @AutoConfiguration  ← 不要单独使用，务必配合条件注解
```

---

## 7. 打包发布到 GitHub

### 7.1 本地验证

发布前先确保本地构建通过：

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 打包（跳过测试）
mvn package -DskipTests

# 检查生成的 JAR
ls target/*.jar
```

### 7.2 设置 pom.xml 版本号

发布版本不能带 `-SNAPSHOT` 后缀。JitPack 根据 git tag 构建版本。

```bash
# 方法 1：直接修改 pom.xml 中的 <version>
# 将 <version>0.2.0-SNAPSHOT</version> 改为 <version>0.2.0</version>

# 方法 2：使用 Maven 命令
mvn versions:set -DnewVersion=0.2.0
mvn versions:commit
```

### 7.3 提交并打 Tag

```bash
# 添加所有变更
git add -A
git commit -m "release: v0.2.0"

# 打 tag（tag 名就是 JitPack 版本号）
git tag v0.2.0

# 推送代码和 tag
git push origin main
git push origin v0.2.0
```

### 7.4 Tag 命名规范

| Tag 格式 | 对应 Maven 版本 | 说明 |
|----------|----------------|------|
| `v0.1.0` | `v0.1.0` | 正式发布 |
| `v0.2.0` | `v0.2.0` | 正式发布 |
| `v1.0.0-RC1` | `v1.0.0-RC1` | 预发布 |

> Tag 名就是依赖的 version。JitPack 会原样使用 tag 名作为版本号，所以 `v0.2.0` tag → Maven version 写 `v0.2.0`。

---

## 8. 通过 JitPack 构建远程依赖

[JitPack](https://jitpack.io) 是一个免费的 Maven 仓库，可以直接从 GitHub 仓库构建 JAR 包。无需配置 Nexus/OSSRH 等复杂流程。

### 8.1 工作原理

```
用户 pom.xml 请求 com.github.topxiao:ssh-jdbc-spring-boot-starter:v0.2.0
    ↓
Maven 找不到本地/中央仓库
    ↓
请求 JitPack 仓库 (https://jitpack.io)
    ↓
JitPack 克隆 GitHub 仓库 → checkout tag v0.2.0 → mvn package
    ↓
返回构建好的 JAR 给用户
（后续请求使用缓存，不再重复构建）
```

### 8.2 首次构建触发

推送 tag 后，JitPack 不会自动构建。需要**手动触发**首次构建：

**方式 1：在浏览器打开**

```
https://jitpack.io/#com.github.topxiao/ssh-jdbc-spring-boot-starter/v0.2.0
```

等待页面显示绿色 "Status: ok" 即表示构建成功。

**方式 2：直接在项目中使用**

在用户项目的 pom.xml 中添加依赖后执行 `mvn compile`，JitPack 会自动触发构建。首次构建较慢（1-3 分钟），后续使用缓存。

**方式 3：查看构建日志**

```
https://jitpack.io/#com.github.topxiao/ssh-jdbc-spring-boot-starter/v0.2.0
```

点击 "Log" 可以看到完整的 Maven 构建输出。如果构建失败，日志会告诉你具体原因。

### 8.3 可选：添加 .jitpack.yml

如果 JitPack 默认的 Java 版本不对，可以在项目根目录创建 `.jitpack.yml`：

```yaml
# .jitpack.yml
jdk:
  - openjdk21
```

支持的 JDK 版本：`openjdk8`, `openjdk11`, `openjdk17`, `openjdk21` 等。

### 8.4 JitPack 构建要求

- 仓库必须是 **public** 的 GitHub 仓库
- 必须有对应的 **git tag**
- `pom.xml` 必须能正常 `mvn package`
- **pom.xml 的 version 不要带 `-SNAPSHOT`**（tag 对应的 commit 上）

---

## 9. 其他人如何引入使用

### 9.1 添加 JitPack 仓库

在用户项目的 `pom.xml` 中添加 `<repositories>`：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### 9.2 添加依赖

```xml
<dependency>
    <groupId>com.github.topxiao</groupId>
    <artifactId>ssh-jdbc-spring-boot-starter</artifactId>
    <version>v0.2.0</version>
</dependency>
```

> **groupId 规则**：`com.github.<GitHub用户名>`
> **artifactId**：GitHub 仓库名
> **version**：git tag 名（前面加了 `v` 前缀）

### 9.3 添加配置

在 `application.yml` 中配置：

```yaml
ssh-jdbc:
  tunnel:
    host: your-ssh-server.com     # SSH 跳板机地址（必填，填了 Starter 才激活）
    port: 22
    user: ssh-user
    private-key-path: /path/to/id_rsa
  datasources:
    primary:                       # 数据源名称，可自定义
      host: 10.0.1.100            # 远程数据库地址
      port: 5432
      database: mydb
      username: postgres
      password: secret
```

### 9.4 使用 Starter 提供的 Bean

```java
@RestController
public class MyController {

    private final SshJdbcRegistry registry;

    public MyController(SshJdbcRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> getUsers() {
        SshJdbcTemplate template = registry.getTemplate("primary");
        return template.queryForList(
            "SELECT * FROM users WHERE org = :org",
            Map.of("org", "engineering"));
    }
}
```

### 9.5 完整的用户项目 pom.xml 示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.4</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>1.0.0</version>

    <!-- 1. 添加 JitPack 仓库 -->
    <repositories>
        <repository>
            <id>jitpack.io</id>
            <url>https://jitpack.io</url>
        </repository>
    </repositories>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- 2. 添加 Starter 依赖 -->
        <dependency>
            <groupId>com.github.topxiao</groupId>
            <artifactId>ssh-jdbc-spring-boot-starter</artifactId>
            <version>v0.2.0</version>
        </dependency>

        <!-- 3. 数据库驱动（Starter 的 provided 依赖，用户必须自己加） -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>
</project>
```

---

## 10. 版本升级流程

每次发新版需要做以下操作：

### 10.1 修改代码

在 `develop` 分支开发，测试通过后合并到 `main`。

### 10.2 更新版本号

```bash
# 修改 pom.xml 中的 version
# 从 0.2.0-SNAPSHOT → 0.3.0（或保持 SNAPSHOT，tag 时再改）
```

### 10.3 提交、打 Tag、推送

```bash
# 1. 确保 pom.xml 版本号不带 -SNAPSHOT（tag 对应的 commit 上）
git add pom.xml
git commit -m "release: v0.3.0"

# 2. 打 tag
git tag v0.3.0

# 3. 推送
git push origin main
git push origin v0.3.0
```

### 10.4 触发 JitPack 构建

浏览器打开：

```
https://jitpack.io/#com.github.topxiao/ssh-jdbc-spring-boot-starter/v0.3.0
```

等待构建成功。

### 10.5 用户更新版本

用户只需修改依赖版本号：

```xml
<version>v0.3.0</version>
```

### 10.6 发版后的后续工作

```bash
# 在 main 分支将 pom.xml 版本号改回 SNAPSHOT，为下个版本做准备
# <version>0.4.0-SNAPSHOT</version>
git add pom.xml
git commit -m "chore: bump version to 0.4.0-SNAPSHOT"
git push origin main
```

---

## 11. 常见问题排查

### Q1: JitPack 构建失败

**查看日志**：在 `https://jitpack.io/#com.github.topxiao/ssh-jdbc-spring-boot-starter/v0.2.0` 点击 Log。

**常见原因**：

| 原因 | 解决方案 |
|------|----------|
| Java 版本不匹配 | 添加 `.jitpack.yml` 指定 JDK 版本 |
| pom.xml 有 `-SNAPSHOT` | tag 对应的 commit 上 pom.xml 版本号不能带 SNAPSHOT |
| 测试失败 | 先 `mvn test` 确认本地通过，或推送时用 `mvn package -DskipTests` |
| 依赖找不到 | 检查是否有私有仓库依赖，JitPack 只能访问公共仓库 |

### Q2: 引入 Starter 后没有生效

**检查清单**：

1. `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件是否存在且内容正确？
2. 自动配置类上的条件注解是否满足？（例如 `@ConditionalOnProperty` 的配置是否写了）
3. 依赖的 scope 是否正确？（如果是 `provided` 或 `test`，需要用户自己添加）
4. Spring Boot 版本是否兼容？（Spring Boot 3.x 和 2.x 的注册文件不同）

### Q3: IntelliJ 报红但能编译通过

```
# 清除缓存重新导入
mvn clean
# IntelliJ: File → Invalidate Caches → Invalidate and Restart
```

### Q4: 如何删除一个错误的 tag

```bash
# 删除本地 tag
git tag -d v0.1.0

# 删除远程 tag
git push origin :refs/tags/v0.1.0
```

> JitPack 会缓存已构建的版本。删除 tag 后需要在 JitPack 网站上清除缓存才能重新构建。

### Q5: 多个 Starter 嵌套依赖

如果 Starter A 依赖 Starter B：

```xml
<!-- Starter A 的 pom.xml -->
<dependency>
    <groupId>com.github.topxiao</groupId>
    <artifactId>starter-b</artifactId>
    <version>v0.1.0</version>
</dependency>
```

用户只需引入 Starter A，Maven 会自动传递引入 Starter B。

---

## 附录 A：一键检查清单

### 发布前检查

- [ ] `mvn test` 全部通过
- [ ] `pom.xml` 版本号不含 `-SNAPSHOT`
- [ ] `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件存在且内容正确
- [ ] `git tag v0.x.0` 已打上
- [ ] `git push origin main --tags` 已推送
- [ ] JitPack 页面显示构建成功

### 用户使用检查

- [ ] `pom.xml` 添加了 `<repository>` jitpack.io
- [ ] `pom.xml` 添加了 `<dependency>`
- [ ] `application.yml` 配置了必填项
- [ ] 数据库驱动（如 postgresql）已添加

---

## 附录 B：命令速查

```bash
# ===== 开发阶段 =====
mvn compile                          # 编译
mvn test                             # 运行全部测试
mvn test -Dtest=ExecutionContextTest # 运行单个测试类
mvn package -DskipTests              # 打包

# ===== 发布阶段 =====
mvn versions:set -DnewVersion=0.2.0  # 设置版本号
mvn versions:commit                   # 确认版本号
git add -A && git commit -m "release: v0.2.0"
git tag v0.2.0                        # 打 tag
git push origin main                  # 推送代码
git push origin v0.2.0                # 推送 tag

# ===== 版本管理 =====
git tag                              # 查看所有 tag
git tag -d v0.1.0                    # 删除本地 tag
git push origin :refs/tags/v0.1.0    # 删除远程 tag
```
