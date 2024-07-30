package io.quarkiverse.playpen.client;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.quarkiverse.playpen.ProxyUtils;
import io.quarkiverse.playpen.server.DevProxyServer;
import io.quarkiverse.playpen.server.auth.ProxySessionAuth;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpMethod;

public abstract class AbstractDevProxyClient {
    protected static final Logger log = Logger.getLogger(DevProxyClient.class);
    protected HttpClient proxyClient;
    protected String sessionId;
    protected int numPollers = 1;
    protected volatile boolean running = true;
    protected String pollLink;
    protected Phaser workerShutdown;
    protected long pollTimeoutMillis;
    protected boolean pollTimeoutOverriden;
    protected String uri;
    protected boolean connected;
    protected volatile boolean shutdown = false;
    protected String tokenHeader = null;
    protected String authHeader;
    protected String credentials;

    public void setPollTimeoutMillis(long pollTimeoutMillis) {
        pollTimeoutOverriden = true;
        this.pollTimeoutMillis = pollTimeoutMillis;
    }

    public void setProxyClient(HttpClient proxyClient) {
        this.proxyClient = proxyClient;
    }

    public void setBasicAuth(String username, String password) {
        String valueToEncode = username + ":" + password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

    public boolean setBasicAuth(String creds) {
        int idx = creds.indexOf(':');
        if (idx < 0) {
            return false;
        }
        setBasicAuth(creds.substring(0, idx), creds.substring(idx + 1));
        return true;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public void setSecretAuth(String secret) {
        this.authHeader = "Secret " + secret;
    }

    public void initUri(String whoami, String sessionId, List<String> queries, List<String> paths, List<String> headers) {
        if (sessionId == null)
            sessionId = DevProxyServer.GLOBAL_PROXY_SESSION;
        this.sessionId = sessionId;
        log.debug("Start devspace session: " + sessionId);
        this.uri = DevProxyServer.CLIENT_API_PATH + "/connect?who=" + whoami + "&session=" + sessionId;
        if (queries != null) {
            for (String query : queries)
                this.uri = this.uri + "&query=" + query;
        }
        if (headers != null) {
            for (String header : headers)
                this.uri = this.uri + "&header=" + header;
        }
        if (paths != null) {
            for (String path : paths)
                this.uri = this.uri + "&path=" + path;
        }
    }

    public boolean start() {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean();
        connect(latch, success, false);
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!success.get()) {
            logError("Failed to connect to proxy server");
            forcedShutdown();
            return false;
        }
        log.info("Devspace connection established...");
        this.connected = true;
        return true;
    }

    protected void connect(CountDownLatch latch, AtomicBoolean success, boolean challenged) {
        log.debug("Connect " + (challenged ? "after challenge" : ""));
        proxyClient.request(HttpMethod.POST, uri, event -> {
            log.debug("******* Connect request start");
            if (event.failed()) {
                logError("Could not connect to startSession: " + event.cause().getMessage());
                latch.countDown();
                return;
            }
            HttpClientRequest request = event.result();
            if (authHeader != null) {
                request.putHeader("Authorization", authHeader);
            }
            log.debug("******* Sending Connect request");
            request.send().onComplete(event1 -> {
                log.debug("******* Connect request onComplete");
                if (event1.failed()) {
                    logError("Could not connect to startSession: " + event1.cause().getMessage());
                    latch.countDown();
                    return;
                }
                HttpClientResponse response = event1.result();
                if (response.statusCode() == 409) {
                    response.bodyHandler(body -> {
                        logError("Could not connect to session as " + body.toString() + " is using the session");
                        latch.countDown();
                    });
                    return;
                } else if (response.statusCode() == 401) {
                    String wwwAuthenticate = response.getHeader(ProxySessionAuth.WWW_AUTHENTICATE);
                    if (wwwAuthenticate == null) {
                        logError("Could not authenticate connection");
                        latch.countDown();
                    }
                    if (challenged) {
                        String message = "";
                        if (wwwAuthenticate.startsWith("Basic")) {
                            message = ". You must provide correct username and password in quarkus.devspace.credentials as <username>:<password>";
                        } else if (wwwAuthenticate.startsWith("Secret")) {
                            message = ". You must provide correct secret in quarkus.devspace.credentials";
                        }
                        logError("Could not authenticate connection" + message);
                        latch.countDown();
                        return;
                    }
                    if (wwwAuthenticate.startsWith(ProxySessionAuth.BASIC)) {
                        if (!setBasicAuth(credentials)) {
                            logError("Expecting username:password for basic auth credentials string");
                            latch.countDown();
                            return;
                        }
                        connect(latch, success, true);
                        return;
                    } else if (wwwAuthenticate.startsWith(ProxySessionAuth.SECRET)) {
                        setSecretAuth(credentials);
                        connect(latch, success, true);
                        return;
                    } else {
                        logError("Unknown authentication protocol");
                        latch.countDown();
                        return;
                    }
                } else if (response.statusCode() != 204) {
                    logError("Could not connect to startSession " + response.statusCode());
                    latch.countDown();
                    return;
                }
                log.debug("******* Connect request succeeded");
                try {
                    this.pollLink = response.getHeader(DevProxyServer.POLL_LINK);
                    if (!pollTimeoutOverriden)
                        this.pollTimeoutMillis = Long.parseLong(response.getHeader(DevProxyServer.POLL_TIMEOUT));
                    this.tokenHeader = response.getHeader(ProxySessionAuth.BEARER_TOKEN_HEADER);
                    workerShutdown = new Phaser(1);
                    for (int i = 0; i < numPollers; i++) {
                        workerShutdown.register();
                        poll();
                    }
                    success.set(true);
                } finally {
                    latch.countDown();
                }
            });
        });
    }

    protected void reconnect() {
        if (!running)
            return;
        log.debug("reconnect.....");
        proxyClient.request(HttpMethod.POST, uri, event -> {
            if (event.failed()) {
                logError("Could not reconnect to session: " + event.cause().getMessage());
                return;
            }
            HttpClientRequest request = event.result();
            if (authHeader != null) {
                request.putHeader(ProxySessionAuth.AUTHORIZATION, authHeader);
            }
            log.debug("Sending reconnect request...");
            request.send().onComplete(event1 -> {
                if (event1.failed()) {
                    logError("Could not reconnect to session: " + event1.cause().getMessage());
                    return;
                }
                HttpClientResponse response = event1.result();
                if (response.statusCode() == 409) {
                    response.bodyHandler(body -> {
                        logError("Could not reconnect to session as " + body.toString() + " is using the session");
                    });
                    return;
                }
                if (response.statusCode() != 204) {
                    logError("Could not reconnect to session" + response.statusCode());
                    return;
                }
                log.debug("Reconnect succeeded");
                this.pollLink = response.getHeader(DevProxyServer.POLL_LINK);
                if (!pollTimeoutOverriden)
                    this.pollTimeoutMillis = Long.parseLong(response.getHeader(DevProxyServer.POLL_TIMEOUT));
                workerShutdown.register();
                poll();
            });
        });
    }

    protected void logError(String msg) {
        log.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        log.error("");
        log.error(msg);
        log.error("");
        log.error("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    public void forcedShutdown() {
        shutdown = true;
        running = false;
        proxyClient.close();
    }

    protected void pollFailure(Throwable failure) {
        if (failure instanceof HttpClosedException) {
            log.warn("Client poll stopped.  Connection closed by server");
        } else if (failure instanceof TimeoutException) {
            log.debug("Poll timeout");
            poll();
            return;
        } else {
            logError("Poll failed: " + failure.getMessage());
        }
        workerOffline();
    }

    protected void pollConnectFailure(Throwable failure) {
        logError("Connect Poll failed: " + failure.getMessage());
        workerOffline();
    }

    protected void workerOffline() {
        try {
            workerShutdown.arriveAndDeregister();
        } catch (Exception ignore) {
        }
    }

    protected void poll() {
        if (!running) {
            workerOffline();
            return;
        }
        proxyClient.request(HttpMethod.POST, pollLink)
                .onSuccess(request -> {
                    setToken(request);
                    request.setTimeout(pollTimeoutMillis)
                            .send()
                            .onSuccess(this::handlePoll)
                            .onFailure(this::pollFailure);

                })
                .onFailure(this::pollConnectFailure);
    }

    protected void setToken(HttpClientRequest request) {
        if (tokenHeader != null) {
            request.putHeader("Authorization", tokenHeader);
        }
    }

    protected void pollFailure(String error) {
        logError("Poll failed: " + error);
        workerOffline();
    }

    protected void handlePoll(HttpClientResponse pollResponse) {
        pollResponse.pause();
        log.debug("------ handlePoll");
        int proxyStatus = pollResponse.statusCode();
        if (proxyStatus == 408) {
            log.debug("Poll timeout, redo poll");
            poll();
            return;
        } else if (proxyStatus == 204) {
            // keepAlive = false sent back
            log.debug("Keepalive = false.  Stop Polling");
            workerOffline();
            return;
        } else if (proxyStatus == 404) {
            log.debug("session was closed, exiting poll");
            workerOffline();
            reconnect();
            return;
        } else if (proxyStatus == 504) {
            log.debug("Gateway timeout, redo poll");
            poll();
            return;
        } else if (proxyStatus != 200) {
            log.debug("Poll failure: " + proxyStatus);
            workerOffline();
            return;
        }

        processPoll(pollResponse);
    }

    protected abstract void processPoll(HttpClientResponse pollResponse);

    public void shutdown() {
        if (shutdown) {
            return;
        }
        try {
            running = false;
            // delete session
            CountDownLatch latch = new CountDownLatch(1);
            if (connected) {
                String uri = DevProxyServer.CLIENT_API_PATH + "/connect?session=" + sessionId;
                proxyClient.request(HttpMethod.DELETE, uri)
                        .onFailure(event -> {
                            log.error("Failed to delete sesssion on shutdown", event);
                            latch.countDown();
                        })
                        .onSuccess(request -> {
                            setToken(request);
                            request.send()
                                    .onComplete(event -> {
                                        if (event.failed()) {
                                            log.error("Failed to delete sesssion on shutdown", event.cause());
                                        }
                                        latch.countDown();
                                    });
                        });

            }
            try {
                latch.await(1, TimeUnit.SECONDS);
                int phase = workerShutdown.arriveAndDeregister();
                phase = workerShutdown.awaitAdvanceInterruptibly(1, pollTimeoutMillis * 2, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | TimeoutException ignored) {
            }
            ProxyUtils.await(1000, proxyClient.close());
        } finally {
            shutdown = true;
        }
    }
}
