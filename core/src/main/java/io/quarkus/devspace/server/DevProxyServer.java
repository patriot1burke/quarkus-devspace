package io.quarkus.devspace.server;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.quarkus.devspace.ProxyUtils;
import io.quarkus.devspace.server.auth.NoAuth;
import io.quarkus.devspace.server.auth.ProxySessionAuth;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pipe;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.Body;
import io.vertx.httpproxy.HttpProxy;

public class DevProxyServer {

    public static final String VERSION = "1.0";

    public static AutoCloseable create(Vertx vertx, ServiceConfig config, int proxyPort, int clientApiPort) {
        HttpServer proxy = vertx.createHttpServer();
        HttpServer clientApi = vertx.createHttpServer();
        DevProxyServer proxyServer = new DevProxyServer();
        Router proxyRouter = Router.router(vertx);
        Router clientApiRouter = Router.router(vertx);
        proxyServer.init(vertx, proxyRouter, clientApiRouter, config);
        ProxyUtils.await(1000, proxy.requestHandler(proxyRouter).listen(proxyPort));
        ProxyUtils.await(1000, clientApi.requestHandler(clientApiRouter).listen(clientApiPort));
        return new AutoCloseable() {

            @Override
            public void close() throws Exception {
                ProxyUtils.await(1000, proxy.close());
                ProxyUtils.await(1000, clientApi.close());
            }
        };
    }

    public class Poller {
        final ProxySession session;
        final RoutingContext pollerCtx;
        final AtomicBoolean closed = new AtomicBoolean();
        boolean enqueued;

        public Poller(ProxySession session, RoutingContext pollerCtx) {
            this.session = session;
            this.pollerCtx = pollerCtx;
        }

        private void enqueuePoller() {
            HttpServerResponse pollResponse = pollerCtx.response();
            pollResponse.closeHandler(v1 -> connectionFailure());
            pollResponse.exceptionHandler(v1 -> connectionFailure());
            pollerCtx.request().connection().closeHandler(v -> connectionFailure());
            pollerCtx.request().connection().exceptionHandler(v -> connectionFailure());
            enqueued = true;
        }

        private void connectionFailure() {
            closed.set(true);
            if (!enqueued)
                return;
            synchronized (session.pollLock) {
                session.awaitingPollers.remove(this);
            }
            session.pollDisconnect();
        }

        public boolean isClosed() {
            return closed.get();
        }

        public void closeSession() {
            if (closed.get())
                return;
            pollerCtx.response().setStatusCode(404).end();
        }

        public void forwardRequestToPollerClient(RoutingContext proxiedCtx) {
            log.infov("Forward request to poller client {0} isClosed {1}", session.sessionId, isClosed());
            enqueued = false;
            HttpServerRequest proxiedRequest = proxiedCtx.request();
            HttpServerResponse pollResponse = pollerCtx.response();
            session.pollProcessing();
            pollResponse.setStatusCode(200);
            proxiedRequest.headers().forEach((key, val) -> {
                if (key.equalsIgnoreCase("Content-Length")) {
                    return;
                }
                pollResponse.headers().add(HEADER_FORWARD_PREFIX + key, val);
            });
            String requestId = session.queueResponse(proxiedCtx);
            pollResponse.putHeader(REQUEST_ID_HEADER, requestId);
            String responsePath = CLIENT_API_PATH + "/push/response/session/" + session.sessionId + "/request/"
                    + requestId;
            pollResponse.putHeader(RESPONSE_LINK, responsePath);
            pollResponse.putHeader(METHOD_HEADER, proxiedRequest.method().toString());
            pollResponse.putHeader(URI_HEADER, proxiedRequest.uri());
            sendBody(proxiedRequest, pollResponse);
            log.info("Forward request to poller finished");
        }
    }

    public class ProxySession {
        final ConcurrentHashMap<String, RoutingContext> responsePending = new ConcurrentHashMap<>();
        final ServiceProxy proxy;
        final long timerId;
        final String sessionId;
        final String who;
        final String token = UUID.randomUUID().toString();
        final Deque<RoutingContext> awaiting = new LinkedList<>();
        final Deque<Poller> awaitingPollers = new LinkedList<>();
        final Object pollLock = new Object();

