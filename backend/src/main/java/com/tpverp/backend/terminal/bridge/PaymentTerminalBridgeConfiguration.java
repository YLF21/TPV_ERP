package com.tpverp.backend.terminal.bridge;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration class PaymentTerminalBridgeConfiguration {
 @Bean PaymentTerminalBridgeClient paymentTerminalBridgeClient(@Value("${tpv.payment.bridge.url:}")String url,@Value("${tpv.payment.bridge.token:}")String token){
   if(url.isBlank()||token.isBlank())return new UnavailablePaymentTerminalBridgeClient();
   var timeout=Duration.ofSeconds(5);return new HttpPaymentTerminalBridgeClient(LocalBridgeEndpoint.http(URI.create(url),token),timeout,HttpClient.newBuilder().connectTimeout(timeout).build(),JsonMapper.builder().findAndAddModules().build());
 }
}
