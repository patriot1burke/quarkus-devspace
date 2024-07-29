package io.quarkus.devspace.server;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.devspace.server.auth.OpenshiftBasicAuth;
import io.quarkus.devspace.server.auth.ProxySessionAuth;
import io.quarkus.devspace.server.auth.SecretAuth;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

@ApplicationScoped
public class QuarkusDevProxyServer {
    protected static final Logger log = Logger.getLogger(QuarkusDevProxyServer.class);

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

    @Inject
    @ConfigProperty(name = "idle.timeout", defaultValue = "60000")
    protected int idleTimeout;

    @Inject
    @ConfigProperty(name = "poll.timeout", defaultValue = "5000")
    protected int pollTimeout;

    @Inject
    @ConfigProperty(name = "authentication.type", defaultValue = "none")
    protected String authType;

    @Inject
    @ConfigProperty(name = "oauth.url", defaultValue = "oauth-openshift.openshift-authentication.svc.cluster.local")
    protected String oauthUrl;

    @Inject
    @ConfigProperty(name = "secret", defaultValue = "badsecret")
    protected String secret;

    protected DevProxyServer proxyServer;
    private HttpServer clientApi;

    public void start(@Observes StartupEvent start, Vertx vertx, Router proxyRouter) {
        proxyServer = new DevProxyServer();
        proxyServer.setIdleTimeout(idleTimeout);
        log.info("Idle timeout millis: " + idleTimeout);
        proxyServer.setPollTimeout(pollTimeout);
        log.info("Poll timeout millis: " + pollTimeout);
        if (ProxySessionAuth.OPENSHIFT_BASIC_AUTH.equalsIgnoreCase(authType)) {
            log.info("Openshift Basic Auth: " + oauthUrl);
            proxyServer.setAuth(new OpenshiftBasicAuth(vertx, oauthUrl));
        } else if (ProxySessionAuth.SECRET_AUTH.equalsIgnoreCase(authType)) {
            log.info("Secret auth");
            proxyServer.setAuth(new SecretAuth(secret));
        } else {
            log.info("no auth");
        }
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
