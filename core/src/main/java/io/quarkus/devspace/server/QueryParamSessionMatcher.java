package io.quarkus.devspace.server;

import io.vertx.ext.web.RoutingContext;

public class QueryParamSessionMatcher implements RequestSessionMatcher {
    private final String name;

    public QueryParamSessionMatcher(String name) {
        this.name = name;
    }

    @Override
    public String match(RoutingContext ctx) {
        return ctx.queryParams().get(name);
    }
}
