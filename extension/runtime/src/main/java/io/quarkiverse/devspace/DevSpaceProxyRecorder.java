package io.quarkiverse.devspace;

import java.util.function.Supplier;

import org.jboss.logging.Logger;

import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;

@Recorder
public class DevSpaceProxyRecorder {
    private static final Logger log = Logger.getLogger(DevSpaceProxyRecorder.class);

    static VirtualDevpaceProxyClient client;
    public static ExtractedConfig config;
    static Vertx vertx;

    public void init(Supplier<Vertx> vertx, ShutdownContext shutdown, ExtractedConfig c, boolean delayConnect) {
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

    public static void startSession(ExtractedConfig config) {
        client = new VirtualDevpaceProxyClient();
        HttpClientOptions options = new HttpClientOptions();
        options.setDefaultHost(config.host);
        options.setDefaultPort(config.port);
        if (config.ssl) {
            options.setSsl(true).setTrustAll(true);
        }
        client.proxyClient = vertx.createHttpClient(options);
        client.vertx = vertx;
        client.whoami = config.who;
        client.start(config.session, config.queries, config.paths, config.headers);
    }

    public static void closeSession() {
        if (client != null)
            client.shutdown();
        client = null;
    }
}
