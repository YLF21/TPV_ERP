package com.tpverp.backend.disasterrecovery;

import com.fasterxml.jackson.databind.*;
import com.tpverp.backend.servertransfer.TransferEnvelope;
import java.net.URI;
import java.net.http.*;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class RecoveryCentralClient {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final URI base;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    RecoveryCentralClient(String base) {
        this.base=URI.create(base.replaceAll("/+$",""));
        if(!"https".equalsIgnoreCase(this.base.getScheme()) || this.base.getUserInfo()!=null || this.base.getQuery()!=null || this.base.getFragment()!=null)
            throw new IllegalArgumentException("Recovery requires HTTPS SaaS");
    }
    JsonNode call(UUID id,String action,String digest,PrivateKey key) throws Exception {return call(id,action,digest,key,"",null);}
    com.tpverp.backend.licensing.RecoveredLicenseSnapshot license(UUID id,String digest,PrivateKey key,String token)throws Exception {
        if(token==null||token.isBlank())throw new IllegalStateException("Recovery credential missing");
        var body=JSON.createObjectNode().put("destinationProof",proof(id,"license",digest,key,""));
        var request=HttpRequest.newBuilder(URI.create(base+"/api/v1/backup-recoveries/"+id+"/license"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .header("X-TPV-Installation-Token",token).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        var response=boundedResponse(http,request,Duration.ofSeconds(30));
        if(response.statusCode()!=200)throw new IllegalStateException("Recovery license rejected (HTTP "+response.statusCode()+")");
        return new ObjectMapper().findAndRegisterModules().readValue(response.body(),com.tpverp.backend.licensing.RecoveredLicenseSnapshot.class);
    }
    /** The explicit total deadline includes a stalled response body, not just connection/headers. */
    static HttpResponse<byte[]> boundedResponse(HttpClient client,HttpRequest request,Duration timeout)throws Exception {
        var pending=client.sendAsync(request,ignored->new LimitedBodySubscriber());
        try{return pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException expired){pending.cancel(true);throw new IOException("Recovery license response timed out",expired);}
        catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();throw interrupted;}
    }
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                int length=buffer.remaining();
                if(length>65536-bytes.size()) {
                    subscription.cancel();result.completeExceptionally(new IOException("Recovery license response exceeds limit"));return;
                }
                byte[] chunk=new byte[length];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure){result.completeExceptionally(failure);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
    JsonNode call(UUID id,String action,String digest,PrivateKey key,String context,JsonNode report) throws Exception {
        var body=JSON.createObjectNode();body.put("destinationProof",proof(id,action,digest,key,context));if(report!=null)body.set("report",report);
        String route=action.equals("evidence")&&!context.isBlank()?action+"?after="+UUID.fromString(context):action;
        var response=http.send(request(id,route,body),HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            if(response.statusCode()!=200)throw new IllegalStateException("Recovery SaaS rejected "+action+" (HTTP "+response.statusCode()+")");
            byte[] bytes=stream.readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw new IllegalStateException("Recovery response exceeds limit");
            return JSON.readTree(bytes);
        }
    }
    java.io.InputStream download(UUID id,String action,String digest,PrivateKey key) throws Exception {
        var body=JSON.createObjectNode().put("destinationProof",proof(id,action,digest,key,""));
        var response=http.send(request(id,action,body),HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()!=200){response.body().close();throw new IllegalStateException("Recovery download denied (HTTP "+response.statusCode()+")");}
        return response.body();
    }
    private HttpRequest request(UUID id,String route,JsonNode body) {
        return HttpRequest.newBuilder(URI.create(base+"/api/v1/backup-recoveries/"+id+"/"+route)).timeout(Duration.ofMinutes(10))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    private static String proof(UUID id,String action,String digest,PrivateKey key,String payload) throws Exception {
        return TransferEnvelope.sign(key,"TPV-RECOVERY-V1\n"+id+"\n"+action+"\n"+(action.equals("inspect")?"":digest)+"\n"+payload);
    }
}
