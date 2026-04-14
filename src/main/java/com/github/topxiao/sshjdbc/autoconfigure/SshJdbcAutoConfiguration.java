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
