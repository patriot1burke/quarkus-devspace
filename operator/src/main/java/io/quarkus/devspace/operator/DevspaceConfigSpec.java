package io.quarkus.devspace.operator;

public class DevspaceConfigSpec {
    public static class ProxyDeployment {
        private String image;
        private String imagePullPolicy;
        private String clientPathPrefix;

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

        public String getClientPathPrefix() {
            return clientPathPrefix;
        }

        public void setClientPathPrefix(String clientPathPrefix) {
            this.clientPathPrefix = clientPathPrefix;
        }
    }

    private ProxyDeployment proxy;
    private String authType;
    /**
     * manual
     * ingress
     * route
     * secure-route
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
}