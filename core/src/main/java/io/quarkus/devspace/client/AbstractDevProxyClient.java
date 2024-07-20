package io.quarkus.devspace.client;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.quarkus.devspace.ProxyUtils;
import io.quarkus.devspace.server.DevProxyServer;
import io.quarkus.devspace.server.auth.ProxySessionAuth;
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
    protected long pollTimeoutMillis = 3000;
    protected String uri;
    protected boolean connected;
    protected volatile boolean shutdown = false;
    protected String tokenHeader = null;

    public void setProxyClient(HttpClient proxyClient) {
        this.proxyClient = proxyClient;
    }

    public void initUri(String whoami, String sessionId, List<String> queries, List<String> paths, List<String> headers) {
        if (sessionId == null)
            sessionId = DevProxyServer.GLOBAL_PROXY_SESSION;
        this.sessionId = sessionId;
        log.info("Start devspace session: " + sessionId);
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
        proxyClient.request(HttpMethod.POST, uri, event -> {
            log.info("******* Connect request start");
            if (event.failed()) {
                log.error("Could not connect to startSession", event.cause());
                latch.countDown();
                return;
            }
            HttpClientRequest request = event.result();
            log.info("******* Sending Connect request");
            request.send().onComplete(event1 -> {
                log.info("******* Connect request onComplete");
                if (event1.failed()) {
                    log.error("Could not connect to startSession", event1.cause());
                    latch.countDown();
                    return;
                }
                HttpClientResponse response = event1.result();
                if (response.statusCode() == 409) {
                    response.bodyHandler(body -> {
                        log.error("Could not connect to session as " + body.toString() + " is using the session");
                        latch.countDown();
                    });
                    return;
                }
                if (response.statusCode() != 204) {
                    response.bodyHandler(body -> {
                        log.error("Could not connect to startSession " + response.statusCode() + body.toString());
                        latch.countDown();
                    });
                    return;
                }
                log.info("******* Connect request succeeded");
                try {
                    this.pollLink = response.getHeader(DevProxyServer.POLL_LINK);
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
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!success.get()) {
            log.error("Failed to connect to proxy server");
            forcedShutdown();
            return false;
        }
        this.connected = true;
        return true;
    }

    protected void reconnect() {
        if (!running)
            return;
        log.info("reconnect.....");
        proxyClient.request(HttpMethod.POST, uri, event -> {
            if (event.failed()) {
                log.error("Could not reconnect to session", event.cause());
                return;
            }
            HttpClientRequest request = event.result();
            setToken(request);
            log.info("Sending reconnect request...");
            request.send().onComplete(event1 -> {
                if (event1.failed()) {
                    log.error("Could not reconnect to session", event1.cause());
                    return;
                }
                HttpClientResponse response = event1.result();
                if (response.statusCode() == 409) {
                    response.bodyHandler(body -> {
                        log.error("Could not reconnect to session as " + body.toString() + " is using the session");
                    });
                    return;
                }
                if (response.statusCode() != 204) {
                    response.bodyHandler(body -> {
                        log.error("Could not reconnect to session" + response.statusCode() + body.toString());
                    });
                    return;
                }
                log.info("Reconnect succeeded");
                this.pollLink = response.getHeader(DevProxyServer.POLL_LINK);
                workerShutdown.register();
                poll();
            });
        });
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
            log.error("Poll failed", failure);
        }
        workerOffline();
    }

    protected void pollConnectFailure(Throwable failure) {
        log.error("Connect Poll failed", failure);
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
        log.error("Poll failed: " + error);
        workerOffline();
    }

    protected void handlePoll(HttpClientResponse pollResponse) {
        pollResponse.pause();
        log.info("------ handlePoll");
        int proxyStatus = pollResponse.statusCode();
        if (proxyStatus == 408) {
            log.debug("Poll timeout, redo poll");
            poll();
            return;
        } else if (proxyStatus == 204) {
            // keepAlive = false sent back
            log.info("Keepalive = false.  Stop Polling");
            workerOffline();
            return;
        } else if (proxyStatus == 404) {
            log.info("session was closed, exiting poll");
            workerOffline();
            reconnect();
            return;
        } else if (proxyStatus != 200) {
            pollResponse.bodyHandler(body -> {
                pollFailure(body.toString());
            });
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
                        .onSuccess(request -> request.send()
                                .onComplete(event -> {
                                    if (event.failed()) {
                                        log.error("Failed to delete sesssion on shutdown", event.cause());
                                    }
                                    latch.countDown();
                                }));

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
