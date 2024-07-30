package io.quarkiverse.playpen.client;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;

public class PlaypenClientBuilder {
    private final PlaypenClient playpenClient;
    private final Vertx vertx;
    private PlaypenConnectionConfig config;

    PlaypenClientBuilder(Vertx vertx) {
        this.playpenClient = new PlaypenClient();
        this.vertx = vertx;
    }

    public PlaypenClientBuilder devspace(String uri) {
        this.config = PlaypenConnectionConfig.fromUri(uri);
        return this;
    }

    public PlaypenClientBuilder devspace(PlaypenConnectionConfig config) {
        this.config = config;
        return this;
    }

    public PlaypenClientBuilder numPollers(int num) {
        playpenClient.numPollers = num;
        return this;
    }

    public PlaypenClientBuilder service(String host, int port, boolean ssl) {
        HttpClientOptions options = new HttpClientOptions();
        if (ssl) {
            options.setSsl(true).setTrustAll(true);
        }
        return service(host, port, options);
    }

    public PlaypenClientBuilder service(String host, int port, HttpClientOptions options) {
        options.setDefaultHost(host);
        options.setDefaultPort(port);
        playpenClient.serviceClient = vertx.createHttpClient(options);
        return this;
    }

    public PlaypenClientBuilder pollTimeoutMillis(long timeout) {
        playpenClient.setPollTimeoutMillis(timeout);
        return this;
    }

    public PlaypenClientBuilder basicAuth(String user, String password) {
        playpenClient.setBasicAuth(user, password);
        return this;
    }

    public PlaypenClientBuilder secretAuth(String secret) {
        playpenClient.setSecretAuth(secret);
        return this;
    }

    public PlaypenClientBuilder credentials(String creds) {
        playpenClient.setCredentials(creds);
        return this;
    }

    public PlaypenClient build() {
        HttpClientOptions options = new HttpClientOptions();
        options.setDefaultHost(config.host);
        options.setDefaultPort(config.port);
        playpenClient.proxyClient = vertx.createHttpClient(options);
        playpenClient.initUri(config.who, config.session, config.queries, config.paths, config.headers);
        return playpenClient;
    }
}
