package io.quarkus.devspace.client;

import java.util.concurrent.TimeoutException;

import io.quarkus.devspace.ProxyUtils;
import io.quarkus.devspace.server.DevProxyServer;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;

public class DevProxyClient extends AbstractDevProxyClient {

    protected HttpClient serviceClient;

    protected DevProxyClient() {

    }

    public static DevProxyClientBuilder create(Vertx vertx) {
        return new DevProxyClientBuilder(vertx);
    }

    @Override
    public void forcedShutdown() {
        super.forcedShutdown();
        serviceClient.close();
    }

    @Override
    protected void processPoll(HttpClientResponse pollResponse) {
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

    @Override
    public void shutdown() {
        super.shutdown();
        ProxyUtils.await(1000, serviceClient.close());
    }
}
