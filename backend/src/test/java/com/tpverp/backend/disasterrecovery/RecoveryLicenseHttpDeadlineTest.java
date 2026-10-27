package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RecoveryLicenseHttpDeadlineTest {
    @Test void totalDeadlineCancelsA200ResponseWithStalledIncompleteBody()throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        var headersSent=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/",exchange->{
            try {
                exchange.sendResponseHeaders(200,1000);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();headersSent.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }finally{exchange.close();}
        });server.start();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+server.getAddress().getPort()+"/")).build();
            long start=System.nanoTime();
            assertThatThrownBy(()->RecoveryCentralClient.boundedResponse(client,request,Duration.ofMillis(750)))
                    .isInstanceOf(java.io.IOException.class).hasMessageContaining("timed out");
            assertThat(headersSent.getCount()).isZero();
            assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
            release.countDown();
        }finally{release.countDown();server.stop(0);}
    }

    @Test void enforcesTheByteLimitAndAcceptsACompleteBoundedBody()throws Exception {
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/large",exchange->{try{exchange.sendResponseHeaders(200,65537);exchange.getResponseBody().write(new byte[65537]);}finally{exchange.close();}});
        server.createContext("/small",exchange->{try{exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}finally{exchange.close();}});
        server.start();
        try(var client=HttpClient.newHttpClient()) {
            String base="http://localhost:"+server.getAddress().getPort();
            assertThatThrownBy(()->RecoveryCentralClient.boundedResponse(client,HttpRequest.newBuilder(URI.create(base+"/large")).build(),Duration.ofSeconds(2)))
                    .hasRootCauseMessage("Recovery license response exceeds limit");
            assertThat(RecoveryCentralClient.boundedResponse(client,HttpRequest.newBuilder(URI.create(base+"/small")).build(),Duration.ofSeconds(2)).body())
                    .isEqualTo("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }finally{server.stop(0);}
    }
}
