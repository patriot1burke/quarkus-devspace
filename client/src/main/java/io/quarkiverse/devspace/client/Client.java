package io.quarkiverse.devspace.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.devspace.client.DevProxyClient;
import io.quarkus.devspace.client.DevspaceConnectionConfig;
import io.quarkus.runtime.Shutdown;
import io.vertx.core.Vertx;

@ApplicationScoped
public class Client {
    DevProxyClient client;

    @Inject
    Vertx vertx;

    public void start(int localPort, DevspaceConnectionConfig config) {
        client = DevProxyClient.create(vertx)
                .proxy(config.host, config.port, config.ssl)
                .service("localhost", localPort, false)
                .whoami(config.who)
                .build();
    }

    @Shutdown
    public void stop() {
        if (client != null) {
            client.shutdown();
        }
    }
}
