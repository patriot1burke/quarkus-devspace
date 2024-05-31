package io.quarkiverse.devspace.client;

import java.util.concurrent.CountDownLatch;

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

    CountDownLatch running = new CountDownLatch(1);

    public boolean start(int localPort, DevspaceConnectionConfig config) throws Exception {
        client = DevProxyClient.create(vertx)
                .proxy(config.host, config.port, config.ssl)
                .service("localhost", localPort, false)
                .whoami(config.who)
                .build();
        if (!client.start()) {
            return false;
        }
        running = new CountDownLatch(1);
        running.await();
        return true;
    }

    @Shutdown
    public void stop() {
        running.countDown();
        if (client != null) {
            client.shutdown();
        }
        client = null;
    }
}
