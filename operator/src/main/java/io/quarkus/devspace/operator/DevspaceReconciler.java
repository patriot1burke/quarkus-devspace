package io.quarkus.devspace.operator;

import static io.javaoperatorsdk.operator.api.reconciler.Constants.WATCH_CURRENT_NAMESPACE;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceFluent;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ServiceResource;
import io.javaoperatorsdk.operator.api.config.informer.InformerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;

@ControllerConfiguration(namespaces = WATCH_CURRENT_NAMESPACE, name = "devspaceproxy", dependents = {
        @Dependent(type = DevspaceDeploymentDependent.class),
        @Dependent(type = DevspaceServiceDependent.class, useEventSourceWithName = "ServiceEventSource"),
        @Dependent(type = OriginServiceDependent.class, useEventSourceWithName = "ServiceEventSource")
})
@CSVMetadata(displayName = "Devspace operator", description = "Setup of Devspace for a specific service")
public class DevspaceReconciler implements Reconciler<Devspace>, Cleaner<Devspace>, EventSourceInitializer<Devspace> {
    protected static final Logger log = Logger.getLogger(DevspaceReconciler.class);

    @Inject
    KubernetesClient client;

    @Override
    public UpdateControl<Devspace> reconcile(Devspace devspace, Context<Devspace> context) {
        final var name = devspace.getMetadata().getName();
        log.infov("reconcile {0}", name);
        // retrieve the workflow reconciliation result and re-schedule if we have dependents that are not yet ready
        return context.managedDependentResourceContext().getWorkflowReconcileResult()
                .map(wrs -> {
                    if (wrs.allDependentResourcesReady()) {
                        ServiceResource<Service> serviceResource = client.services().withName(name);
                        Service service = serviceResource.get();
                        if (devspace.getStatus() == null) {
                            log.info("Updating status to reflect old selectors");
                            Map<String, String> oldSelectors = new HashMap<>();
                            oldSelectors.putAll(service.getSpec().getSelector());
                            DevspaceStatus status = new DevspaceStatus();
                            status.setOldSelectors(oldSelectors);
                            devspace.setStatus(status);
                            String proxyDeploymentName = DevspaceDeploymentDependent.devspaceDeployment(devspace);
                            UnaryOperator<Service> edit = (s) -> {
                                ServiceBuilder builder = new ServiceBuilder(s);
                                ServiceFluent<ServiceBuilder>.SpecNested<ServiceBuilder> spec = builder.editSpec();
                                spec.getSelector().clear();
                                spec.getSelector().put("run", proxyDeploymentName);
                                return spec.endSpec().build();

                            };
                            serviceResource.edit(edit);
                            return UpdateControl.updateStatus(devspace);
                        } else {
                            return UpdateControl.<Devspace> noUpdate();
                        }
                    } else {
                        final var duration = Duration.ofSeconds(1);
                        log.infov("App {0} is not ready yet, rescheduling reconciliation after {1}s", name,
                                duration.toSeconds());
                        return UpdateControl.<Devspace> noUpdate().rescheduleAfter(duration);
                    }
                }).orElseThrow();
    }

    @Override
    public DeleteControl cleanup(Devspace devspace, Context<Devspace> context) {
        log.debug("cleanup");
        if (devspace.getStatus() == null || devspace.getStatus().getOldSelectors() == null) {
            return DeleteControl.defaultDelete();
        }
        resetServiceSelector(client, devspace);
        return DeleteControl.defaultDelete();
    }

    public static void resetServiceSelector(KubernetesClient client, Devspace devspace) {
        ServiceResource<Service> serviceResource = client.services().withName(devspace.getMetadata().getName());
        UnaryOperator<Service> edit = (s) -> {
            return new ServiceBuilder(s)
                    .editSpec()
                    .withSelector(devspace.getStatus().getOldSelectors())
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

    @Override
    public Map<String, EventSource> prepareEventSources(EventSourceContext<Devspace> context) {
        InformerEventSource<Service, Devspace> ies = new InformerEventSource<>(
                InformerConfiguration.from(Service.class, context)
                        .build(),
                context);

        return Map.of("ServiceEventSource", ies);
    }
}
