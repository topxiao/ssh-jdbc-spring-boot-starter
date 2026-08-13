package com.github.topxiao.sshjdbc.tunnel;

import lombok.Getter;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds the state of a single SSH tunnel: the local port it listens on,
 * the underlying SSH client, and a timestamp for idle-timeout tracking.
 *
 * <p>Package-private by design — only {@link SshTunnelService} should
 * instantiate or mutate this class.
 */
@Getter
class TunnelInfo {

    private final int localPort;
    private final SSHClient sshClient;
    private final ServerSocket serverSocket;
    private final LocalPortForwarder forwarder;
    private final AtomicInteger referenceCount = new AtomicInteger();
    private volatile long lastUsedTime;
    private volatile boolean healthy = true;
    private volatile boolean closed;

    TunnelInfo(int localPort, SSHClient sshClient, ServerSocket serverSocket,
               LocalPortForwarder forwarder) {
        this.localPort = localPort;
        this.sshClient = sshClient;
        this.serverSocket = serverSocket;
        this.forwarder = forwarder;
        this.lastUsedTime = System.currentTimeMillis();
    }

    /** Refresh the last-used timestamp (called on cache hit). */
    void touch() {
        this.lastUsedTime = System.currentTimeMillis();
    }

    void acquire() {
        referenceCount.incrementAndGet();
        touch();
    }

    void release() {
        referenceCount.updateAndGet(value -> Math.max(0, value - 1));
        touch();
    }

    boolean isInUse() {
        return referenceCount.get() > 0;
    }

    boolean isUsable() {
        return healthy && !closed && sshClient.isConnected() && sshClient.isAuthenticated();
    }

    void markFailed() {
        healthy = false;
    }

    void close() {
        closed = true;
        try {
            forwarder.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best-effort close
        }
        try {
            sshClient.disconnect();
        } catch (IOException ignored) {
            // best-effort close
        }
    }
}
