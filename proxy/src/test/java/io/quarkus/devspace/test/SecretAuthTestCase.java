package io.quarkus.devspace.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.quarkus.devspace.ProxyUtils;
import io.quarkus.devspace.client.DevProxyClient;
import io.quarkus.devspace.server.auth.ProxySessionAuth;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.impl.VertxBuilder;
import io.vertx.core.impl.VertxThread;
import io.vertx.core.spi.VertxThreadFactory;

@QuarkusTest
@TestProfile(SecretAuthTestCase.ConfigOverrides.class)
public class SecretAuthTestCase {

    public static Vertx vertx;

    static HttpServer myService;

    static HttpServer localService;

    public static class ConfigOverrides implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "service.host", "localhost",
                    "service.name", "my-service",
                    "service.port", "9091",
                    "authentication.type", ProxySessionAuth.SECRET_AUTH,
                    "secret", "geheim",
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

    }

    @AfterAll
    public static void after() {
        List<Future> futures = new ArrayList<>();
        if (myService != null)
            futures.add(myService.close());
        if (localService != null)
            futures.add(localService.close());
        if (vertx != null)
            futures.add(vertx.close());
        ProxyUtils.awaitAll(1000, futures);

    }

    @Test
    public void testBaseSecret() {
        DevProxyClient client = DevProxyClient.create(vertx)
                .devspace("http://localhost:8082?who=bill")
                .service("localhost", 9092, false)
                .secretAuth("badpassword")
                .build();
        Assertions.assertFalse(client.start());
    }

    @Test
    public void testGlobalSession() throws Exception {
        DevProxyClient client = DevProxyClient.create(vertx)
                .devspace("http://localhost:8082?who=bill")
                .service("localhost", 9092, false)
                .credentials("geheim")
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
