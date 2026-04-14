package com.github.topxiao.sshjdbc.provider;

import java.util.Map;

/**
 * Strategy interface for resolving {@link ConnectionInfo} instances at runtime.
 *
 * <p>Implementations return a map where the key is a logical datasource name
 * (e.g. "primary", "readonly") and the value is the corresponding connection details.
 *
 * <p>This is a {@link FunctionalInterface} so it can be supplied as a lambda or
 * method reference.
 */
@FunctionalInterface
public interface ConnectionInfoProvider {
    Map<String, ConnectionInfo> provide();
}
