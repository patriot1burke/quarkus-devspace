package org.acme;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.rest.client.reactive.runtime.RestClientBuilderImpl;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.client.impl.ClientBuilderImpl;

import java.net.URI;
import java.time.LocalTime;
import java.util.Base64;

@Path("/hello")
public class GreetingResource {

    @Path(".well-known")
    public interface WellKnown {

        @GET
        @Path("oauth-authorization-server")
        String oauth();

    }


    private static final String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }



    @GET
    @Produces(MediaType.TEXT_HTML)
    public String hello(@QueryParam("user") @DefaultValue("developer") String user) {
        /*
        ClientBuilderImpl impl = (ClientBuilderImpl)ClientBuilder.newBuilder();
        impl.trustAll(true).followRedirects(false);
        Client client = impl.build();
        Response response = client.target("https://oauth-openshift.openshift-authentication.svc.cluster.local/oauth/authorize")
                .queryParam("response_type", "token")
                .queryParam("client_id", "openshift-challenging-client")
                .request().header("Authorization", getBasicAuthenticationHeader(user, ""))
                .get();
        if (response.getStatus() == 302) {
            String uri = response.getHeaderString("Location");
            return uri;
        } else {
            return "Failed to authenticate";
        }

         */
                /*
        WellKnown wk = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create("https://openshift.default.svc"))
                .trustAll(true)
                .build(WellKnown.class);
        return wk.oauth();

                 */

        return "<h1>LOCAL " + user + " " + LocalTime.now() + "</h1>";
    }
}
