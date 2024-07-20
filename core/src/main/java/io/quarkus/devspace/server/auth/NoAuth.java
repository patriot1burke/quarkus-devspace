package io.quarkus.devspace.server.auth;

import io.quarkus.devspace.server.DevProxyServer;
import io.vertx.ext.web.RoutingContext;

public class NoAuth implements ProxySessionAuth {
    @Override
    public void authenticate(RoutingContext ctx, Runnable success) {
        success.run();
    }

    @Override
    public boolean authorized(RoutingContext ctx, DevProxyServer.ProxySession session) {
        return true;
    }

    @Override
    public void propagateToken(RoutingContext ctx, DevProxyServer.ProxySession session) {

    }
}
