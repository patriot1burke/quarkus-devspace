package io.quarkus.devspace.operator;

import static io.javaoperatorsdk.operator.api.reconciler.Constants.WATCH_CURRENT_NAMESPACE;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

@ControllerConfiguration(namespaces = WATCH_CURRENT_NAMESPACE, name = "devspaceproxy")
@CSVMetadata(displayName = "Devspace operator", description = "Setup of Devspace for a specific service")
public class DevspaceReconciler implements Reconciler<Devspace>, Cleaner<Devspace> {
    protected static final Logger log = Logger.getLogger(DevspaceReconciler.class);

    @Inject
    KubernetesClient client;

    private DevspaceConfig getDevspaceConfig(Devspace primary) {
        MixedOperation<DevspaceConfig, KubernetesResourceList<DevspaceConfig>, Resource<DevspaceConfig>> configs = client
                .resources(DevspaceConfig.class);
        String configName = "global";
        if (primary.getSpec() != null && primary.getSpec().getConfig() != null) {
            configName = primary.getSpec().getConfig();
        }
        DevspaceConfig config = configs.inNamespace(primary.getMetadata().getNamespace()).withName(configName).get();
        return config;
    }

    public static String devspaceDeployment(Devspace primary) {
        return primary.getMetadata().getName() + "-proxy";
    }

    private void createProxyDeployment(Devspace primary, DevspaceConfig config) {
        String serviceName = primary.getMetadata().getName();
        String name = devspaceDeployment(primary);
        String image = "io.quarkus/quarkus-devspace-proxy:latest";
        String imagePullPolicy = "Always";
        if (config != null) {
            if (config.getSpec() != null && config.getSpec().getProxy() != null) {
                image = config.getSpec().getProxy().getImage() == null ? image : config.getSpec().getProxy().getImage();
                imagePullPolicy = config.getSpec().getProxy().getImagePullPolicy() == null ? imagePullPolicy
                        : config.getSpec().getProxy().getImagePullPolicy();
            }
        }

        Deployment deployment = new DeploymentBuilder()
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
                .addNewEnv().withName("SERVICE_HOST").withValue(origin(primary)).endEnv()
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
        client.resource(deployment).serverSideApply();
    }

    public static String origin(Devspace primary) {
        return primary.getMetadata().getName() + "-origin";
    }

    private void createOriginService(Devspace primary, DevspaceConfig config) {
        String serviceName = primary.getMetadata().getName();
        String name = origin(primary);
        Map<String, String> selector = null;
        if (primary.getStatus() == null || primary.getStatus().getOldSelectors() == null) {
            selector = client.services().withName(serviceName).get().getSpec().getSelector();
        } else {
            selector = primary.getStatus().getOldSelectors();
        }
        Service service = new ServiceBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(primary, name))
                .withNewSpec()
                .addNewPort()
                .withName("http")
                .withPort(80)
                .withProtocol("TCP")
                .withTargetPort(new IntOrString(8080))
                .endPort()
                .withSelector(selector)
                .withType("ClusterIP")
                .endSpec().build();
        client.resource(service).serverSideApply();
    }

    private static String devspaceServiceName(Devspace primary) {
        return primary.getMetadata().getName() + "-devspace";
    }

    private void createClientService(Devspace primary, DevspaceConfig config) {
        String name = devspaceServiceName(primary);
        Service service = new ServiceBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(primary, name))
                .withNewSpec()
                .addNewPort()
                .withName("http")
                .withPort(80)
                .withProtocol("TCP")
                .withTargetPort(new IntOrString(8081))
                .endPort()
                .withSelector(Map.of("run", devspaceDeployment(primary)))
                .withType("NodePort")
                .endSpec().build();
        client.resource(service).serverSideApply();
    }

    private boolean isOpenshift() {
        for (APIGroup group : client.getApiGroups().getGroups()) {
            if (group.getName().contains("openshift"))
                return true;
        }
        return false;
    }

    @Override
    public UpdateControl<Devspace> reconcile(Devspace devspace, Context<Devspace> context) {
        if (devspace.getStatus() == null) {
            DevspaceConfig config = getDevspaceConfig(devspace);
            createProxyDeployment(devspace, config);
            createOriginService(devspace, config);
            createClientService(devspace, config);

            final var name = devspace.getMetadata().getName();
            ServiceResource<Service> serviceResource = client.services().withName(name);
            Service service = serviceResource.get();
            log.info("Updating status to reflect old selectors");
            Map<String, String> oldSelectors = new HashMap<>();
            oldSelectors.putAll(service.getSpec().getSelector());
            DevspaceStatus status = new DevspaceStatus();
            status.setOldSelectors(oldSelectors);
            status.setOldExternalTrafficPolicy(service.getSpec().getExternalTrafficPolicy());
            devspace.setStatus(status);
            String proxyDeploymentName = devspaceDeployment(devspace);
            UnaryOperator<Service> edit = (s) -> {
                ServiceBuilder builder = new ServiceBuilder(s);
                ServiceFluent<ServiceBuilder>.SpecNested<ServiceBuilder> spec = builder.editSpec();
                spec.getSelector().clear();
                spec.getSelector().put("run", proxyDeploymentName);
                // Setting externalTrafficPolicy to Local is for getting clientIp address
                // when using NodePort.  If service is not NodePort then you can't use this
                // spec.withExternalTrafficPolicy("Local");
                return spec.endSpec().build();
            };
            serviceResource.edit(edit);
            return UpdateControl.updateStatus(devspace);
        } else {
            return UpdateControl.<Devspace> noUpdate();
        }
    }

    private void suppress(Runnable work) {
        try {
            work.run();
        } catch (Exception ignore) {

        }
    }

    @Override
    public DeleteControl cleanup(Devspace devspace, Context<Devspace> context) {
        log.debug("cleanup");
        if (devspace.getStatus() == null || devspace.getStatus().getOldSelectors() == null) {
            return DeleteControl.defaultDelete();
        }
        resetServiceSelector(client, devspace);
        suppress(() -> client.services().withName(devspaceServiceName(devspace)).delete());
        suppress(() -> client.services().withName(origin(devspace)).delete());
        suppress(() -> client.apps().deployments().withName(devspaceDeployment(devspace)).delete());

        return DeleteControl.defaultDelete();
    }

    public static void resetServiceSelector(KubernetesClient client, Devspace devspace) {
        ServiceResource<Service> serviceResource = client.services().withName(devspace.getMetadata().getName());
        UnaryOperator<Service> edit = (s) -> {
            return new ServiceBuilder(s)
                    .editSpec()
                    .withSelector(devspace.getStatus().getOldSelectors())
                    .withExternalTrafficPolicy(devspace.getStatus().getOldExternalTrafficPolicy())
                    .endSpec().build();

        };
        serviceResource.edit(edit);
    }

    static ObjectMeta createMetadata(Devspace resource, String name) {
        final var metadata = resource.getMetadata();
        return new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(metadata.getNamespace())
                .withLabels(Map.of("app.kubernetes.io/name", name))
                .build();
    }
}
