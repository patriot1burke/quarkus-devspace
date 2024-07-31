package io.quarkiverse.playpen.operator;

public class PlaypenConfigSpec {
    private String authType;
    private Integer pollTimeoutSeconds;
    private Integer idleTimeoutSeconds;
    private String logLevel;
    /**
     * manual
     * ingress
     * route
     * secure-route
     * nodePort;
     */
    private String exposePolicy;

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

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

}