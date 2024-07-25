package io.quarkiverse.devspace;

import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.devspace.client.DevspaceConnectionConfig;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;

@Recorder
public class DevSpaceProxyRecorder {
    private static final Logger log = Logger.getLogger(DevSpaceProxyRecorder.class);

    static VirtualDevpaceProxyClient client;
    public static DevspaceConnectionConfig config;
    static Vertx vertx;

    public void init(Supplier<Vertx> vertx, ShutdownContext shutdown, DevspaceConnectionConfig c, boolean delayConnect) {
        config = c;
        DevSpaceProxyRecorder.vertx = vertx.get();
        if (!delayConnect) {
            startSession(c);
            shutdown.addShutdownTask(() -> {
                closeSession();
            });
        }
    }

    public static void startSession() {
        startSession(config);
    }

    public static void startSession(DevspaceConnectionConfig config) {
        client = new VirtualDevpaceProxyClient();
        HttpClientOptions options = new HttpClientOptions();
        options.setDefaultHost(config.host);
        options.setDefaultPort(config.port);
        if (config.ssl) {
            options.setSsl(true).setTrustAll(true);
        }
        client.setCredentials(config.credentials);
        client.setProxyClient(vertx.createHttpClient(options));
        client.vertx = vertx;
        client.initUri(config.who, config.session, config.queries, config.paths, config.headers);
        client.start();
    }

    public static void closeSession() {
        if (client != null)
            client.shutdown();
        client = null;
    }
}
