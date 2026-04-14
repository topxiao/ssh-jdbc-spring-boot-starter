package com.github.topxiao.sshjdbc.context;

import com.github.topxiao.sshjdbc.provider.ConnectionInfo;

/**
 * Strategy interface for resolving {@link ConnectionInfo} from an
 * {@link ExecutionContext}.
 *
 * <p>Implementations are discovered as Spring beans. Multiple resolvers
 * are tried in {@code @Order} sequence; the first non-null result wins.
 *
 * <p>This is a {@link FunctionalInterface} so it can be supplied as a lambda.
 */
@FunctionalInterface
public interface ConnectionInfoResolver {

    /**
     * Resolve connection info from the given context.
     *
     * @param ctx the current execution context
     * @return resolved ConnectionInfo, or {@code null} if this resolver
     *         cannot handle the context
     */
    ConnectionInfo resolve(ExecutionContext ctx);
}
