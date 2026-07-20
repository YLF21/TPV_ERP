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
    private static final int MAX_RESPONSE_LENGTH=65_536;
    private static final Set<String> CAPABILITIES=Set.of("HEALTH","PAIR","CHARGE","QUERY","VOID","REFUND","RECEIPT","RECONCILIATION");
    private static final Set<String> CODES=Set.of("APPROVED","DECLINED","CANCELLED","VOIDED","REFUNDED","PARTIALLY_REFUNDED",
            "PENDING","TIMEOUT","ERROR","REVIEW_REQUIRED","SDK_NOT_INSTALLED","PAIRED","PAIRING_NOT_FOUND",
            "OPERATION_NOT_FOUND","RECEIPT_AVAILABLE","RECONCILED","BRIDGE_TIMEOUT","BRIDGE_UNAVAILABLE",
            "BRIDGE_HTTP_ERROR","INVALID_RESPONSE");
    private static final Set<String> SUCCESS_CODES=Set.of("APPROVED","VOIDED","REFUNDED","PARTIALLY_REFUNDED",
            "PAIRED","RECEIPT_AVAILABLE","RECONCILED");
    private static final Set<String> COMMON_FAILURE_CODES=Set.of("DECLINED","CANCELLED","PENDING","TIMEOUT","ERROR",
            "REVIEW_REQUIRED","SDK_NOT_INSTALLED","BRIDGE_TIMEOUT","BRIDGE_UNAVAILABLE","BRIDGE_HTTP_ERROR","INVALID_RESPONSE");
    public BridgeHealth health(){try{var node=get("/health");var valid=node.path("available").isBoolean()&&node.path("code").isTextual()&&node.path("version").isTextual();var available=valid&&node.path("available").booleanValue()&&"OK".equals(node.path("code").textValue());return new BridgeHealth(available,valid?(available?"OK":"UNAVAILABLE"):"INVALID_RESPONSE",valid?node.path("version").textValue():null);}catch(Exception ex){return new BridgeHealth(false,code(ex),null);}}
    public Set<String> capabilities(String provider,String mode){try{if(!Set.of("SIMULATED","LIVE").contains(mode))return Set.of();var query=new StringBuilder("?mode=").append(mode);if(provider!=null)query.append("&provider=").append(java.net.URLEncoder.encode(provider,java.nio.charset.StandardCharsets.UTF_8));var node=get("/capabilities"+query).path("capabilities");if(!node.isArray())return Set.of();var values=new java.util.HashSet<String>();for(var value:node){if(!value.isTextual()||!CAPABILITIES.contains(value.textValue()))return Set.of();values.add(value.textValue());}return Set.copyOf(values);}catch(Exception ex){return Set.of();}}
    public BridgeOperationResult pair(BridgePairingRequest request){return post("/pair",request,"PAIR");}
    public BridgeOperationResult operate(BridgeOperationRequest request){return post("/operation",request,request.command());}
    private com.fasterxml.jackson.databind.JsonNode get(String path)throws Exception{return send(HttpRequest.newBuilder(endpoint.uri().resolve(path)).GET());}
    private BridgeOperationResult post(String path,Object body,String command){try{var node=send(HttpRequest.newBuilder(endpoint.uri().resolve(path)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));if(!node.path("approved").isBoolean()||!node.path("code").isTextual())return invalid();var approved=node.path("approved").booleanValue();var normalizedCode=node.path("code").textValue();if(!CODES.contains(normalizedCode)||!allowedFor(command,normalizedCode)||approved!=SUCCESS_CODES.contains(normalizedCode))return invalid();var reference=optional(node,"reference",256,false);var authorization=optional(node,"authorization",64,false);var message=optional(node,"message",512,false);var receipt=optional(node,"receiptText",4_000,true);if((approved&&!"RECEIPT_AVAILABLE".equals(normalizedCode)&&(reference==null||reference.isBlank()))
                ||("RECEIPT".equals(command)&&"RECEIPT_AVAILABLE".equals(normalizedCode)&&(receipt==null||receipt.isBlank())))return invalid();return new BridgeOperationResult(approved,normalizedCode,reference,authorization,message,receipt);}catch(Exception ex){return new BridgeOperationResult(false,code(ex),null,null,null,null);}}
    private static boolean allowedFor(String command,String code){
        if(COMMON_FAILURE_CODES.contains(code))return true;
        return switch(command){
            case "PAIR" -> Set.of("PAIRED","PAIRING_NOT_FOUND").contains(code);
            case "PAIRING_STATUS" -> Set.of("PAIRED","PAIRING_NOT_FOUND").contains(code);
            case "CHARGE" -> "APPROVED".equals(code);
            case "QUERY" -> Set.of("APPROVED","VOIDED","REFUNDED","PARTIALLY_REFUNDED","OPERATION_NOT_FOUND").contains(code);
            case "VOID" -> Set.of("VOIDED","OPERATION_NOT_FOUND").contains(code);
            case "REFUND" -> Set.of("REFUNDED","PARTIALLY_REFUNDED","OPERATION_NOT_FOUND").contains(code);
            case "RECEIPT" -> Set.of("RECEIPT_AVAILABLE","OPERATION_NOT_FOUND").contains(code);
            case "RECONCILIATION" -> "RECONCILED".equals(code);
            default -> false;
        };
    }
    private static String optional(com.fasterxml.jackson.databind.JsonNode node,String name,int maximum,boolean multiline){var value=node.get(name);if(value==null||value.isNull())return null;if(!value.isTextual()||value.textValue().length()>maximum||value.textValue().chars().anyMatch(character->Character.isISOControl(character)&&(!multiline||character!='\n'&&character!='\r'&&character!='\t')))throw new IllegalArgumentException("Invalid bridge response");return value.textValue();}
    private static BridgeOperationResult invalid(){return new BridgeOperationResult(false,"INVALID_RESPONSE",null,null,null,null);}
    private com.fasterxml.jackson.databind.JsonNode send(HttpRequest.Builder builder)throws Exception{
        var token=endpoint.authenticationToken();try{var response=client.send(builder.timeout(timeout).header("Authorization","Bearer "+new String(token)).header("Content-Type","application/json").build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()<200||response.statusCode()>299)throw new BridgeHttpException();if(response.body().length()>MAX_RESPONSE_LENGTH)throw new IllegalArgumentException("Bridge response too large");return json.readTree(response.body());}catch(InterruptedException ex){Thread.currentThread().interrupt();throw ex;}finally{java.util.Arrays.fill(token,'\0');}
    }
    private static String code(Exception ex){if(ex instanceof java.net.http.HttpTimeoutException)return "BRIDGE_TIMEOUT";if(ex instanceof BridgeHttpException)return "BRIDGE_HTTP_ERROR";if(ex instanceof com.fasterxml.jackson.core.JsonProcessingException||ex instanceof IllegalArgumentException)return "INVALID_RESPONSE";return "BRIDGE_UNAVAILABLE";}
    private static final class BridgeHttpException extends RuntimeException {}
}
