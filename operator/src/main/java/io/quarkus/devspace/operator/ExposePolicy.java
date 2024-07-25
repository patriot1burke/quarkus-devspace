package io.quarkus.devspace.operator;

public enum ExposePolicy {
    defaultPolicy,
    manual,
    route,
    secureRoute,
    nodePort
}
