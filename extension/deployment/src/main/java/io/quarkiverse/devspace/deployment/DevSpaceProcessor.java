package io.quarkiverse.devspace.deployment;

import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.devspace.DevSpaceProxyRecorder;
import io.quarkus.builder.BuildException;
import io.quarkus.deployment.IsNormal;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.devspace.client.DevspaceConnectionConfig;
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
            DevspaceConnectionConfig devspace = DevspaceConnectionConfig.fromUri(config.uri.get());
            if (devspace.error != null) {
                throw new RuntimeException(devspace.error);
            }
            log.info("*********** manual start: " + config.manualStart);
            proxy.init(vertx.getVertx(), shutdown, devspace, config.manualStart);
        }
    }
}
