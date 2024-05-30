package io.quarkus.devspace.client;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;

public class DevProxyClientBuilder {
    private final DevProxyClient devProxyClient;
    private final Vertx vertx;

    DevProxyClientBuilder(Vertx vertx) {
        this.devProxyClient = new DevProxyClient();
        this.vertx = vertx;
    }

    public DevProxyClientBuilder whoami(String whoami) {
        devProxyClient.whoami = whoami;
        return this;
    }

    public DevProxyClientBuilder numPollers(int num) {
        devProxyClient.numPollers = num;
        return this;
    }

    public DevProxyClientBuilder proxy(String host, int port, boolean ssl) {
        HttpClientOptions options = new HttpClientOptions();
        if (ssl) {
            options.setSsl(true).setTrustAll(true);
        }
        return proxy(host, port, options);
    }

    public DevProxyClientBuilder proxy(String host, int port, HttpClientOptions options) {
        devProxyClient.proxyHost = host;
        devProxyClient.proxyPort = port;
        options.setDefaultHost(host);
        options.setDefaultPort(port);
        devProxyClient.proxyClient = vertx.createHttpClient(options);
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
        devProxyClient.serviceHost = host;
        devProxyClient.servicePort = port;
        options.setDefaultHost(host);
        options.setDefaultPort(port);
        devProxyClient.serviceClient = vertx.createHttpClient(options);
        return this;
    }

    public DevProxyClientBuilder pollTimeoutMillis(long timeout) {
        devProxyClient.pollTimeoutMillis = timeout;
        return this;
    }

    public DevProxyClient build() {
        return devProxyClient;
    }
}
