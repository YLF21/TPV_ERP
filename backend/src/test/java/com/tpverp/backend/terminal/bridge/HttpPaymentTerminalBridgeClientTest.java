package com.tpverp.backend.terminal.bridge;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpPaymentTerminalBridgeClientTest {
 @Test void sendsAuthenticationAndNormalizesApprovedResponse() throws Exception {
  var auth=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
  server.createContext("/operation",exchange->{auth.set(exchange.getRequestHeaders().getFirst("Authorization"));var body="{\"approved\":true,\"code\":\"APPROVED\",\"reference\":\"R1\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
  try{var endpoint=LocalBridgeEndpoint.http(URI.create("http://localhost:"+server.getAddress().getPort()),"auth-secret");var client=new HttpPaymentTerminalBridgeClient(endpoint,Duration.ofSeconds(2),HttpClient.newHttpClient(),new ObjectMapper());
   var result=client.operate(new BridgeOperationRequest("op","CHARGE",100,"EUR"));assertThat(result.approved()).isTrue();assertThat(auth.get()).isEqualTo("Bearer auth-secret");
  }finally{server.stop(0);}
 }
 @Test void timeoutAndMalformedResponseNeverApprove() throws Exception {
  var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.createContext("/operation",exchange->{try{Thread.sleep(200);}catch(InterruptedException ignored){}exchange.sendResponseHeaders(200,1);exchange.getResponseBody().write('x');exchange.close();});server.start();
  try{var client=new HttpPaymentTerminalBridgeClient(LocalBridgeEndpoint.http(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"t"),Duration.ofMillis(20),HttpClient.newHttpClient(),new ObjectMapper());var result=client.operate(new BridgeOperationRequest("op","CHARGE",100,"EUR"));assertThat(result.approved()).isFalse();assertThat(result.code()).isEqualTo("BRIDGE_TIMEOUT");}finally{server.stop(0);}
 }
 @Test void rejectsUriConfusionComponents(){assertThatThrownBy(()->LocalBridgeEndpoint.http(URI.create("http://user@localhost:1?x=1#f"),"t")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->LocalBridgeEndpoint.http(URI.create("https://localhost:1"),"t")).isInstanceOf(IllegalArgumentException.class);}
 @Test void incoherentOrIncompleteApprovalIsNeverApproved() throws Exception {for(var body:java.util.List.of("{\"approved\":true}","{\"approved\":true,\"code\":\"DECLINED\",\"reference\":\"R\"}","{\"approved\":true,\"code\":\"APPROVED\"}","{\"approved\":\"true\",\"code\":\"APPROVED\",\"reference\":\"R\"}","{\"approved\":true,\"code\":\"UNKNOWN\",\"reference\":\"R\"}")){var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.createContext("/operation",e->{var bytes=body.getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();try{var client=new HttpPaymentTerminalBridgeClient(LocalBridgeEndpoint.http(URI.create("http://localhost:"+server.getAddress().getPort()),"t"),Duration.ofSeconds(1),HttpClient.newHttpClient(),new ObjectMapper());var result=client.operate(new BridgeOperationRequest("op","CHARGE",1,"EUR"));assertThat(result.approved()).isFalse();assertThat(result.code()).isEqualTo("INVALID_RESPONSE");}finally{server.stop(0);}}}
 @Test void rejectsAValidCodeWhenItIsIncoherentWithTheRequestedCommand() throws Exception {
  var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
  server.createContext("/operation",exchange->{var body="{\"approved\":true,\"code\":\"REFUNDED\",\"reference\":\"R1\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
  try{var client=new HttpPaymentTerminalBridgeClient(LocalBridgeEndpoint.http(URI.create("http://localhost:"+server.getAddress().getPort()),"t"),Duration.ofSeconds(1),HttpClient.newHttpClient(),new ObjectMapper());
   var result=client.operate(new BridgeOperationRequest("op","CHARGE",100,"EUR"));assertThat(result.approved()).isFalse();assertThat(result.code()).isEqualTo("INVALID_RESPONSE");
  }finally{server.stop(0);}
 }
 @Test void requestsProviderCapabilitiesAndReadsTheExtendedSdkResponse() throws Exception {
  var query=new AtomicReference<String>();var requestBody=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
  server.createContext("/capabilities",exchange->{query.set(exchange.getRequestURI().getQuery());var body="{\"capabilities\":[\"HEALTH\",\"CHARGE\",\"QUERY\"]}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
  server.createContext("/operation",exchange->{requestBody.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));var body="{\"approved\":true,\"code\":\"APPROVED\",\"reference\":\"GP-1\",\"authorization\":\"AUTH-1\",\"message\":\"Aprobada\"}".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
  try{var client=new HttpPaymentTerminalBridgeClient(LocalBridgeEndpoint.http(URI.create("http://localhost:"+server.getAddress().getPort()),"t"),Duration.ofSeconds(1),HttpClient.newHttpClient(),new ObjectMapper());
   assertThat(client.capabilities("GLOBAL_PAYMENTS","LIVE")).containsExactlyInAnyOrder("HEALTH","CHARGE","QUERY");assertThat(query.get()).isEqualTo("mode=LIVE&provider=GLOBAL_PAYMENTS");
   var request=new BridgeOperationRequest("GLOBAL_PAYMENTS","terminal-1","LIVE","operation-1","stable-key","CHARGE",1234,"EUR",null,null,"config-1",2,java.util.Map.of());
   var result=client.operate(request);assertThat(result).extracting(BridgeOperationResult::code,BridgeOperationResult::reference,BridgeOperationResult::authorization,BridgeOperationResult::message).containsExactly("APPROVED","GP-1","AUTH-1","Aprobada");
   assertThat(requestBody.get()).contains("\"provider\":\"GLOBAL_PAYMENTS\"","\"idempotencyKey\":\"stable-key\"","\"amountMinor\":1234");
  }finally{server.stop(0);}
 }
}
