package com.tpverp.backend.terminal.bridge;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;

public final class HttpPaymentTerminalBridgeClient implements PaymentTerminalBridgeClient {
    private final LocalBridgeEndpoint endpoint; private final Duration timeout; private final HttpClient client; private final ObjectMapper json;
    public HttpPaymentTerminalBridgeClient(LocalBridgeEndpoint endpoint,Duration timeout,HttpClient client,ObjectMapper json){
        this.endpoint=endpoint;this.timeout=timeout;this.client=client;this.json=json;
        if(timeout==null||timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("timeout");
    }
    private static final Set<String> CAPABILITIES=Set.of("HEALTH","PAIR","CHARGE","QUERY","VOID","REFUND","RECEIPT","RECONCILIATION");
    public BridgeHealth health(){try{var node=get("/health");var valid=node.path("available").isBoolean()&&node.path("code").isTextual()&&node.path("version").isTextual();var available=valid&&node.path("available").booleanValue()&&"OK".equals(node.path("code").textValue());return new BridgeHealth(available,valid?(available?"OK":"UNAVAILABLE"):"INVALID_RESPONSE",valid?node.path("version").textValue():null);}catch(Exception ex){return new BridgeHealth(false,code(ex),null);}}
    public Set<String> capabilities(){try{var node=get("/capabilities").path("capabilities");if(!node.isArray())return Set.of();var values=new java.util.HashSet<String>();for(var value:node){if(!value.isTextual()||!CAPABILITIES.contains(value.textValue()))return Set.of();values.add(value.textValue());}return Set.copyOf(values);}catch(Exception ex){return Set.of();}}
    public BridgeOperationResult pair(String pairingCode){return post("/pair",java.util.Map.of("pairingCode",pairingCode));}
    public BridgeOperationResult operate(BridgeOperationRequest request){return post("/operation",request);}
    private com.fasterxml.jackson.databind.JsonNode get(String path)throws Exception{return send(HttpRequest.newBuilder(endpoint.uri().resolve(path)).GET());}
    private BridgeOperationResult post(String path,Object body){try{var node=send(HttpRequest.newBuilder(endpoint.uri().resolve(path)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));if(!node.path("approved").isBoolean()||!node.path("code").isTextual())return invalid();var approved=node.path("approved").booleanValue();var code=node.path("code").textValue();var reference=node.path("reference");var successCode="/pair".equals(path)?"PAIRED":"APPROVED";if(approved&&(!successCode.equals(code)||!reference.isTextual()||reference.textValue().isBlank()))return invalid();if(!approved&&successCode.equals(code))return invalid();if(!Set.of("APPROVED","DECLINED","CANCELLED","TIMEOUT","ERROR","SDK_NOT_INSTALLED","PAIRED").contains(code))return invalid();return new BridgeOperationResult(approved,code,reference.isTextual()?reference.textValue():null);}catch(Exception ex){return new BridgeOperationResult(false,code(ex),null);}}
    private static BridgeOperationResult invalid(){return new BridgeOperationResult(false,"INVALID_RESPONSE",null);}
    private com.fasterxml.jackson.databind.JsonNode send(HttpRequest.Builder builder)throws Exception{
        var token=endpoint.authenticationToken();try{var response=client.send(builder.timeout(timeout).header("Authorization","Bearer "+new String(token)).header("Content-Type","application/json").build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>299)throw new IllegalStateException("HTTP_"+response.statusCode());return json.readTree(response.body());}finally{java.util.Arrays.fill(token,'\0');}
    }
    private static String code(Exception ex){return ex instanceof java.net.http.HttpTimeoutException?"BRIDGE_TIMEOUT":"BRIDGE_UNAVAILABLE";}
}
