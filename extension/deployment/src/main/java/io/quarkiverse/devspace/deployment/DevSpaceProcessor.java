package io.quarkiverse.devspace.deployment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.devspace.DevSpaceProxyRecorder;
import io.quarkiverse.devspace.ExtractedConfig;
import io.quarkus.builder.BuildException;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.vertx.core.deployment.CoreVertxBuildItem;
import io.quarkus.vertx.http.deployment.RequireVirtualHttpBuildItem;

public class DevSpaceProcessor {
    private static final Logger log = Logger.getLogger(DevSpaceProcessor.class);

    @BuildStep(onlyIfNot = IsNormal.class) // This is required for testing so run it even if devservices.enabled=false
    public RequireVirtualHttpBuildItem requestVirtualHttp(DevspaceConfig config) throws BuildException {

        if (config.uri.isPresent()) {
            return RequireVirtualHttpBuildItem.MARKER;
        } else {
            return null;
        }
    }

    @Record(ExecutionTime.RUNTIME_INIT)
    @BuildStep(onlyIfNot = IsNormal.class) // This is required for testing so run it even if devservices.enabled=false
    public void recordProxy(CoreVertxBuildItem vertx,
            List<ServiceStartBuildItem> orderServicesFirst, // try to order this after service recorders
            ShutdownContextBuildItem shutdown,
            DevspaceConfig config,
            DevSpaceProxyRecorder proxy) {
        if (config.uri.isPresent()) {
            URI uri = null;
            try {
                uri = new URI(config.uri.get());
            } catch (URISyntaxException e) {
                throw new RuntimeException("Bad URI value for quarkus.http.devspace: '" + config.uri.get());
            }
            ExtractedConfig devspace = new ExtractedConfig();
            devspace.host = uri.getHost();
            devspace.ssl = uri.getScheme().equalsIgnoreCase("https");
            devspace.port = uri.getPort() == -1 ? (devspace.ssl ? 443 : 80) : uri.getPort();

            boolean needSession = false;
            for (String pair : uri.getQuery().split("&")) {
                int idx = pair.indexOf("=");
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                if ("session".equals(key)) {
                    devspace.session = value;
                } else if ("who".equals(key)) {
                    devspace.who = value;
                } else if ("query".equals(key)) {
                    if (devspace.queries == null)
                        devspace.queries = new ArrayList<>();
                    devspace.queries.add(value);
                    needSession = true;
                } else if ("path".equals(key)) {
                    if (devspace.paths == null)
                        devspace.paths = new ArrayList<>();
                    devspace.paths.add(value);
                    needSession = true;
                } else if ("header".equals(key)) {
                    if (devspace.headers == null)
                        devspace.headers = new ArrayList<>();
                    devspace.headers.add(value);
                    needSession = true;
                }
            }
            if (devspace.who == null) {
                throw new RuntimeException("quarkus.http.devspace is missing who parameter");
            }
            if (needSession && devspace.session == null) {
                throw new RuntimeException("quarkus.http.devspace uri is missing session parameter");
            }
            log.info("*********** manual start: " + config.manualStart);
            proxy.init(vertx.getVertx(), shutdown, devspace, config.manualStart);
        }
    }
}
