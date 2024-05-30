package io.quarkiverse.devspace;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.quarkus.devspace.ProxyUtils;
import io.quarkus.devspace.server.DevProxyServer;
import io.quarkus.netty.runtime.virtual.VirtualClientConnection;
import io.quarkus.netty.runtime.virtual.VirtualResponseHandler;
import io.quarkus.vertx.http.runtime.QuarkusHttpHeaders;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.core.streams.impl.InboundBuffer;

public class VirtualDevpaceProxyClient {
    protected static final Logger log = Logger.getLogger(VirtualDevpaceProxyClient.class);

    protected Vertx vertx;
    protected HttpClient proxyClient;
    protected String whoami;
    protected String sessionId;
    protected int numPollers = 1;
    protected volatile boolean running = true;
    protected volatile boolean shutdown = false;
    protected String pollLink;
    protected Phaser workerShutdown;
    protected long pollTimeoutMillis = 5000;
    protected String uri;

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
        log.info("**** poll() start");
        if (!running) {
            return;
        }
        proxyClient.request(HttpMethod.POST, pollLink)
                .onSuccess(request -> {
                    request.setTimeout(pollTimeoutMillis)
                            .send()
                            .onSuccess(this::handlePoll)
                            .onFailure(this::pollFailure);

                })
                .onFailure(this::pollConnectFailure);
    }

    private class NettyResponseHandler implements VirtualResponseHandler, ReadStream<Buffer> {

        final String responsePath;
        InboundBuffer<Buffer> queue;
        static Buffer end = Buffer.buffer();
        Handler<Void> endHandler;
        VirtualClientConnection connection;

        public void setConnection(VirtualClientConnection connection) {
            this.connection = connection;
        }

        private void write(Buffer buf) {
            vertx.runOnContext((v) -> queue.write(buf));
        }

        public NettyResponseHandler(String responsePath, Vertx vertx) {
            this.responsePath = responsePath;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            queue.exceptionHandler(handler);
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
            if (handler == null) {
                if (queue != null)
                    queue.handler(null);
                return this;
            }
            log.info("NettyResponseHandler: set handler");
            queue.handler((buf) -> {
                log.info("NettyResponseHandler: handler");
                if (buf == end) {
                    log.info("NettyResponseHandler: calling end");
                    connection.close();
                    if (endHandler != null) {
                        endHandler.handle(null);
                    }
                } else {
                    log.info("NettyResponseHandler: handler.handle(buf)");
                    handler.handle(buf);
                }
            });
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            log.info("NettyResponseHandler: pause");
            queue.pause();
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            log.info("NettyResponseHandler: resume");
            boolean result = queue.resume();
            log.info("NettyResponseHandler: resume returned: " + result);
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            log.info("NettyResponseHandler: fetch");
            queue.fetch(amount);
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }

        @Override
        public void handleMessage(Object msg) {
            log.infov("NettyResponseHandler: handleMessage({0})", msg.getClass().getName());
            if (msg instanceof HttpResponse) {
                queue = new InboundBuffer<>(vertx.getOrCreateContext());
                queue.pause();
                HttpResponse res = (HttpResponse) msg;
                proxyClient.request(HttpMethod.POST, responsePath + "?keepAlive=" + running)
                        .onFailure(exc -> {
                            log.error("Proxy handle response failure", exc);
                            workerOffline();
                        })
                        .onSuccess(pushRequest -> {
                            log.info("NettyResponseHandler connect accepted for pushResponse");
                            pushRequest.setTimeout(pollTimeoutMillis);
                            pushRequest.putHeader(DevProxyServer.STATUS_CODE_HEADER, Integer.toString(res.status().code()));

                            for (String name : res.headers().names()) {
                                final List<String> allForName = res.headers().getAll(name);
                                if (allForName == null || allForName.isEmpty()) {
                                    continue;
                                }

                                for (Iterator<String> valueIterator = allForName.iterator(); valueIterator.hasNext();) {
                                    String val = valueIterator.next();
                                    if (name.equalsIgnoreCase("Transfer-Encoding")
                                            && val.equals("chunked")) {
                                        continue; // ignore transfer encoding, chunked screws up message and response
                                    }
                                    pushRequest.headers().add(DevProxyServer.HEADER_FORWARD_PREFIX + name, val);
                                }
                            }
                            pushRequest.send(this)
                                    .onFailure(exc -> {
                                        if (exc instanceof TimeoutException) {
                                            poll();
                                        } else {
                                            log.error("Failed to push service response", exc);
                                            workerOffline();
                                        }
                                    })
                                    .onSuccess(VirtualDevpaceProxyClient.this::handlePoll); // a successful push restarts poll
                        });
            }
            if (msg instanceof HttpContent) {
                log.info("NettyResponseHandler: write HttpContent");
                write(BufferImpl.buffer(((HttpContent) msg).content()));
            }
            if (msg instanceof FileRegion) {
                log.error("FileRegion not supported yet");
                throw new RuntimeException("FileRegion not supported yet");
            }
            if (msg instanceof LastHttpContent) {
                log.info("NettyResponseHandler: write LastHttpContent");
                write(end);
            }
        }

        @Override
        public void close() {

        }
    }

    private class NettyWriteStream implements WriteStream<Buffer> {
        VirtualClientConnection connection;

        public NettyWriteStream(VirtualClientConnection connection) {
            this.connection = connection;
        }

        private void writeHttpContent(Buffer data) {
            log.info("NettyWriteStream: writeHttpContent");
            // todo getByteBuf copies the underlying byteBuf
            DefaultHttpContent content = new DefaultHttpContent(data.getByteBuf());
            connection.sendMessage(content);

        }

        @Override
        public WriteStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data) {
            writeHttpContent(data);
            Promise<Void> promise = Promise.promise();
            write(data, promise);
            return promise.future();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler) {
            writeHttpContent(data);
            handler.handle(Future.succeededFuture());
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler) {
            log.info("NettyWriteStream: end");
            connection.sendMessage(LastHttpContent.EMPTY_LAST_CONTENT);
            handler.handle(Future.succeededFuture());
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
            return this;
        }

        @Override
        public boolean writeQueueFull() {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(@Nullable Handler<Void> handler) {
            return this;
        }
    }

    protected void handlePoll(HttpClientResponse pollResponse) {
        pollResponse.pause();
        log.info("------ handlePoll");
        int proxyStatus = pollResponse.statusCode();
        if (proxyStatus == 408) {
            log.info("Poll timeout, redo poll");
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
        log.info("Unpack poll request");
        String method = pollResponse.getHeader(DevProxyServer.METHOD_HEADER);
        String uri = pollResponse.getHeader(DevProxyServer.URI_HEADER);
        String responsePath = pollResponse.getHeader(DevProxyServer.RESPONSE_LINK);
        NettyResponseHandler handler = new NettyResponseHandler(responsePath, vertx);
        VirtualClientConnection connection = VirtualClientConnection.connect(handler, VertxHttpRecorder.VIRTUAL_HTTP,
                null);
        handler.setConnection(connection);

        QuarkusHttpHeaders quarkusHeaders = new QuarkusHttpHeaders();
        // add context specific things
        io.netty.handler.codec.http.HttpMethod httpMethod = io.netty.handler.codec.http.HttpMethod.valueOf(method);

        DefaultHttpRequest nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1,
                httpMethod, uri,
                quarkusHeaders);
        pollResponse.headers().forEach((key, val) -> {
            log.debugv("Poll response header: {0} : {1}", key, val);
            int idx = key.indexOf(DevProxyServer.HEADER_FORWARD_PREFIX);
            if (idx == 0) {
                String headerName = key.substring(DevProxyServer.HEADER_FORWARD_PREFIX.length());
                nettyRequest.headers().add(headerName, val);
            } else if (key.equalsIgnoreCase("Content-Length")) {
                nettyRequest.headers().add("Content-Length", val);
            }
        });
        if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
            nettyRequest.headers().add(HttpHeaderNames.HOST, "localhost");
        }

        log.info("send initial nettyRequest");
        connection.sendMessage(nettyRequest);
        pollResponse.pipeTo(new NettyWriteStream(connection));
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
                            log.error("Failed to delete session on shutdown", event);
                            latch.countDown();
                        })
                        .onSuccess(request -> request.send()
                                .onComplete(event -> {
                                    if (event.failed()) {
                                        log.error("Failed to delete session on shutdown", event.cause());
                                    }
                                    latch.countDown();
                                }));

            }

            try {
                latch.await(1000, TimeUnit.MILLISECONDS);
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
