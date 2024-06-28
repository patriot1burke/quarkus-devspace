package io.quarkus.devspace.operator;

import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ResourceDiscriminator;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;

@KubernetesDependent(resourceDiscriminator = DevspaceServiceDependent.ClientApiDiscriminator.class)
public class DevspaceServiceDependent extends CRUDKubernetesDependentResource<Service, Devspace> {
    public static class ClientApiDiscriminator implements ResourceDiscriminator<Service, Devspace> {
        @Override
        public Optional<Service> distinguish(Class<Service> resource, Devspace primary, Context<Devspace> context) {
            InformerEventSource<Service, Devspace> ies = (InformerEventSource<Service, Devspace>) context
                    .eventSourceRetriever().getResourceEventSourceFor(Service.class);

            return ies.get(new ResourceID(devspaceServiceName(primary),
                    primary.getMetadata().getNamespace()));
        }

    }

    protected static final Logger log = Logger.getLogger(DevspaceServiceDependent.class);

    private static String devspaceServiceName(Devspace primary) {
        return primary.getMetadata().getName() + "-devspace";
    }

    public DevspaceServiceDependent() {
        super(Service.class);
    }

    @Override
    protected Service desired(Devspace primary, Context<Devspace> context) {
        log.info("enter desired");
        String name = devspaceServiceName(primary);
        return new ServiceBuilder()
                .withMetadata(DevspaceReconciler.createMetadata(primary, name))
                .withNewSpec()
                .addNewPort()
                .withName("http")
                .withPort(80)
                .withProtocol("TCP")
                .withTargetPort(new IntOrString(8081))
                .endPort()
                .withSelector(Map.of("run", DevspaceDeploymentDependent.devspaceDeployment(primary)))
                .withType("NodePort")
                .withExternalTrafficPolicy("Local")
                .endSpec().build();
    }
}