        List<RequestSessionMatcher> matchers = new ArrayList<>();

        volatile boolean running = true;
        volatile long lastPoll;
        AtomicLong requestId = new AtomicLong(System.currentTimeMillis());

        ProxySession(ServiceProxy proxy, String sessionId, String who) {
            timerId = vertx.setPeriodic(POLL_TIMEOUT, this::timerCallback);
            this.proxy = proxy;
            this.sessionId = sessionId;
            this.who = who;
        }

        void timerCallback(Long t) {
            checkIdle();
        }

        void checkIdle() {
            if (!running)
                return;
            if (System.currentTimeMillis() - lastPoll > POLL_TIMEOUT) {
                log.warnv("Shutting down session {0} due to timeout.", sessionId);
                shutdown();
            }
        }

        String queueResponse(RoutingContext ctx) {
            String requestId = Long.toString(this.requestId.incrementAndGet());
            responsePending.put(requestId, ctx);
            return requestId;
        }

        public String getToken() {
            return token;
        }

        public boolean validateToken(RoutingContext ctx) {
            String header = ctx.request().getHeader("Authorization");
            if (header == null) {
                log.error("Authorization failed: no Authorization header");
                ctx.response().setStatusCode(401).end();
                return false;
            }
            int idx = header.indexOf("Bearer");
            if (idx == -1) {
                log.error("Authorization failed: bad Authorization header");
                ctx.response().setStatusCode(401).end();
                return false;
            }
            String token = header.substring(idx + "Bearer".length()).trim();
            if (!this.token.equals(token)) {
                log.error("Authorization failed: bad session token");
                ctx.response().setStatusCode(401).end();
                return false;
            }
            return true;
        }

        RoutingContext dequeueResponse(String requestId) {
            return responsePending.remove(requestId);
        }

        void shutdown() {
            if (!running)
                return;
            running = false;
            proxy.sessions.remove(this.sessionId);
            vertx.cancelTimer(timerId);

            synchronized (pollLock) {
                while (!awaiting.isEmpty()) {
                    RoutingContext proxiedCtx = awaiting.poll();
                    if (proxiedCtx != null) {
                        proxy.proxy.handle(proxiedCtx.request());
                    }
                }
                awaitingPollers.stream().forEach((poller) -> {
                    poller.closeSession();
                });
            }
        }

        void pollStarted() {
            lastPoll = System.currentTimeMillis();
        }

        void pollProcessing() {
            lastPoll = System.currentTimeMillis();
        }

        void pollEnded() {
            lastPoll = System.currentTimeMillis();
        }

        void pollDisconnect() {
            if (!running) {
                return;
            }
            checkIdle();
        }

        public void poll(RoutingContext pollingCtx) {
            this.pollStarted();
            RoutingContext proxiedCtx = null;
            Poller poller = new Poller(this, pollingCtx);
            synchronized (pollLock) {
                proxiedCtx = awaiting.poll();
                if (proxiedCtx == null) {
                    poller.enqueuePoller();
                    awaitingPollers.add(poller);
                    return;
                }
            }
            poller.forwardRequestToPollerClient(proxiedCtx);
        }

        public void handleProxiedRequest(RoutingContext ctx) {
            log.info("handleProxiedRequest");
            ctx.request().pause();
            Poller poller = null;
            synchronized (pollLock) {
                poller = awaitingPollers.poll();
                if (poller == null) {
                    log.info("No pollers, enqueueing");
                    awaiting.add(ctx);
                    return;
                }
                poller.enqueued = false;
            }
            poller.forwardRequestToPollerClient(ctx);
        }
    }

    public class ServiceProxy {
        final HttpClient client;

        public ServiceProxy(ServiceConfig service) {
            this.config = service;
            HttpClientOptions options = new HttpClientOptions();
            if (service.isSsl()) {
                options.setSsl(true).setTrustAll(true);
            }
            this.client = vertx.createHttpClient(options);
            this.proxy = HttpProxy.reverseProxy(client);
            proxy.origin(service.getPort(), service.getHost());
        }

