package com.github.topxiao.sshjdbc.tunnel;

import com.github.topxiao.sshjdbc.autoconfigure.SshTunnelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.schmizz.sshj.SSHClient;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class SshTunnelServiceTest {

    private SshTunnelService service;

    @BeforeEach
    void setUp() {
        SshTunnelProperties props = new SshTunnelProperties();
        props.setHost("127.0.0.1");
        props.setPort(22);
        props.setUser("test");
        props.setPrivateKeyPath("/nonexistent/id_rsa");
        props.setMaxConnections(2);
        props.setIdleTimeoutMs(60000L);
        service = new SshTunnelService(props);
    }

    @Test
    void shouldInitializeAndShutdownWithoutError() {
        assertDoesNotThrow(() -> {
            service.init();
            service.shutdown();
        });
    }

    @Test
    void shouldBuildTunnelKey() {
        assertEquals("10.0.1.100:5432", SshTunnelService.buildTunnelKey("10.0.1.100", 5432));
    }

    @Test
    void shouldRespectMaxConnectionsConfig() {
        SshTunnelProperties props = new SshTunnelProperties();
        props.setMaxConnections(5);
        assertEquals(5, props.getMaxConnections());
    }

    @Test
    void shouldInitializeIdempotently() {
        service.init();
        assertDoesNotThrow(service::init);
        service.shutdown();
    }

    @Test
    void shouldHaveCorrectIdleTimeoutDefault() {
        SshTunnelProperties props = new SshTunnelProperties();
        assertEquals(600_000L, props.getIdleTimeoutMs());
    }

    @Test
    void shouldBuildTunnelKeyWithDifferentFormats() {
        assertEquals("my-host.example.com:3306", SshTunnelService.buildTunnelKey("my-host.example.com", 3306));
        assertEquals("192.168.1.1:22", SshTunnelService.buildTunnelKey("192.168.1.1", 22));
    }

    @Test
    void shouldUsePinnedFingerprintWhenConfigured() throws Exception {
        SshTunnelProperties props = new SshTunnelProperties();
        props.setHostKeyFingerprint("SHA256:test");
        SshTunnelService tunnelService = new SshTunnelService(props);
        SSHClient ssh = org.mockito.Mockito.mock(SSHClient.class);

        tunnelService.configureHostKeyVerification(ssh);

        org.mockito.Mockito.verify(ssh).addHostKeyVerifier("SHA256:test");
    }

    @Test
    void shouldBindForwardingSocketToLoopbackOnly() throws Exception {
        try (ServerSocket socket = SshTunnelService.createLoopbackServerSocket()) {
            assertTrue(socket.getInetAddress().isLoopbackAddress());
        }
    }
}
