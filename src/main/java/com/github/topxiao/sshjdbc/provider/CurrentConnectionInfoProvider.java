package com.github.topxiao.sshjdbc.provider;

/**
 * Supplies connection information for the current application execution.
 *
 * <p>Use this SPI when the consuming application already owns a request or
 * execution context. It avoids copying that context into the starter's optional
 * {@code ExecutionContext}. Returning {@code null} delegates to the legacy
 * context and resolver chain.</p>
 */
@FunctionalInterface
public interface CurrentConnectionInfoProvider {

    /** Return the current connection information, or {@code null} to fall back. */
    ConnectionInfo getCurrent();
}
