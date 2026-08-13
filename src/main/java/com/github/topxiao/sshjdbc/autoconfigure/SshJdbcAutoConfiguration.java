package com.github.topxiao.sshjdbc.autoconfigure;

import com.github.topxiao.sshjdbc.context.ConnectionInfoResolver;
import com.github.topxiao.sshjdbc.context.SshJdbc;
import com.github.topxiao.sshjdbc.context.SshJdbcRegistry;
import com.github.topxiao.sshjdbc.jdbc.DataSourceCustomizer;
import com.github.topxiao.sshjdbc.provider.ConnectionInfo;
import com.github.topxiao.sshjdbc.provider.ConnectionInfoProvider;
import com.github.topxiao.sshjdbc.provider.CurrentConnectionInfoProvider;
import com.github.topxiao.sshjdbc.tunnel.SshTunnelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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
        return new SshTunnelService(props);
    }

    @Bean
    public SshJdbcRegistry sshJdbcRegistry(
            SshDataSourceProperties dataSourceProps,
            SshTunnelService sshJdbcTunnelService,
            ObjectProvider<ConnectionInfoProvider> providerOpt,
            ObjectProvider<DataSourceCustomizer> customizerOpt,
            ObjectProvider<List<ConnectionInfoResolver>> resolversOpt,
            ObjectProvider<CurrentConnectionInfoProvider> currentConnectionInfoProviderOpt) {

        DataSourceCustomizer customizer = customizerOpt.getIfAvailable();
        ConnectionInfoProvider provider = providerOpt.getIfAvailable();

        SshJdbcRegistry registry = new SshJdbcRegistry(
                sshJdbcTunnelService, customizer, provider,
                dataSourceProps.getMaxCachedDatasources());

        // 1. Register yml static datasources
        for (Map.Entry<String, SshDataSourceProperties.DataSourceProperties> entry
                : dataSourceProps.getDatasources().entrySet()) {
            SshDataSourceProperties.DataSourceProperties ds = entry.getValue();
            registry.register(entry.getKey(), new ConnectionInfo(
                    ds.getHost(), ds.getPort(), ds.getDatabase(),
                    ds.getUsername(), ds.getPassword()));
        }

        // 2. Dynamic provider entries override static entries and are refresh-managed.
        if (provider != null) {
            Map<String, ConnectionInfo> dynamic = provider.provide();
            if (dynamic != null) {
                for (Map.Entry<String, ConnectionInfo> entry : dynamic.entrySet()) {
                    if (registry.getDatasourceNames().contains(entry.getKey())) {
                        log.info("动态数据源 '{}' 覆盖了静态配置", entry.getKey());
                    } else {
                        log.info("添加动态数据源 '{}'", entry.getKey());
                    }
                    registry.registerProviderManaged(entry.getKey(), entry.getValue());
                }
            }
        }

        // 3. Initialize SshJdbc static facade
        List<ConnectionInfoResolver> resolvers = resolversOpt.getIfAvailable();
        SshJdbc.init(registry, resolvers != null ? resolvers : List.of(),
                currentConnectionInfoProviderOpt.getIfAvailable());

        return registry;
    }
}
