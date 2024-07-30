package io.quarkus.devspace.operator;

import static io.javaoperatorsdk.operator.api.reconciler.Constants.WATCH_ALL_NAMESPACES;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;

import org.apache.commons.lang3.RandomStringUtils;
import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

@ControllerConfiguration(namespaces = WATCH_ALL_NAMESPACES, name = "devspace")
@CSVMetadata(displayName = "Devspace operator", description = "Setup of Devspace for a specific service")
public class DevspaceReconciler implements Reconciler<Devspace>, Cleaner<Devspace> {
    protected static final Logger log = Logger.getLogger(DevspaceReconciler.class);

    @Inject
    OpenShiftClient client;

    private DevspaceConfigSpec getDevspaceConfig(Devspace primary) {
        MixedOperation<DevspaceConfig, KubernetesResourceList<DevspaceConfig>, Resource<DevspaceConfig>> configs = client
                .resources(DevspaceConfig.class);
        String configNamespace = "quarkus";
        String configName = "global";
        if (primary.getSpec() != null && primary.getSpec().getConfig() != null) {
            configName = primary.getSpec().getConfig();
            configNamespace = primary.getMetadata().getNamespace();
        }
        DevspaceConfig config = configs.inNamespace(configNamespace).withName(configName).get();
        return DevspaceConfigSpec.toDefaultedSpec(config);
    }

    public static String devspaceDeployment(Devspace primary) {
        return primary.getMetadata().getName() + "-proxy";
    }

    private void createProxyDeployment(Devspace primary, DevspaceConfigSpec config, AuthenticationType auth) {
        String serviceName = primary.getMetadata().getName();
        String name = devspaceDeployment(primary);
        String image = config.getProxy().getImage();
        String imagePullPolicy = config.getProxy().getImagePullPolicy();

        var container = new DeploymentBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(primary, name))
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(Map.of("run", name))
                .endSelector()
                .withNewTemplate().withNewMetadata().addToLabels(Map.of("run", name)).endMetadata()
                .withNewSpec()
                .addNewContainer();
        if (auth == AuthenticationType.secret) {
            container.addNewEnv().withName("SECRET").withNewValueFrom().withNewSecretKeyRef().withName(devspaceSecret(primary))
                    .withKey("password").endSecretKeyRef().endValueFrom().endEnv();
        }
        String logLevel = config.getLogLevel();
        if (primary.getSpec() != null && primary.getSpec().getLogLevel() != null) {
            logLevel = primary.getSpec().getLogLevel();
        }
        if (logLevel != null) {
            container.addNewEnv().withName("QUARKUS_LOG_CATEGORY__IO_QUARKUS_DEVSPACE__LEVEL").withValue(logLevel)
                    .endEnv();
        }
        long pollTimeout = config.getPollTimeoutSeconds() * 1000;
        long idleTimeout = config.getIdleTimeoutSeconds() * 1000;

        var spec = container
                .addNewEnv().withName("SERVICE_NAME").withValue(serviceName).endEnv()
                .addNewEnv().withName("SERVICE_HOST").withValue(origin(primary)).endEnv()
                .addNewEnv().withName("SERVICE_PORT").withValue("80").endEnv()
                .addNewEnv().withName("SERVICE_SSL").withValue("false").endEnv()
                .addNewEnv().withName("POLL_TIMEOUT").withValue(Long.toString(pollTimeout)).endEnv()
                .addNewEnv().withName("IDLE_TIMEOUT").withValue(Long.toString(idleTimeout)).endEnv()
                .addNewEnv().withName("CLIENT_API_PORT").withValue("8081").endEnv()
                .addNewEnv().withName("AUTHENTICATION_TYPE").withValue(auth.name()).endEnv()
                .withImage(image)
                .withImagePullPolicy(imagePullPolicy)
                .withName(name)
                .addNewPort().withName("proxy-http").withContainerPort(8080).withProtocol("TCP").endPort()
                .addNewPort().withName("devspace-http").withContainerPort(8081).withProtocol("TCP").endPort()
                .endContainer();

