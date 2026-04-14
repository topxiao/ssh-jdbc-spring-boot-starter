package com.github.topxiao.sshjdbc.autoconfigure;

import lombok.Data;

/**
 * Configuration properties for SSH tunnel connections.
 *
 * <p>Binds to the {@code ssh.tunnel} prefix in application configuration.
 */
@Data
public class SshTunnelProperties {

    /** SSH server hostname. */
    private String host;

    /** SSH server port (default 22). */
    private int port = 22;

    /** SSH authentication username. */
    private String user;

    /** Path to the SSH private key file. */
    private String privateKeyPath;

    /** Passphrase for the private key (may be null). */
    private String privateKeyPassphrase;

    /** Maximum number of concurrent SSH tunnels (default 50). */
    private int maxConnections = 50;

    /** Idle timeout in milliseconds before a tunnel is cleaned up (default 10 min). */
    private long idleTimeoutMs = 600_000L;
}
