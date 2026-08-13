package com.github.topxiao.sshjdbc.autoconfigure;

import lombok.Data;
import lombok.ToString;

/**
 * Configuration properties for SSH tunnel connections.
 *
 * <p>Binds to the {@code ssh-jdbc.tunnel} prefix in application configuration.
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
    @ToString.Exclude
    private String privateKeyPassphrase;

    /** Optional pinned SSH host-key fingerprint (SHA256 or legacy MD5 form). */
    private String hostKeyFingerprint;

    /** Optional known_hosts file. Defaults to the current user's standard known_hosts. */
    private String knownHostsPath;

    /** SSH connect timeout in milliseconds. */
    private int connectTimeoutMs = 10_000;

    /** SSH socket timeout in milliseconds. */
    private int timeoutMs = 30_000;

    /** Maximum number of concurrent SSH tunnels (default 50). */
    private int maxConnections = 50;

    /** Idle timeout in milliseconds before a tunnel is cleaned up (default 10 min). */
    private long idleTimeoutMs = 600_000L;
}
