package com.github.topxiao.sshjdbc.tunnel;

import lombok.Getter;
import net.schmizz.sshj.SSHClient;

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
    private volatile long lastUsedTime;

    TunnelInfo(int localPort, SSHClient sshClient) {
        this.localPort = localPort;
        this.sshClient = sshClient;
        this.lastUsedTime = System.currentTimeMillis();
    }

    /** Refresh the last-used timestamp (called on cache hit). */
    void touch() {
        this.lastUsedTime = System.currentTimeMillis();
    }
}
