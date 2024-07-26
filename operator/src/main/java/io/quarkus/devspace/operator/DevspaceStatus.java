package io.quarkus.devspace.operator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DevspaceStatus {
    public static class CleanupResource {
        private String type;
        private String name;

        public CleanupResource(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public CleanupResource() {
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private List<CleanupResource> cleanup = new ArrayList<>();
    private Map<String, String> oldSelectors = new HashMap<>();
    private String oldExternalTrafficPolicy;
    private boolean created;
    private String error;

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

    public List<CleanupResource> getCleanup() {
        return cleanup;
    }

    public void setCleanup(List<CleanupResource> cleanup) {
        this.cleanup = cleanup;
    }

    public boolean isCreated() {
        return created;
    }

    public void setCreated(boolean created) {
        this.created = created;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}