        final ServiceConfig config;
        final HttpProxy proxy;
        final Map<String, ProxySession> sessions = new ConcurrentHashMap<>();

        void shutdown() {
            for (ProxySession session : sessions.values()) {
                session.shutdown();
            }
        }
    }

    public static final String API_PATH = "/_devspace/api";
    public static final String CLIENT_API_PATH = "/_devspace/client";
    public static final String GLOBAL_PROXY_SESSION = "_devspace_global";
    public static final String SESSION_HEADER = "X-DevSpace-Session";
    public static final String HEADER_FORWARD_PREFIX = "X-DevSpace-Fwd-";
    public static final String STATUS_CODE_HEADER = "X-DevSpace-Status-Code";
    public static final String METHOD_HEADER = "X-DevSpace-Method";
    public static final String URI_HEADER = "X-DevSpace-Uri";
    public static final String REQUEST_ID_HEADER = "X-DevSpace-Request-Id";
    public static final String RESPONSE_LINK = "X-DevSpace-Response-Path";
    public static final String POLL_LINK = "X-DevSpace-Poll-Path";

    protected long POLL_TIMEOUT = 5000;
    protected static final Logger log = Logger.getLogger(DevProxyServer.class);
    protected ServiceProxy service;
    protected Vertx vertx;
    protected ProxySessionAuth auth = new NoAuth();

    public long getPollTimeout() {
        return POLL_TIMEOUT;
    }

    public void setPollTimeout(long timeout) {
        POLL_TIMEOUT = timeout;
    }

    public void setAuth(ProxySessionAuth auth) {
        this.auth = auth;
    }

    public void init(Vertx vertx, Router proxyRouter, Router clientApiRouter, ServiceConfig config) {
        this.vertx = vertx;
        proxyRouter.route().handler((context) -> {
            if (context.get("continue-sent") == null) {
                String expect = context.request().getHeader(HttpHeaderNames.EXPECT);
                if (expect != null && expect.equalsIgnoreCase("100-continue")) {
                    context.put("continue-sent", true);
                    context.response().writeContinue();
                }
            }
            context.next();
        });
        // CLIENT API
        clientApiRouter.route(CLIENT_API_PATH + "/version").method(HttpMethod.GET)
                .handler((ctx) -> ctx.response().setStatusCode(200).putHeader("Content-Type", "text/plain").end(VERSION));
        clientApiRouter.route(CLIENT_API_PATH + "/poll/session/:session").method(HttpMethod.POST).handler(this::pollNext);
        clientApiRouter.route(CLIENT_API_PATH + "/connect").method(HttpMethod.POST).handler(this::clientConnect);
        clientApiRouter.route(CLIENT_API_PATH + "/connect").method(HttpMethod.DELETE).handler(this::deleteClientConnection);
        clientApiRouter.route(CLIENT_API_PATH + "/push/response/session/:session/request/:request")
                .method(HttpMethod.POST)
                .handler(this::pushResponse);
        clientApiRouter.route(CLIENT_API_PATH + "/push/response/session/:session/request/:request")
                .method(HttpMethod.DELETE)
                .handler(this::deletePushResponse);
        clientApiRouter.route(CLIENT_API_PATH + "/*").handler(routingContext -> routingContext.fail(404));

        // API routes
        proxyRouter.route(API_PATH + "/version").method(HttpMethod.GET)
                .handler((ctx) -> ctx.response().setStatusCode(200).putHeader("Content-Type", "text/plain").end(VERSION));
        proxyRouter.route(API_PATH + "/clientIp").method(HttpMethod.GET)
                .handler((ctx) -> ctx.response().setStatusCode(200).putHeader("Content-Type", "text/plain")
                        .end("" + ctx.request().remoteAddress().hostAddress()));
        proxyRouter.route(API_PATH + "/cookie/set").method(HttpMethod.GET).handler(this::setCookieApi);
        proxyRouter.route(API_PATH + "/cookie/get").method(HttpMethod.GET).handler(this::getCookieApi);
        proxyRouter.route(API_PATH + "/cookie/remove").method(HttpMethod.GET).handler(this::removeCookieApi);
        clientApiRouter.route(API_PATH + "/*").handler(routingContext -> routingContext.fail(404));
        proxyRouter.route().handler(this::proxy);

        // proxy to deployed services
        service = new ServiceProxy(config);
    }

