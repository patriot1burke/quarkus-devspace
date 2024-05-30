package io.quarkiverse.devspace;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public class DevspaceConfig {
    /**
     * Connection string for quarkus devspace.
     *
     * Uri. Add query parameters to uri for additional config parameters
     *
     * i.e.
     * http://host:port?who=whoami[&optional-config=value]*
     *
     * "who" is who you are. This is required.
     *
     * By default, all request will be pushed locally from the devspace proxy.
     * If you want to have a specific directed session, then use these parameters to define
     * the session within the devspace config uri:
     *
     * header - http header or cookie name that identifies the session id
     * query - query parameter name that identifies session id
     * path - path parameter name that identifies session id use "{}" to specify where sessionid is in path i.e.
     * /foo/bar/{}/blah
     * session - session id value
     */
    @ConfigItem
    public Optional<String> uri;

    /**
     * If true, quarkus will not connect to devspace on boot. Connection would have
     * to be done manually from the recorder method.
     *
     * This is for internal testing purposes only.
     */
    @ConfigItem(defaultValue = "false")
    public boolean manualStart;
}
