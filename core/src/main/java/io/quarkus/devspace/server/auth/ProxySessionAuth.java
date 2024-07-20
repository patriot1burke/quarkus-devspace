package io.quarkus.devspace.server.auth;

import io.quarkus.devspace.server.DevProxyServer;
import io.vertx.ext.web.RoutingContext;

public interface ProxySessionAuth {
    String BEARER_TOKEN_HEADER = "X-Bearer-Token";

    /**
     * @param ctx
     * @param success code to execute on successful connect
     */
    void authenticate(RoutingContext ctx, Runnable success);

    /**
     * If false, then response is already set
     *
     * @param ctx
     * @param session
     * @return
     */
    default boolean authorized(RoutingContext ctx, DevProxyServer.ProxySession session) {
        return session.validateToken(ctx);
    }

    default void propagateToken(RoutingContext ctx, DevProxyServer.ProxySession session) {
        ctx.response().putHeader(BEARER_TOKEN_HEADER, "Bearer " + session.getToken());

    }
}
