package io.quarkus.devspace.server;

import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;

public class HeaderOrCookieSessionMatcher implements RequestSessionMatcher {
    private final String name;

    public HeaderOrCookieSessionMatcher(String name) {
        this.name = name;
    }

    @Override
    public String match(RoutingContext ctx) {
        String sessionId = ctx.request().getHeader(name);
        if (sessionId == null) {
            Cookie cookie = ctx.request().getCookie(name);
            if (cookie != null) {
                sessionId = cookie.getValue();
            }
        }
        return sessionId;
    }
}
