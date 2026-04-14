package com.github.topxiao.sshjdbc.autoconfigure;

import com.github.topxiao.sshjdbc.context.SshJdbcRegistry;
import com.github.topxiao.sshjdbc.provider.ConnectionInfoProvider;
import com.github.topxiao.sshjdbc.tunnel.SshTunnelService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SshJdbcAutoConfiguration}.
 *
 * <p>Uses {@link ApplicationContextRunner} to verify bean registration and
 * property binding without establishing real SSH connections (Option B).
 *
 * <p>Tests that configure static datasources in YAML are intentionally omitted
 * because they would trigger real SSH tunnel creation in the
 * {@code sshJdbcRegistry} bean.  Datasource property binding is covered by
 * {@link SshDataSourcePropertiesTest} and the actual merge logic is verified
 * through integration tests against a real SSH server.
 */
class SshJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SshJdbcAutoConfiguration.class));

    // ---- Activation tests ----

    @Test
    void shouldNotActivateWithoutTunnelHost() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SshTunnelService.class);
            assertThat(context).doesNotHaveBean(SshJdbcRegistry.class);
            assertThat(context).doesNotHaveBean(SshTunnelProperties.class);
            assertThat(context).doesNotHaveBean(SshDataSourceProperties.class);
        });
    }

    @Test
    void shouldActivateWithMinimalConfig() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(SshTunnelService.class);
                    assertThat(context).hasSingleBean(SshTunnelProperties.class);
                    assertThat(context).hasSingleBean(SshDataSourceProperties.class);
                    assertThat(context).hasSingleBean(SshJdbcRegistry.class);
                });
    }

    // ---- Tunnel property binding tests ----

    @Test
    void shouldBindTunnelProperties() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=192.168.1.100",
                        "ssh-jdbc.tunnel.port=2222",
                        "ssh-jdbc.tunnel.user=deploy",
                        "ssh-jdbc.tunnel.private-key-path=/home/deploy/.ssh/id_ed25519",
                        "ssh-jdbc.tunnel.private-key-passphrase=s3cret",
                        "ssh-jdbc.tunnel.max-connections=10",
                        "ssh-jdbc.tunnel.idle-timeout-ms=300000"
                )
                .run(context -> {
                    SshTunnelProperties props =
                            context.getBean(SshTunnelProperties.class);
                    assertThat(props.getHost()).isEqualTo("192.168.1.100");
                    assertThat(props.getPort()).isEqualTo(2222);
                    assertThat(props.getUser()).isEqualTo("deploy");
                    assertThat(props.getPrivateKeyPath())
                            .isEqualTo("/home/deploy/.ssh/id_ed25519");
                    assertThat(props.getPrivateKeyPassphrase()).isEqualTo("s3cret");
                    assertThat(props.getMaxConnections()).isEqualTo(10);
                    assertThat(props.getIdleTimeoutMs()).isEqualTo(300_000L);
                });
    }

    @Test
    void shouldUseDefaultPropertyValues() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .run(context -> {
                    SshTunnelProperties props =
                            context.getBean(SshTunnelProperties.class);
                    assertThat(props.getPort()).isEqualTo(22);
                    assertThat(props.getMaxConnections()).isEqualTo(50);
                    assertThat(props.getIdleTimeoutMs()).isEqualTo(600_000L);
                    assertThat(props.getPrivateKeyPassphrase()).isNull();
                });
    }

    // ---- DataSource properties default ----

    @Test
    void shouldCreateEmptyDataSourcePropertiesByDefault() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .run(context -> {
                    SshDataSourceProperties props =
                            context.getBean(SshDataSourceProperties.class);
                    assertThat(props.getDatasources()).isEmpty();
                });
    }

    // ---- Registry with empty datasources ----

    @Test
    void shouldCreateEmptyRegistryWithNoDatasources() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .run(context -> {
                    SshJdbcRegistry registry = context.getBean(SshJdbcRegistry.class);
                    assertThat(registry.getDatasourceNames()).isEmpty();
                });
    }

    // ---- ConnectionInfoProvider wiring ----

    @Test
    void shouldWireConnectionInfoProviderWhenPresent() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .withBean(ConnectionInfoProvider.class, () -> Collections::emptyMap)
                .run(context -> {
                    assertThat(context).hasSingleBean(ConnectionInfoProvider.class);
                    // Empty provider yields empty registry (no tunnels attempted)
                    SshJdbcRegistry registry = context.getBean(SshJdbcRegistry.class);
                    assertThat(registry.getDatasourceNames()).isEmpty();
                });
    }

    @Test
    void shouldNotRequireConnectionInfoProvider() {
        runner
                .withPropertyValues(
                        "ssh-jdbc.tunnel.host=127.0.0.1",
                        "ssh-jdbc.tunnel.port=22",
                        "ssh-jdbc.tunnel.user=test",
                        "ssh-jdbc.tunnel.private-key-path=/tmp/id_rsa"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ConnectionInfoProvider.class);
                    // Registry still created, just empty
                    assertThat(context).hasSingleBean(SshJdbcRegistry.class);
                });
    }
}
