package io.quarkiverse.playpen.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;

@Version("v1")
@Group("io.quarkus.devspace")
public class Devspace extends CustomResource<DevspaceSpec, DevspaceStatus> implements Namespaced {
}