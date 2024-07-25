package io.quarkus.devspace.client;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;

public class DevProxyClientBuilder {
    private final DevProxyClient devProxyClient;
    private final Vertx vertx;
    private DevspaceConnectionConfig config;

    DevProxyClientBuilder(Vertx vertx) {
        this.devProxyClient = new DevProxyClient();
        this.vertx = vertx;
    }

    public DevProxyClientBuilder devspace(String uri) {
        this.config = DevspaceConnectionConfig.fromUri(uri);
        return this;
    }

    public DevProxyClientBuilder devspace(DevspaceConnectionConfig config) {
        this.config = config;
        return this;
    }

    public DevProxyClientBuilder numPollers(int num) {
        devProxyClient.numPollers = num;
        return this;
    }

    public DevProxyClientBuilder service(String host, int port, boolean ssl) {
        HttpClientOptions options = new HttpClientOptions();
        if (ssl) {
            options.setSsl(true).setTrustAll(true);
        }
        return service(host, port, options);
    }

    public DevProxyClientBuilder service(String host, int port, HttpClientOptions options) {
        options.setDefaultHost(host);
        options.setDefaultPort(port);
        devProxyClient.serviceClient = vertx.createHttpClient(options);
        return this;
    }

    public DevProxyClientBuilder pollTimeoutMillis(long timeout) {
        devProxyClient.pollTimeoutMillis = timeout;
        return this;
    }

    public DevProxyClientBuilder basicAuth(String user, String password) {
        devProxyClient.setBasicAuth(user, password);
        return this;
    }

    public DevProxyClientBuilder secretAuth(String secret) {
        devProxyClient.setSecretAuth(secret);
        return this;
    }

    public DevProxyClientBuilder credentials(String creds) {
        devProxyClient.setCredentials(creds);
        return this;
    }

    public DevProxyClient build() {
        HttpClientOptions options = new HttpClientOptions();
        options.setDefaultHost(config.host);
        options.setDefaultPort(config.port);
        devProxyClient.proxyClient = vertx.createHttpClient(options);
        devProxyClient.initUri(config.who, config.session, config.queries, config.paths, config.headers);
        return devProxyClient;
    }
}
