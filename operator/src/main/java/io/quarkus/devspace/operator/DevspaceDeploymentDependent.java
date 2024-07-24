package io.quarkus.devspace.operator;

import java.util.Map;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

public class DevspaceDeploymentDependent extends CRUDKubernetesDependentResource<Deployment, Devspace> {
    protected static final Logger log = Logger.getLogger(DevspaceDeploymentDependent.class);

    public DevspaceDeploymentDependent() {
        super(Deployment.class);
    }

    @Inject
    KubernetesClient client;

    public static String devspaceDeployment(Devspace primary) {
        return primary.getMetadata().getName() + "-proxy";
    }

    @Override
    protected Deployment desired(Devspace primary, Context<Devspace> context) {
        String serviceName = primary.getMetadata().getName();
        String name = devspaceDeployment(primary);
        MixedOperation<DevspaceConfig, KubernetesResourceList<DevspaceConfig>, Resource<DevspaceConfig>> configs = client
                .resources(DevspaceConfig.class);
        String configName = "global";
        if (primary.getSpec() != null && primary.getSpec().getConfig() != null) {
            configName = primary.getSpec().getConfig();
        }
        DevspaceConfig config = configs.inNamespace(primary.getMetadata().getNamespace()).withName(configName).get();
        String image = "io.quarkus/quarkus-devspace-proxy:latest";
        String imagePullPolicy = "Always";
        if (config != null) {
            if (config.getSpec() != null && config.getSpec().getProxy() != null) {
                image = config.getSpec().getProxy().getImage() == null ? image : config.getSpec().getProxy().getImage();
                imagePullPolicy = config.getSpec().getProxy().getImagePullPolicy() == null ? imagePullPolicy
                        : config.getSpec().getProxy().getImagePullPolicy();
            }
        }

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
                .withImage(image)
                .withImagePullPolicy(imagePullPolicy)
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
