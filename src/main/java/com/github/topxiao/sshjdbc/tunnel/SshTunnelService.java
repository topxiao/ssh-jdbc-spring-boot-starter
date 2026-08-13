package com.github.topxiao.sshjdbc.tunnel;

import com.github.topxiao.sshjdbc.autoconfigure.SshTunnelProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
        if (cleanupExecutor != null) {
            return;
        }
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
        return createOrGetTunnel(remoteHost, remotePort, false);
    }

    /** Acquire a tunnel lease for a long-lived datasource. */
    public int acquireTunnel(String remoteHost, int remotePort) throws IOException {
        return createOrGetTunnel(remoteHost, remotePort, true);
    }

    /** Release a datasource's tunnel lease. */
    public void releaseTunnel(String remoteHost, int remotePort) {
        TunnelInfo tunnel = tunnelCache.get(buildTunnelKey(remoteHost, remotePort));
        if (tunnel != null) {
            tunnel.release();
        }
    }

    private int createOrGetTunnel(String remoteHost, int remotePort, boolean acquire) throws IOException {
        String key = buildTunnelKey(remoteHost, remotePort);

        // Fast path — already cached and connected
        TunnelInfo tunnel = tunnelCache.get(key);
        if (tunnel != null && tunnel.isUsable()) {
            if (acquire) tunnel.acquire(); else tunnel.touch();
            return tunnel.getLocalPort();
        }

        // Slow path — synchronised creation
        synchronized (tunnelCache) {
            tunnel = tunnelCache.get(key);
            if (tunnel != null && tunnel.isUsable()) {
                if (acquire) tunnel.acquire(); else tunnel.touch();
                return tunnel.getLocalPort();
            }

            // Remove a stale entry before enforcing the capacity limit.
            if (tunnel != null) {
                closeTunnel(key, tunnel);
            }

            if (tunnelCache.size() >= props.getMaxConnections()) {
                throw new IllegalStateException(
                        "已达到最大 SSH 隧道连接数: " + props.getMaxConnections()
                        + "，当前活跃隧道: " + tunnelCache.keySet());
            }

            TunnelInfo newTunnel = createTunnel(key, remoteHost, remotePort, acquire);
            return newTunnel.getLocalPort();
        }
    }

    private TunnelInfo createTunnel(String key, String remoteHost, int remotePort,
                                    boolean acquire) throws IOException {
        SSHClient ssh = new SSHClient();
        ServerSocket ss = null;
        boolean success = false;
        try {
            ssh.setConnectTimeout(props.getConnectTimeoutMs());
            ssh.setTimeout(props.getTimeoutMs());
            configureHostKeyVerification(ssh);
            ssh.connect(props.getHost(), props.getPort());

            KeyProvider keyProvider = ssh.loadKeys(props.getPrivateKeyPath(), props.getPrivateKeyPassphrase());
            ssh.authPublickey(props.getUser(), keyProvider);

            ss = createLoopbackServerSocket();
            int localPort = ss.getLocalPort();
            Parameters params = new Parameters("localhost", localPort, remoteHost, remotePort);
            var forwarder = ssh.newLocalPortForwarder(params, ss);
            TunnelInfo tunnel = new TunnelInfo(localPort, ssh, ss, forwarder);
            if (acquire) {
                tunnel.acquire();
            }
            tunnelCache.put(key, tunnel);

            Thread forwarderThread = new Thread(() -> {
                try {
                    forwarder.listen();
                } catch (IOException e) {
                    tunnel.markFailed();
                    if (tunnelCache.remove(key, tunnel)) {
                        tunnel.close();
                    }
                    log.error("SSH 隧道监听失败 ({}:{})", remoteHost, remotePort, e);
                }
            }, "ssh-forward-" + remoteHost + ":" + remotePort);
            forwarderThread.setDaemon(true);
            forwarderThread.start();

            success = true;
            log.info("SSH 隧道已建立: localhost:{} -> {}:{}", localPort, remoteHost, remotePort);
            return tunnel;
        } finally {
            if (!success) {
                tunnelCache.remove(key);
                if (ss != null) {
                    try { ss.close(); } catch (IOException ignored) { }
                }
                try { ssh.disconnect(); } catch (IOException ignored) { }
            }
        }
    }

    void configureHostKeyVerification(SSHClient ssh) throws IOException {
        if (props.getHostKeyFingerprint() != null && !props.getHostKeyFingerprint().isBlank()) {
            ssh.addHostKeyVerifier(props.getHostKeyFingerprint());
        } else if (props.getKnownHostsPath() != null && !props.getKnownHostsPath().isBlank()) {
            ssh.loadKnownHosts(new File(props.getKnownHostsPath()));
        } else {
            ssh.loadKnownHosts();
        }
    }

    static ServerSocket createLoopbackServerSocket() throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        return serverSocket;
    }

    private void closeTunnel(String key, TunnelInfo tunnel) {
        tunnel.close();
        tunnelCache.remove(key, tunnel);
        log.debug("SSH 隧道已关闭: {}", key);
    }

    private void cleanupIdleTunnels() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, TunnelInfo> entry : tunnelCache.entrySet()) {
            TunnelInfo tunnel = entry.getValue();
            if (!tunnel.isInUse()
                    && now - tunnel.getLastUsedTime() > props.getIdleTimeoutMs()) {
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
