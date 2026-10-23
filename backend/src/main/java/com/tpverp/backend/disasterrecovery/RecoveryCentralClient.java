package com.tpverp.backend.disasterrecovery;

import com.fasterxml.jackson.databind.*;
import com.tpverp.backend.servertransfer.TransferEnvelope;
import java.net.URI;
import java.net.http.*;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.*;

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
