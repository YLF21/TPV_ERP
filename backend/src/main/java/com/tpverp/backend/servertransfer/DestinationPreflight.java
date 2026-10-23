package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Signed destination readiness is bound to its persisted key and expires after one day. */
final class DestinationPreflight {
    private static final ObjectMapper JSON=new ObjectMapper();
    static String issue(KeyPair key,String release,String schema,long freeBytes)throws Exception {
        return issue(key.getPrivate(),key.getPublic(),release,schema,freeBytes);
    }
    static String issue(PrivateKey privateKey,PublicKey publicKey,String release,String schema,long freeBytes)throws Exception {
        return issue(privateKey,publicKey,release,schema,freeBytes,"localhost",false);
    }
    static String issue(PrivateKey privateKey,PublicKey publicKey,String release,String schema,long freeBytes,String host,boolean tlsReady)throws Exception {
        var payload=new LinkedHashMap<String,Object>();payload.put("releaseId",release);payload.put("schemaVersion",schema);payload.put("freeBytes",freeBytes);payload.put("issuedAt",Instant.now().toString());payload.put("publicHost",host);payload.put("tlsReady",tlsReady);
        String message=message(TransferEnvelope.fingerprint(publicKey),release,schema,freeBytes,(String)payload.get("issuedAt"),host,tlsReady);
        payload.put("signature",TransferEnvelope.sign(privateKey,message));return Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(payload));
    }
    static Readiness requireCompatible(String key,String proof)throws Exception {
        var node=JSON.readTree(Base64.getDecoder().decode(proof));
        var publicKey=TransferEnvelope.publicKey(key);String release=node.path("releaseId").asText(),schema=node.path("schemaVersion").asText(),issued=node.path("issuedAt").asText();long bytes=node.path("freeBytes").asLong();
        String host=node.path("publicHost").asText();boolean tlsReady=node.path("tlsReady").asBoolean();
        if(host.isBlank() || host.length()>253 || host.contains("\n") || !node.path("tlsReady").isBoolean())throw new IllegalArgumentException("Destination host required");
        var signer=Signature.getInstance("SHA256withRSA");signer.initVerify(publicKey);signer.update(message(TransferEnvelope.fingerprint(publicKey),release,schema,bytes,issued,host,tlsReady).getBytes(StandardCharsets.UTF_8));
        if(!signer.verify(Base64.getDecoder().decode(node.path("signature").asText())))throw new GeneralSecurityException("Invalid destination proof");
        var at=Instant.parse(issued);if(at.isAfter(Instant.now().plusSeconds(60)) || at.isBefore(Instant.now().minusSeconds(86400)) || bytes<2L*1024*1024*1024)throw new IllegalStateException("Destination preflight expired or insufficient space");
        var properties=new Properties();try(var input=DestinationPreflight.class.getResourceAsStream("/META-INF/tpv-erp-release.properties")){properties.load(Objects.requireNonNull(input));}
        if(!release.equals(properties.getProperty("release.id")) || !schema.equals(properties.getProperty("schema.version")))throw new IllegalStateException("Destination release/schema must exactly match source");
        return new Readiness(host,tlsReady);
    }
    private static String message(String fingerprint,String release,String schema,long bytes,String issued,String host,boolean tlsReady){return "TPV-DESTINATION-V2\n"+fingerprint+"\n"+release+"\n"+schema+"\n"+bytes+"\n"+issued+"\n"+host+"\n"+tlsReady;}
    record Readiness(String publicHost,boolean tlsReady){}
}
