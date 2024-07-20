package io.quarkus.devspace.server.openshift;

import java.util.Base64;

import io.quarkus.devspace.server.auth.ProxySessionAuth;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;

public class OpenshiftBasicAuth implements ProxySessionAuth {
    final HttpClient client;

    public OpenshiftBasicAuth(Vertx vertx) {
        HttpClientOptions options = new HttpClientOptions();
        options.setSsl(true).setTrustAll(true);
        options.setDefaultHost("oauth-openshift.openshift-authentication.svc.cluster.local");
        options.setDefaultPort(443);
        this.client = vertx.createHttpClient(options);
    }

    private static final String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

    @Override
    public void authenticate(RoutingContext ctx, Runnable success) {
        String authorizationHeader = ctx.request().getHeader("Authorization");
        if (authorizationHeader == null || !authorizationHeader.startsWith("Basic")) {
            ctx.response().setStatusCode(401).putHeader("WWW-Authenticate", "Basic").end();
            return;
        }
        client.request(HttpMethod.GET, "/oauth/authorize?response_type=token&client_id=openshift-challenging-client", event -> {
            if (event.failed()) {
                ctx.response().setStatusCode(500).end();
                return;
            }
            HttpClientRequest request = event.result();
            request.putHeader("Authorization", authorizationHeader)
                    .send().onComplete(result -> {
                        if (result.succeeded() && result.result().statusCode() == 302) {
                            success.run();
                        } else {
                            ctx.response().setStatusCode(500).end();
                        }
                    });
        });
    }
}
