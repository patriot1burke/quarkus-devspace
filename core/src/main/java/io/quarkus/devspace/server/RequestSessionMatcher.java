package io.quarkus.devspace.server;

import io.vertx.ext.web.RoutingContext;

/**
 *
 */
public interface RequestSessionMatcher {
    String match(RoutingContext ctx);
}
