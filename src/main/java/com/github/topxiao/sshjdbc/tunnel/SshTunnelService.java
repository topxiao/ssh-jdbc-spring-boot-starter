package com.github.topxiao.sshjdbc.tunnel;

import com.github.topxiao.sshjdbc.autoconfigure.SshTunnelProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages a pool of SSH tunnels keyed by {@code remoteHost:remotePort}.
 *
 * <p>Tunnels are cached in a {@link ConcurrentHashMap}. Creation is
 * synchronised to prevent duplicate tunnels for the same remote endpoint.
 * A background thread periodically removes tunnels that have been idle
 * longer than {@link SshTunnelProperties#getIdleTimeoutMs()}.
 */
@Slf4j
public class SshTunnelService {

    private final SshTunnelProperties props;
    private final ConcurrentHashMap<String, TunnelInfo> tunnelCache = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupExecutor;

    public SshTunnelService(SshTunnelProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssh-tunnel-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupIdleTunnels, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * Returns the local port for an existing tunnel to the given remote
     * endpoint, or creates a new one.
     *
     * @param remoteHost remote database host
     * @param remotePort remote database port
     * @return local port that forwards traffic to {@code remoteHost:remotePort}
     * @throws IOException if the SSH connection cannot be established
     * @throws IllegalStateException if the max-connections limit is reached
     */
    public int createOrGetTunnel(String remoteHost, int remotePort) throws IOException {
        String key = buildTunnelKey(remoteHost, remotePort);

        // Fast path — already cached and connected
        TunnelInfo tunnel = tunnelCache.get(key);
        if (tunnel != null && tunnel.getSshClient().isConnected()) {
            tunnel.touch();
            return tunnel.getLocalPort();
        }

        // Slow path — synchronised creation
        synchronized (tunnelCache) {
            tunnel = tunnelCache.get(key);
            if (tunnel != null && tunnel.getSshClient().isConnected()) {
                tunnel.touch();
                return tunnel.getLocalPort();
            }

            if (tunnelCache.size() >= props.getMaxConnections()) {
                throw new IllegalStateException(
                        "已达到最大 SSH 隧道连接数: " + props.getMaxConnections()
                        + "，当前活跃隧道: " + tunnelCache.keySet());
            }

            // Close stale entry if present
            if (tunnel != null) {
                closeTunnel(key, tunnel);
            }

            TunnelInfo newTunnel = createTunnel(remoteHost, remotePort);
            tunnelCache.put(key, newTunnel);
            return newTunnel.getLocalPort();
        }
    }

    private TunnelInfo createTunnel(String remoteHost, int remotePort) throws IOException {
        SSHClient ssh = new SSHClient();
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.connect(props.getHost(), props.getPort());

        KeyProvider keyProvider = ssh.loadKeys(props.getPrivateKeyPath(), props.getPrivateKeyPassphrase());
        ssh.authPublickey(props.getUser(), keyProvider);

        ServerSocket ss = new ServerSocket(0);
        int localPort = ss.getLocalPort();

        Parameters params = new Parameters("localhost", localPort, remoteHost, remotePort);
        Thread forwarderThread = new Thread(() -> {
            try {
                ssh.newLocalPortForwarder(params, ss).listen();
            } catch (IOException e) {
                log.error("SSH 隧道监听失败 ({}:{})", remoteHost, remotePort, e);
            }
        }, "ssh-forward-" + remoteHost + ":" + remotePort);
        forwarderThread.setDaemon(true);
        forwarderThread.start();

        log.info("SSH 隧道已建立: localhost:{} -> {}:{}", localPort, remoteHost, remotePort);
        return new TunnelInfo(localPort, ssh);
    }

    private void closeTunnel(String key, TunnelInfo tunnel) {
        try {
            tunnel.getSshClient().disconnect();
        } catch (IOException ignored) {
            // best-effort close
        }
        tunnelCache.remove(key, tunnel);
        log.debug("SSH 隧道已关闭: {}", key);
    }

    private void cleanupIdleTunnels() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TunnelInfo> entry : tunnelCache.entrySet()) {
            TunnelInfo tunnel = entry.getValue();
            if (now - tunnel.getLastUsedTime() > props.getIdleTimeoutMs()) {
                log.info("清理空闲 SSH 隧道: {}", entry.getKey());
                closeTunnel(entry.getKey(), tunnel);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
        }
        for (Map.Entry<String, TunnelInfo> entry : tunnelCache.entrySet()) {
            closeTunnel(entry.getKey(), entry.getValue());
        }
        log.info("所有 SSH 隧道已关闭");
    }

    /** Build a cache key from the remote host and port. Package-visible for testing. */
    static String buildTunnelKey(String remoteHost, int remotePort) {
        return remoteHost + ":" + remotePort;
    }
}
