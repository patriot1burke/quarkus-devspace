package io.quarkus.devspace.operator;

import java.util.Map;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

public class DevspaceDeploymentDependent extends CRUDKubernetesDependentResource<Deployment, Devspace> {
    protected static final Logger log = Logger.getLogger(DevspaceDeploymentDependent.class);

    public DevspaceDeploymentDependent() {
        super(Deployment.class);
    }

    //@ConfigProperty(name="quarkus.application.version")
    String quarkusVersion = "999-SNAPSHOT";

    public static String devspaceDeployment(Devspace primary) {
        return primary.getMetadata().getName() + "-proxy";
    }

    @Override
    protected Deployment desired(Devspace primary, Context<Devspace> context) {
        log.info("enter desired");
        String serviceName = primary.getMetadata().getName();
        String name = devspaceDeployment(primary);

        return new DeploymentBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(primary, name))
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("run", name))
                .endSelector()
                .withNewTemplate().withNewMetadata().addToLabels(Map.of("run", name)).endMetadata()
                .withNewSpec()
                .addNewContainer()
                .addNewEnv().withName("SERVICE_NAME").withValue(serviceName).endEnv()
                .addNewEnv().withName("SERVICE_HOST").withValue(OriginServiceDependent.origin(primary)).endEnv()
                .addNewEnv().withName("SERVICE_PORT").withValue("80").endEnv()
                .addNewEnv().withName("SERVICE_SSL").withValue("false").endEnv()
                .addNewEnv().withName("CLIENT_API_PORT").withValue("8081").endEnv()
                .withImage("docker.io/io.quarkus/quarkus-devspace-proxy:" + quarkusVersion)
                .withImagePullPolicy("IfNotPresent")
                .withName(name)
                .addNewPort().withName("proxy-http").withContainerPort(8080).withProtocol("TCP").endPort()
                .addNewPort().withName("devspace-http").withContainerPort(8081).withProtocol("TCP").endPort()
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }
}
