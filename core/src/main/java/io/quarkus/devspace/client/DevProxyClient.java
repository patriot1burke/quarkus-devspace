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
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpMethod;

public class DevProxyClient {
    protected static final Logger log = Logger.getLogger(DevProxyClient.class);

    protected HttpClient proxyClient;
    protected HttpClient serviceClient;
    protected String proxyHost;
    protected int proxyPort;
    protected String serviceHost;
    protected int servicePort;
    protected String whoami;
    protected String sessionId;
    protected int numPollers = 1;
    protected volatile boolean running = true;
    protected volatile boolean shutdown = false;
    protected String pollLink;
    protected Phaser workerShutdown;
    protected long pollTimeoutMillis = 1000;
    protected String uri;

    protected DevProxyClient() {

    }

    public static DevProxyClientBuilder create(Vertx vertx) {
        return new DevProxyClientBuilder(vertx);
    }

    public boolean start() {
        return start(null, null, null, null);
    }

    public boolean start(String sessionId, List<String> queries, List<String> paths, List<String> headers) {
        if (sessionId == null)
            sessionId = DevProxyServer.GLOBAL_PROXY_SESSION;
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
                this.uri = this.uri + "&header=" + path;
        }

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
        this.sessionId = sessionId;
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
        serviceClient.close();
    }

    protected void pollFailure(Throwable failure) {
        if (failure instanceof HttpClosedException) {
            log.warn("Client poll stopped.  Connection closed by server");
        } else if (failure instanceof TimeoutException) {
            log.warn("Poll timeout");
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

    protected void pollFailure(String error) {
        log.error("Poll failed: " + error);
        workerOffline();
    }

    private void workerOffline() {
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
                    request.setTimeout(pollTimeoutMillis)
                            .send()
                            .onSuccess(this::handlePoll)
                            //.onSuccess(this::handlePollWaitBody)
                            .onFailure(this::pollFailure);

                })
                .onFailure(this::pollFailure);
    }

    protected void handlePoll(HttpClientResponse pollResponse) {
        pollResponse.pause();
        log.info("------ handlePoll");
        int proxyStatus = pollResponse.statusCode();
        if (proxyStatus == 408) {
            poll();
            return;
        } else if (proxyStatus == 204) {
            // keepAlive = false sent back
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

        String method = pollResponse.getHeader(DevProxyServer.METHOD_HEADER);
        String uri = pollResponse.getHeader(DevProxyServer.URI_HEADER);
        serviceClient.request(HttpMethod.valueOf(method), uri)
                .onFailure(exc -> {
                    log.error("Service connect failure", exc);
                    String responsePath = pollResponse.getHeader(DevProxyServer.RESPONSE_LINK);
                    deletePushResponse(responsePath);
                })
                .onSuccess(serviceRequest -> {
                    invokeService(pollResponse, serviceRequest);
                });
    }

    private void invokeService(HttpClientResponse pollResponse, HttpClientRequest serviceRequest) {
        log.info("**** INVOKE SERVICE ****");
        serviceRequest.setTimeout(1000 * 1000); // long timeout as there might be a debugger session
        String responsePath = pollResponse.getHeader(DevProxyServer.RESPONSE_LINK);
        pollResponse.headers().forEach((key, val) -> {
            log.infov("Poll response header: {0} : {1}", key, val);
            int idx = key.indexOf(DevProxyServer.HEADER_FORWARD_PREFIX);
            if (idx == 0) {
                String headerName = key.substring(DevProxyServer.HEADER_FORWARD_PREFIX.length());
                serviceRequest.headers().add(headerName, val);
            } else if (key.equalsIgnoreCase("Content-Length")) {
                serviceRequest.headers().add("Content-Length", val);
            }
        });
        //serviceRequest.send(pollResponse.body().result())
        serviceRequest.send(pollResponse)
                .onFailure(exc -> {
                    log.error("Service send failure", exc);
                    deletePushResponse(responsePath);
                })
                .onSuccess(serviceResponse -> {
                    handleServiceResponse(responsePath, serviceResponse);
                });

    }

    private void deletePushResponse(String link) {
        if (link == null) {
            workerOffline();
            return;
        }
        proxyClient.request(HttpMethod.DELETE, link)
                .onFailure(event -> workerOffline())
                .onSuccess(request -> request.send().onComplete(event -> workerOffline()));
    }

    private void handleServiceResponse(String responsePath, HttpClientResponse serviceResponse) {
        serviceResponse.pause();
        log.info("----> handleServiceResponse");
        // do not keepAlive is we are in shutdown mode
        proxyClient.request(HttpMethod.POST, responsePath + "?keepAlive=" + running)
                .onFailure(exc -> {
                    log.error("Proxy handle response failure", exc);
                    workerOffline();
                })
                .onSuccess(pushRequest -> {
                    pushRequest.setTimeout(pollTimeoutMillis);
                    pushRequest.putHeader(DevProxyServer.STATUS_CODE_HEADER, Integer.toString(serviceResponse.statusCode()));
                    serviceResponse.headers()
                            .forEach((key, val) -> pushRequest.headers().add(DevProxyServer.HEADER_FORWARD_PREFIX + key, val));
                    pushRequest.send(serviceResponse)
                            .onFailure(exc -> {
                                if (exc instanceof TimeoutException) {
                                    poll();
                                } else {
                                    log.error("Failed to push service response", exc);
                                    workerOffline();
                                }
                            })
                            .onSuccess(this::handlePoll); // a successful push restarts poll
                });
    }

    public void shutdown() {
        if (shutdown) {
            return;
        }
        try {
            running = false;
            // delete session
            CountDownLatch latch = new CountDownLatch(1);
            if (sessionId != null) {
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
            ProxyUtils.await(1000, serviceClient.close());
        } finally {
            shutdown = true;
        }
    }

}