        Deployment deployment = spec
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.apps().deployments().resource(deployment).serverSideApply();
        primary.getStatus().getCleanup().add(0, new DevspaceStatus.CleanupResource("deployment", name));

    }

    public static String origin(Devspace primary) {
        return primary.getMetadata().getName() + "-origin";
    }

    private void createOriginService(Devspace primary, DevspaceConfigSpec config) {
        String serviceName = primary.getMetadata().getName();
        String name = origin(primary);
        Map<String, String> selector = null;
        if (primary.getStatus() == null || primary.getStatus().getOldSelectors() == null
                || primary.getStatus().getOldSelectors().isEmpty()) {
            selector = client.services().inNamespace(primary.getMetadata().getNamespace()).withName(serviceName).get().getSpec()
                    .getSelector();
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
        primary.getStatus().getCleanup().add(0, new DevspaceStatus.CleanupResource("service", name));
    }

    private static String devspaceServiceName(Devspace primary) {
        return primary.getMetadata().getName() + "-devspace";
    }

    private void createClientService(Devspace primary, DevspaceConfigSpec config) {
        String name = devspaceServiceName(primary);
        ExposePolicy exposePolicy = config.toExposePolicy();
        if (exposePolicy == ExposePolicy.defaultPolicy) {
            exposePolicy = ExposePolicy.nodePort;
        }
        var spec = new ServiceBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(primary, name))
                .withNewSpec();
        if (primary.getSpec() != null && primary.getSpec().getNodePort() != null) {
            spec.withType("NodePort")
                    .addNewPort().withNodePort(primary.getSpec().getNodePort());
        } else if (exposePolicy == ExposePolicy.nodePort) {
            spec.withType("NodePort");
        }

        Service service = spec
                .addNewPort()
                .withName("http")
                .withPort(80)
                .withProtocol("TCP")
                .withTargetPort(new IntOrString(8081))
                .endPort()
                .withSelector(Map.of("run", devspaceDeployment(primary)))
                .endSpec().build();
        client.resource(service).serverSideApply();

        int routerTimeout = config.getPollTimeoutSeconds() + 1;

        if (exposePolicy == ExposePolicy.secureRoute) {
            String routeName = primary.getMetadata().getName() + "-devspace";
            Route route = new RouteBuilder()
                    .withMetadata(DevspaceReconciler.createMetadataWithAnnotations(primary, routeName,
                            "haproxy.router.openshift.io/timeout", routerTimeout + "s"))
                    .withNewSpec().withNewTo().withKind("Service").withName(devspaceServiceName(primary))
                    .endTo()
                    .withNewPort().withNewTargetPort("http").endPort()
                    .withNewTls().withTermination("edge").withInsecureEdgeTerminationPolicy("Redirect").endTls()
                    .endSpec().build();
            client.adapt(OpenShiftClient.class).routes().resource(route).serverSideApply();
            primary.getStatus().getCleanup().add(0, new DevspaceStatus.CleanupResource("route", routeName));
        } else if (exposePolicy == ExposePolicy.route) {
            String routeName = primary.getMetadata().getName() + "-devspace";
            Route route = new RouteBuilder()
                    .withMetadata(DevspaceReconciler.createMetadataWithAnnotations(primary, routeName,
                            "haproxy.router.openshift.io/timeout", routerTimeout + "s"))
                    .withNewSpec().withNewTo().withKind("Service").withName(devspaceServiceName(primary))
                    .endTo()
                    .withNewPort().withNewTargetPort("http").endPort()
                    .endSpec().build();
            client.adapt(OpenShiftClient.class).routes().resource(route).serverSideApply();
            primary.getStatus().getCleanup().add(0, new DevspaceStatus.CleanupResource("route", routeName));
        }
        primary.getStatus().getCleanup().add(0, new DevspaceStatus.CleanupResource("service", name));
    }

    private boolean isOpenshift() {
        for (APIGroup group : client.getApiGroups().getGroups()) {
            if (group.getName().contains("openshift"))
                return true;
        }
        return false;
    }

    private static String devspaceSecret(Devspace primary) {
        return primary.getMetadata().getName() + "-secret";
    }

    private void createSecret(Devspace devspace) {
        String name = devspaceSecret(devspace);
        String password = RandomStringUtils.randomAlphabetic(10);
        Secret secret = new SecretBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(devspace, name))
                .addToStringData("password", password)
                .build();
        client.secrets().resource(secret).serverSideApply();
        devspace.getStatus().getCleanup().add(0, new DevspaceStatus.CleanupResource("secret", name));
    }

    @Override
    public UpdateControl<Devspace> reconcile(Devspace devspace, Context<Devspace> context) {
        if (devspace.getStatus() == null) {
            DevspaceStatus status = new DevspaceStatus();
            devspace.setStatus(status);
            try {
                ServiceResource<Service> serviceResource = client.services().inNamespace(devspace.getMetadata().getNamespace())
                        .withName(devspace.getMetadata().getName());
                Service service = serviceResource.get();
                if (service == null) {
                    status.setError("Service does not exist");
                    return UpdateControl.updateStatus(devspace);
                }
                DevspaceConfigSpec config = getDevspaceConfig(devspace);
                AuthenticationType auth = config.toAuthenticationType();
                if (auth == AuthenticationType.secret) {
                    createSecret(devspace);
                }
                createProxyDeployment(devspace, config, auth);
                createOriginService(devspace, config);
                createClientService(devspace, config);

                Map<String, String> oldSelectors = new HashMap<>();
                oldSelectors.putAll(service.getSpec().getSelector());
                status.setOldSelectors(oldSelectors);
                status.setOldExternalTrafficPolicy(service.getSpec().getExternalTrafficPolicy());
                String proxyDeploymentName = devspaceDeployment(devspace);
                UnaryOperator<Service> edit = (s) -> {
                    ServiceBuilder builder = new ServiceBuilder(s);
                    ServiceFluent<ServiceBuilder>.SpecNested<ServiceBuilder> spec = builder.editSpec();
                    spec.getSelector().clear();
                    spec.getSelector().put("run", proxyDeploymentName);
                    // Setting externalTrafficPolicy to Local is for getting clientIp address
                    // when using NodePort. If service is not NodePort then you can't use this
                    // spec.withExternalTrafficPolicy("Local");
                    return spec.endSpec().build();
                };
                serviceResource.edit(edit);

                status.setCreated(true);
                return UpdateControl.updateStatus(devspace);
            } catch (RuntimeException e) {
                status.setError(e.getMessage());
                log.error("Error creating devspace " + devspace.getMetadata().getName(), e);
                return UpdateControl.updateStatus(devspace);
            }
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
        log.info("cleanup");
        if (devspace.getStatus() == null) {
            return DeleteControl.defaultDelete();
        }
        if (devspace.getStatus().getOldSelectors() != null && !devspace.getStatus().getOldSelectors().isEmpty()) {
            resetServiceSelector(client, devspace);
        }
        if (devspace.getStatus().getCleanup() != null) {
            for (DevspaceStatus.CleanupResource cleanup : devspace.getStatus().getCleanup()) {
                log.info("Cleanup: " + cleanup.getType() + " " + cleanup.getName());
                if (cleanup.getType().equals("secret")) {
                    suppress(() -> client.secrets().inNamespace(devspace.getMetadata().getNamespace())
                            .withName(cleanup.getName())
                            .delete());
                } else if (cleanup.getType().equals("route")) {
                    suppress(() -> client.adapt(OpenShiftClient.class).routes()
                            .inNamespace(devspace.getMetadata().getNamespace())
                            .withName(cleanup.getName()).delete());
                } else if (cleanup.getType().equals("service")) {
                    suppress(() -> client.services().inNamespace(devspace.getMetadata().getNamespace())
                            .withName(cleanup.getName()).delete());
                } else if (cleanup.getType().equals("deployment")) {
                    suppress(() -> client.apps().deployments().inNamespace(devspace.getMetadata().getNamespace())
                            .withName(cleanup.getName()).delete());
                }
            }
        }

        return DeleteControl.defaultDelete();
    }

    public static void resetServiceSelector(KubernetesClient client, Devspace devspace) {
        ServiceResource<Service> serviceResource = client.services().inNamespace(devspace.getMetadata().getNamespace())
                .withName(devspace.getMetadata().getName());
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

    static ObjectMeta createMetadataWithAnnotations(Devspace resource, String name, String... annotations) {
        final var metadata = resource.getMetadata();
        ObjectMetaBuilder builder = new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(metadata.getNamespace())
                .withLabels(Map.of("app.kubernetes.io/name", name));
        Map<String, String> aMap = new HashMap<>();

        for (int i = 0; i < annotations.length; i++) {
            aMap.put(annotations[i], annotations[++i]);
        }
        return builder.withAnnotations(aMap).build();
    }
}
