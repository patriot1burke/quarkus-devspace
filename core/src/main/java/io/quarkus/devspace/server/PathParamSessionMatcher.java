package io.quarkus.devspace.server;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.vertx.ext.web.RoutingContext;

public class PathParamSessionMatcher implements RequestSessionMatcher {
    private final Pattern pattern;

    public PathParamSessionMatcher(String pathExpression) {
        String regex = pathExpression.replace("[]", "(?<service>[^/]+)");
        pattern = Pattern.compile(regex + ".*");
    }

    @Override
    public String match(RoutingContext ctx) {
        String path = ctx.normalizedPath();
        return match(path);
    }

    public String match(String path) {
        if (path == null)
            return null;
        Matcher matcher = pattern.matcher(path);
        if (matcher.matches()) {
            return matcher.group("service");
        } else {
            return null;
        }
    }
}
