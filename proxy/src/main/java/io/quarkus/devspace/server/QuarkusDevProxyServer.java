package io.quarkus.devspace.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

@ApplicationScoped
public class QuarkusDevProxyServer {

    @Inject
    @ConfigProperty(name = "service.name")
    protected String serviceName;

    @Inject
    @ConfigProperty(name = "service.host")
    protected String serviceHost;

    @Inject
    @ConfigProperty(name = "service.port")
    protected int servicePort;

    @Inject
    @ConfigProperty(name = "service.ssl", defaultValue = "false")
    protected boolean serviceSsl;

    @Inject
    @ConfigProperty(name = "client.api.port")
    protected int clientApiPort;

    protected DevProxyServer proxyServer;
    private HttpServer clientApi;

    public void start(@Observes StartupEvent start, Vertx vertx, Router proxyRouter) {
        proxyServer = new DevProxyServer();
        ServiceConfig config = new ServiceConfig(serviceName, serviceHost, servicePort, serviceSsl);
        clientApi = vertx.createHttpServer();
        Router clientApiRouter = Router.router(vertx);
        proxyServer.init(vertx, proxyRouter, clientApiRouter, config);
        clientApi.requestHandler(clientApiRouter).listen(clientApiPort);
    }

    @Shutdown
    public void stop() {
        clientApi.close();
    }
}
