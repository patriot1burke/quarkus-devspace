package io.quarkus.devspace.operator;

import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ResourceDiscriminator;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;

@KubernetesDependent(resourceDiscriminator = OriginServiceDependent.OriginDescriminator.class)
public class OriginServiceDependent extends CRUDKubernetesDependentResource<Service, Devspace> {
    public static class OriginDescriminator implements ResourceDiscriminator<Service, Devspace> {
        @Override
        public Optional<Service> distinguish(Class<Service> resource, Devspace primary, Context<Devspace> context) {
            InformerEventSource<Service, Devspace> ies = (InformerEventSource<Service, Devspace>) context
                    .eventSourceRetriever().getResourceEventSourceFor(Service.class);

            return ies.get(new ResourceID(origin(primary),
                    primary.getMetadata().getNamespace()));
        }

    }

    protected static final Logger log = Logger.getLogger(OriginServiceDependent.class);

    public static String origin(Devspace primary) {
        return primary.getMetadata().getName() + "-origin";
    }

    public OriginServiceDependent() {
        super(Service.class);
    }

    @Inject
    KubernetesClient client;

    @Override
    protected Service desired(Devspace primary, Context<Devspace> context) {
        log.info("enter desired");
        String serviceName = primary.getMetadata().getName();
        String name = origin(primary);
        Map<String, String> selector = null;
        if (primary.getStatus() == null || primary.getStatus().getOldSelectors() == null) {
            selector = client.services().withName(serviceName).get().getSpec().getSelector();
        } else {
            selector = primary.getStatus().getOldSelectors();
        }
        return new ServiceBuilder()
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
    }
}