    static void error(RoutingContext ctx, int status, String msg) {
        ctx.response().setStatusCode(status).putHeader("ContentType", "text/plain").end(msg);

    }

    static Boolean isChunked(MultiMap headers) {
        List<String> te = headers.getAll("transfer-encoding");
        if (te != null) {
            boolean chunked = false;
            for (String val : te) {
                if (val.equals("chunked")) {
                    chunked = true;
                } else {
                    return null;
                }
            }
            return chunked;
        } else {
            return false;
        }
    }

    private static void sendBody(HttpServerRequest source, HttpServerResponse destination) {
        long contentLength = -1L;
        String contentLengthHeader = source.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (contentLengthHeader != null) {
            try {
                contentLength = Long.parseLong(contentLengthHeader);
            } catch (NumberFormatException e) {
                // Ignore ???
            }
        }
        Body body = Body.body(source, contentLength);
        long len = body.length();
        if (len >= 0) {
            destination.putHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(len));
        } else {
            Boolean isChunked = DevProxyServer.isChunked(source.headers());
            destination.setChunked(len == -1 && Boolean.TRUE == isChunked);
        }
        Pipe<Buffer> pipe = body.stream().pipe();
        pipe.endOnComplete(true);
        pipe.endOnFailure(false);
        pipe.to(destination, ar -> {
            if (ar.failed()) {
                log.info("Failed to pipe response on poll");
                destination.reset();
            }
        });
    }

    public void setCookieApi(RoutingContext ctx) {
        String session = ctx.queryParams().get("session");
        if (session == null) {
            ctx.response().setStatusCode(400).putHeader("Content-Type", "text/plain")
                    .end("You must specify a session query param in url to set session");
        } else {
            ctx.response()
                    .setStatusCode(200)
                    .addCookie(Cookie.cookie(SESSION_HEADER, session).setPath("/"))
                    .putHeader("Content-Type", "text/plain")
                    .end("Session cookie set for session: " + session);
        }
    }

    public void removeCookieApi(RoutingContext ctx) {
        ctx.response()
                .setStatusCode(200)
                .addCookie(Cookie.cookie(SESSION_HEADER, "null").setPath("/").setMaxAge(0))
                .putHeader("Content-Type", "text/plain")
                .end("Session cookie removed");

    }

    public void getCookieApi(RoutingContext ctx) {
        String sessionId = null;
        Cookie cookie = ctx.request().getCookie(SESSION_HEADER);
        if (cookie != null) {
            sessionId = cookie.getValue();
        } else {
            sessionId = "NONE";
        }
        ctx.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "text/plain")
                .end("Session cookie: " + sessionId);
    }

    public void proxy(RoutingContext ctx) {
        log.infov("*** entered proxy {0} {1}", ctx.request().method().toString(), ctx.request().uri());

        ProxySession found = null;
        find: for (ProxySession session : service.sessions.values()) {
            for (RequestSessionMatcher matcher : session.matchers) {
                if (matcher.matches(ctx)) {
                    found = session;
                    break find;
                }
            }
        }
        if (found == null) {
            found = service.sessions.get(GLOBAL_PROXY_SESSION);
        }

        if (found != null && found.running) {
            found.handleProxiedRequest(ctx);
        } else {
            service.proxy.handle(ctx.request());
        }
    }

    public void clientConnect(RoutingContext ctx) {
        // TODO: add security 401 protocol

        log.info("Connect: " + ctx.request().absoluteURI());
        List<String> sessionQueryParam = ctx.queryParam("session");
        String sessionId = null;
        if (!sessionQueryParam.isEmpty()) {
            sessionId = sessionQueryParam.get(0);
        }
        List<String> whoQueryParam = ctx.queryParam("who");
        String who = null;
        if (!whoQueryParam.isEmpty()) {
            who = whoQueryParam.get(0);
        }
        if (who == null) {
            ctx.response().setStatusCode(400).end();
            log.errorv("Failed Client Connect to service {0} and session {1}: who identity not sent", service.config.getName(),
                    sessionId);
            return;
        }
        List<RequestSessionMatcher> matchers = new ArrayList<>();
        for (Map.Entry<String, String> entry : ctx.queryParams()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("query".equals(key)) {
                if (sessionId == null) {
                    ctx.response().setStatusCode(400).putHeader("Content-Type", "text/plain").end("Must declare session");
                    return;
                }
                String query = value;
                String qvalue = sessionId;
                int idx = value.indexOf('=');
                if (idx > 0) {
                    query = value.substring(0, idx);
                    qvalue = value.substring(idx + 1);
                }
                matchers.add(new QueryParamSessionMatcher(query, qvalue));
            } else if ("path".equals(key)) {
                if (sessionId == null) {
                    ctx.response().setStatusCode(400).putHeader("Content-Type", "text/plain").end("Must declare session");
                    return;
                }
                log.info("********** Adding PathParam " + value);
                matchers.add(new PathParamSessionMatcher(value));
            } else if ("header".equals(key)) {
                if (sessionId == null) {
                    ctx.response().setStatusCode(400).putHeader("Content-Type", "text/plain").end("Must declare session");
                    return;
                }
                String header = value;
                int idx = value.indexOf('=');
                String hvalue = sessionId;
                if (idx > 0) {
                    header = value.substring(0, idx);
                    hvalue = value.substring(idx + 1);
                }
                matchers.add(new HeaderOrCookieSessionMatcher(header, hvalue));
            } else if ("clientIp".equals(key)) {
                String ip = value;
                if (ip == null) {
                    ip = ctx.request().remoteAddress().hostAddress();
                }
                if (sessionId == null) {
                    sessionId = ip;
                }
            }
        }
        if (sessionId == null) {
            sessionId = GLOBAL_PROXY_SESSION;
        } else {
            matchers.add(new HeaderOrCookieSessionMatcher(SESSION_HEADER, sessionId));
        }
        synchronized (this) {
            ProxySession session = service.sessions.get(sessionId);
            if (session != null) {
                if (!who.equals(session.who)) {
                    log.errorv("Failed Client Connect for {0} to service {1} and session {2}: Existing connection {3}", who,
                            service.config.getName(), sessionId, session.who);
                    ctx.response().setStatusCode(409).putHeader("Content-Type", "text/plain").end(session.who);
                    return;
                }
                if (auth.authorized(ctx, session)) {
                    ctx.response().setStatusCode(204).putHeader(POLL_LINK, CLIENT_API_PATH + "/poll/session/" + sessionId)
                            .end();
                }
            } else {
                String finalSessionId = sessionId;
                String finalWho = who;
                auth.authenticate(ctx, () -> {
                    ProxySession newSession = new ProxySession(service, finalSessionId, finalWho);
                    if (matchers.isEmpty()) {
                        matchers.add(new HeaderOrCookieSessionMatcher(SESSION_HEADER, finalSessionId));
                    }
                    newSession.matchers = matchers;
                    service.sessions.put(finalSessionId, newSession);

                    auth.propagateToken(ctx, newSession);
                    ctx.response().setStatusCode(204)
                            .putHeader(POLL_LINK, CLIENT_API_PATH + "/poll/session/" + finalSessionId)
                            .end();
                });

            }
        }
    }

    public void deleteClientConnection(RoutingContext ctx) {
        // TODO: add security 401 protocol
        List<String> sessionQueryParam = ctx.queryParam("session");
        String sessionId = GLOBAL_PROXY_SESSION;
        if (!sessionQueryParam.isEmpty()) {
            sessionId = sessionQueryParam.get(0);
        }
        ProxySession session = service.sessions.get(sessionId);
        if (session != null) {
            if (!auth.authorized(ctx, session))
                return;
            log.infov("Shutdown session {0}", sessionId);
            session.shutdown();
            ctx.response().setStatusCode(204).end();
        } else {
            ctx.response().setStatusCode(404).end();
        }
    }

    public void pushResponse(RoutingContext ctx) {
        String sessionId = ctx.pathParam("session");
        String requestId = ctx.pathParam("request");
        String kp = ctx.queryParams().get("keepAlive");
        boolean keepAlive = kp == null ? true : Boolean.parseBoolean(kp);

        ProxySession session = service.sessions.get(sessionId);
        if (session == null) {
            log.error("Push response could not find service " + service.config.getName() + " session ");
            DevProxyServer.error(ctx, 404, "Session not found for service " + service.config.getName());
            return;
        }
        if (!auth.authorized(ctx, session))
            return;
        RoutingContext proxiedCtx = session.dequeueResponse(requestId);
        if (proxiedCtx == null) {
            log.error("Push response could not request " + requestId + " for service " + service.config.getName() + " session "
                    + sessionId);
            ctx.response().putHeader(POLL_LINK, CLIENT_API_PATH + "/poll/session/" + sessionId);
            DevProxyServer.error(ctx, 404, "Request " + requestId + " not found");
            return;
        }
        HttpServerResponse proxiedResponse = proxiedCtx.response();
        HttpServerRequest pushedResponse = ctx.request();
        String status = pushedResponse.getHeader(STATUS_CODE_HEADER);
        if (status == null) {
            log.error("Failed to get status header");
            DevProxyServer.error(proxiedCtx, 500, "Failed");
            DevProxyServer.error(ctx, 400, "Failed to get status header");
            return;
        }
        proxiedResponse.setStatusCode(Integer.parseInt(status));
        pushedResponse.headers().forEach((key, val) -> {
            int idx = key.indexOf(HEADER_FORWARD_PREFIX);
            if (idx == 0) {
                String headerName = key.substring(HEADER_FORWARD_PREFIX.length());
                proxiedResponse.headers().add(headerName, val);
            }
        });
        sendBody(pushedResponse, proxiedResponse);
        if (keepAlive) {
            log.infov("Keep alive {0} {1}", service.config.getName(), sessionId);
            session.pollProcessing();
            session.poll(ctx);
        } else {
            log.infov("End polling {0} {1}", service.config.getName(), sessionId);
            session.pollEnded();
            ctx.response().setStatusCode(204).end();
        }
    }

    public void deletePushResponse(RoutingContext ctx) {
        String sessionId = ctx.pathParam("session");
        String requestId = ctx.pathParam("request");

        ProxySession session = service.sessions.get(sessionId);
        if (session == null) {
            log.error("Delete push response could not find service " + service.config.getName() + " session ");
            DevProxyServer.error(ctx, 404, "Session not found for service " + service.config.getName());
            return;
        }
        if (!auth.authorized(ctx, session))
            return;
        RoutingContext proxiedCtx = session.dequeueResponse(requestId);
        if (proxiedCtx == null) {
            log.error("Delete push response could not find request " + requestId + " for service " + service.config.getName()
                    + " session " + sessionId);
            DevProxyServer.error(ctx, 404, "Request " + requestId + " not found");
            return;
        }
        proxiedCtx.fail(500);
        ctx.response().setStatusCode(204).end();
    }

    public void pollNext(RoutingContext ctx) {
        String sessionId = ctx.pathParam("session");

        ProxySession session = service.sessions.get(sessionId);
        if (session == null) {
            log.error("Poll next could not find service " + service.config.getName() + " session " + sessionId);
            DevProxyServer.error(ctx, 404, "Session not found for service " + service.config.getName());
            return;
        }
        if (!auth.authorized(ctx, session))
            return;
        log.infov("pollNext {0} {1}", service.config.getName(), sessionId);
        session.poll(ctx);
    }
}
