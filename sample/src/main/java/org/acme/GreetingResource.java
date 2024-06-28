package org.acme;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.time.LocalTime;

@Path("/hello")
public class GreetingResource {

    @GET
    @Produces(MediaType.TEXT_HTML)
    public String hello(@QueryParam("user") @DefaultValue("") String user) {
        return "<h1>LOCAL " + user + " " + LocalTime.now() + "</h1>";
    }
}
