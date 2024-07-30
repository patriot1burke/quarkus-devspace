package io.quarkiverse.playpen.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class DevspaceConnectionConfig {
    public String who;
    public String host;
    public int port;
    public boolean ssl;
    public List<String> headers;
    public List<String> paths;
    public List<String> queries;
    public String session;
    public String error;
    public boolean useClientIp;
    public String clientIp;
    public String credentials;

    public static DevspaceConnectionConfig fromUri(String uriString) {
        DevspaceConnectionConfig devspace = new DevspaceConnectionConfig();
        URI uri = null;
        try {
            uri = new URI(uriString);
        } catch (Exception e) {
            devspace.error = "devspace URI value is bad";
            return devspace;
        }
        devspace.host = uri.getHost();
        devspace.ssl = "https".equalsIgnoreCase(uri.getScheme());
        devspace.port = uri.getPort() == -1 ? (devspace.ssl ? 443 : 80) : uri.getPort();

        boolean needSession = false;
        if (uri.getQuery() != null) {
            for (String pair : uri.getQuery().split("&")) {
                int idx = pair.indexOf("=");
                String key = pair.substring(0, idx);
                String value = idx == -1 ? null : pair.substring(idx + 1);
                if ("session".equals(key)) {
                    devspace.session = value;
                } else if ("who".equals(key)) {
                    devspace.who = value;
                } else if ("query".equals(key)) {
                    if (devspace.queries == null)
                        devspace.queries = new ArrayList<>();
                    devspace.queries.add(value);
                    needSession = true;
                } else if ("path".equals(key)) {
                    if (devspace.paths == null)
                        devspace.paths = new ArrayList<>();
                    devspace.paths.add(value);
                    needSession = true;
                } else if ("header".equals(key)) {
                    if (devspace.headers == null)
                        devspace.headers = new ArrayList<>();
                    devspace.headers.add(value);
                    needSession = true;
                } else if ("clientIp".equals(key)) {
                    devspace.useClientIp = true;
                    devspace.clientIp = value;
                }
            }
        }
        if (devspace.who == null) {
            devspace.error = "devspace uri is missing who parameter";
        }
        if (needSession && devspace.session == null) {
            devspace.error = "devspace uri is missing session parameter";
        }
        return devspace;

    }
}
