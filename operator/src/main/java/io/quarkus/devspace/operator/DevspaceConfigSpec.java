package io.quarkus.devspace.operator;

public class DevspaceConfigSpec {
    public static class ProxyDeployment {
        private String image;
        private String imagePullPolicy;

        public String getImage() {
            return image;
        }

        public void setImage(String image) {
            this.image = image;
        }

        public String getImagePullPolicy() {
            return imagePullPolicy;
        }

        public void setImagePullPolicy(String imagePullPolicy) {
            this.imagePullPolicy = imagePullPolicy;
        }
    }

    private ProxyDeployment proxy;

    private String authType;
    private Integer pollTimeoutSeconds;
    private Integer idleTimeoutSeconds;
    /**
     * manual
     * ingress
     * route
     * secure-route
     * nodePort;
     */
    private String exposePolicy;

    public ProxyDeployment getProxy() {
        return proxy;
    }

    public void setProxy(ProxyDeployment proxy) {
        this.proxy = proxy;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getExposePolicy() {
        return exposePolicy;
    }

    public void setExposePolicy(String exposePolicy) {
        this.exposePolicy = exposePolicy;
    }

    public Integer getPollTimeoutSeconds() {
        return pollTimeoutSeconds;
    }

    public Integer getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(Integer idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public void setPollTimeoutSeconds(Integer pollTimeoutSeconds) {
        this.pollTimeoutSeconds = pollTimeoutSeconds;
    }

    public AuthenticationType toAuthenticationType() {
        if (authType == null)
            return AuthenticationType.secret;
        return AuthenticationType.valueOf(authType);
    }

    public ExposePolicy toExposePolicy() {
        if (exposePolicy == null)
            return ExposePolicy.defaultPolicy;
        return ExposePolicy.valueOf(exposePolicy);
    }

    /**
     * pull spec from config and set up default values if value not set
     *
     * @param config
     * @return
     */
    public static DevspaceConfigSpec toDefaultedSpec(DevspaceConfig config) {
        DevspaceConfigSpec spec = new DevspaceConfigSpec();
        spec.pollTimeoutSeconds = 5;
        spec.authType = AuthenticationType.secret.name();
        spec.exposePolicy = ExposePolicy.defaultPolicy.name();
        spec.setProxy(new ProxyDeployment());
        spec.getProxy().setImage("io.quarkus/quarkus-devspace-proxy:latest");
        spec.getProxy().setImagePullPolicy("Always");
        spec.setIdleTimeoutSeconds(60);
        spec.setPollTimeoutSeconds(5);

        if (config == null || config.getSpec() == null)
            return spec;
        DevspaceConfigSpec oldSpec = config.getSpec();
        if (oldSpec.getPollTimeoutSeconds() != null)
            spec.pollTimeoutSeconds = oldSpec.getPollTimeoutSeconds();
        if (oldSpec.getIdleTimeoutSeconds() != null)
            spec.idleTimeoutSeconds = oldSpec.getIdleTimeoutSeconds();
        if (oldSpec.getAuthType() != null)
            spec.authType = oldSpec.getAuthType();
        if (oldSpec.getExposePolicy() != null)
            spec.exposePolicy = oldSpec.getExposePolicy();
        if (oldSpec.getProxy() != null) {
            if (oldSpec.getProxy().getImage() != null)
                spec.getProxy().setImage(oldSpec.getProxy().getImage());
            if (oldSpec.getProxy().getImagePullPolicy() != null)
                spec.getProxy().setImagePullPolicy(oldSpec.getProxy().getImagePullPolicy());
        }
        return spec;
    }
}