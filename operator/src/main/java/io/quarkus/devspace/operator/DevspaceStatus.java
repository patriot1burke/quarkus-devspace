package io.quarkus.devspace.operator;

import java.util.HashMap;
import java.util.Map;

public class DevspaceStatus {
    private Map<String, String> oldSelectors = new HashMap<>();
    private String oldExternalTrafficPolicy;

    public Map<String, String> getOldSelectors() {
        return oldSelectors;
    }

    public void setOldSelectors(Map<String, String> oldSelectors) {
        this.oldSelectors = oldSelectors;
    }

    public String getOldExternalTrafficPolicy() {
        return oldExternalTrafficPolicy;
    }

    public void setOldExternalTrafficPolicy(String oldExternalTrafficPolicy) {
        this.oldExternalTrafficPolicy = oldExternalTrafficPolicy;
    }
}