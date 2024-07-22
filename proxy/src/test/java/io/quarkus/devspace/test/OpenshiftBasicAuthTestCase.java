package io.quarkus.devspace.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.devspace.ProxyUtils;
import io.quarkus.devspace.client.DevProxyClient;
import io.quarkus.devspace.server.QuarkusDevProxyServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.impl.VertxBuilder;
import io.vertx.core.impl.VertxThread;
import io.vertx.core.spi.VertxThreadFactory;

@QuarkusTest
@TestProfile(OpenshiftBasicAuthTestCase.ConfigOverrides.class)
public class OpenshiftBasicAuthTestCase {

    public static Vertx vertx;

    static HttpServer myService;

    static HttpServer localService;
    static HttpServer oauthService;

    public static class ConfigOverrides implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "service.host", "localhost",
                    "service.name", "my-service",
                    "service.port", "9091",
                    "auth.type", QuarkusDevProxyServer.OPENSHIFT_BASIC_AUTH,
                    "oauth.url", "http://localhost:9093",
                    "client.api.port", "8082"
            //,"quarkus.log.level", "DEBUG"
            );
        }
    }

    @BeforeAll
    public static void before() {
        vertx = new VertxBuilder()
                .threadFactory(new VertxThreadFactory() {
                    public VertxThread newVertxThread(Runnable target, String name, boolean worker, long maxExecTime,
                            TimeUnit maxExecTimeUnit) {
                        return new VertxThread(target, "TEST-" + name, worker, maxExecTime, maxExecTimeUnit);
                    }
                }).init().vertx();

        myService = vertx.createHttpServer();
        myService.requestHandler(request -> {
            request.response().setStatusCode(200).putHeader("Content-Type", "text/plain").end("my-service");
        }).listen(9091);

        localService = vertx.createHttpServer();
        localService.requestHandler(request -> {
            request.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "text/plain")
                    .end("local");
        }).listen(9092);

        oauthService = vertx.createHttpServer();
        oauthService.requestHandler(request -> {
            String authorization = request.getHeader("Authorization");
            String base64Credentials = authorization.substring("Basic".length()).trim();
            byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
            String credentials = new String(credDecoded, StandardCharsets.UTF_8);
            // credentials = username:password
            final String[] values = credentials.split(":", 2);
            if (values[0].equals("bill") && values[1].equals("geheim")) {
                request.response()
                        .setStatusCode(302)
                        .putHeader("Location", "http://localhost:9093?access_token=12345")
                        .end();
            } else {
                request.response().setStatusCode(401).end();
            }
        }).listen(9093);

    }

    @AfterAll
    public static void after() {
        if (myService != null)
            ProxyUtils.await(1000, myService.close());
        if (localService != null)
            ProxyUtils.await(1000, localService.close());
        if (oauthService != null)
            ProxyUtils.await(1000, oauthService.close());
        if (vertx != null)
            ProxyUtils.await(1000, vertx.close());

    }

    @Test
    public void testBadPassword() {
        DevProxyClient client = DevProxyClient.create(vertx)
                .devspace("http://localhost:8082?who=bill")
                .service("localhost", 9092, false)
                .basicAuth("bill", "badpassword")
                .build();
        Assertions.assertFalse(client.start());
    }

    @Test
    public void testBadUser() {
        DevProxyClient client = DevProxyClient.create(vertx)
                .devspace("http://localhost:8082?who=bill")
                .service("localhost", 9092, false)
                .basicAuth("john", "geheim")
                .build();
        Assertions.assertFalse(client.start());
    }

    @Test
    public void testGlobalSession() throws Exception {
        DevProxyClient client = DevProxyClient.create(vertx)
                .devspace("http://localhost:8082?who=bill")
                .service("localhost", 9092, false)
                .basicAuth("bill", "geheim")
                .build();
        Assertions.assertTrue(client.start());
        try {
            System.out.println("------------------ POST REQUEST BODY ---------------------");
            given()
                    .when()
                    .contentType("text/plain")
                    .body("hello")
                    .post("/hey")
                    .then()
                    .statusCode(200)
                    .contentType(equalTo("text/plain"))
                    .body(equalTo("local"));
            System.out.println("-------------------- GET REQUEST --------------------");
            given()
                    .when()
                    .get("/yo")
                    .then()
                    .statusCode(200)
                    .contentType(equalTo("text/plain"))
                    .body(equalTo("local"));
            System.out.println("------------------ POST REQUEST NO BODY ---------------------");
            given()
                    .when()
                    .post("/hey")
                    .then()
                    .statusCode(200)
                    .contentType(equalTo("text/plain"))
                    .body(equalTo("local"));
        } finally {
            client.shutdown();
        }
        System.out.println("-------------------- After Shutdown GET REQUEST --------------------");
        given()
                .when()
                .get("/yo")
                .then()
                .statusCode(200)
                .contentType(equalTo("text/plain"))
                .body(equalTo("my-service"));
    }
}
