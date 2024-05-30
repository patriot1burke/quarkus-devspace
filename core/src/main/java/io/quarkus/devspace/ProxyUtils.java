package io.quarkus.devspace;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;

public class ProxyUtils {
    public static <T> T await(long timeout, Future<T> future) {
        return await(timeout, future, "");
    }

    public static <T> T await(long timeout, Future<T> future, String error) {
        CountDownLatch latch = new CountDownLatch(1);
        future.onComplete(event -> latch.countDown());
        try {
            latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(error);
        }
        if (future.failed()) {
            throw new RuntimeException(error, future.cause());
        }
        return future.result();
    }
}
