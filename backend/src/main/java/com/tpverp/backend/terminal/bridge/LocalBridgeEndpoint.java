package com.tpverp.backend.terminal.bridge;

import java.net.URI;
import java.net.InetAddress;

public final class LocalBridgeEndpoint {
    private final URI uri;
    private final char[] authenticationToken;
    private LocalBridgeEndpoint(URI uri,String authenticationToken){this.uri=uri;this.authenticationToken=authenticationToken.toCharArray();}
    public static LocalBridgeEndpoint http(URI uri,String token){
        if(uri==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
            throw new IllegalArgumentException("Bridge endpoint contains forbidden URI components");
        if(uri.getPath()!=null&&!uri.getPath().isBlank()&&!"/".equals(uri.getPath()))throw new IllegalArgumentException("Bridge endpoint path is not allowed");
        var host=uri==null?null:uri.getHost();
        if(host!=null&&host.startsWith("[")&&host.endsWith("]"))host=host.substring(1,host.length()-1);
        if(host==null||!(host.equalsIgnoreCase("localhost")||host.equals("127.0.0.1")||host.equals("::1")))
            throw new IllegalArgumentException("Bridge transport must be local");
        if(!"http".equalsIgnoreCase(uri.getScheme()))throw new IllegalArgumentException("Only local HTTP transport is allowed");
        if(token==null||token.isBlank())throw new IllegalArgumentException("Bridge authentication is required");
        try {
            var addresses=InetAddress.getAllByName(host);
            if(addresses.length==0||java.util.Arrays.stream(addresses).anyMatch(address->!address.isLoopbackAddress()))throw new IllegalArgumentException("Bridge DNS must resolve only to loopback");
            var address=addresses[0].getHostAddress(); if(address.contains(":"))address="["+address+"]";
            return new LocalBridgeEndpoint(URI.create("http://"+address+(uri.getPort()<0?"":":"+uri.getPort())),token);
        } catch(java.net.UnknownHostException ex){throw new IllegalArgumentException("Bridge host cannot be resolved",ex);}
    }
    public URI uri(){return uri;}
    char[] authenticationToken(){return authenticationToken.clone();}
    @Override public String toString(){return "LocalBridgeEndpoint[uri="+uri+", authenticationToken=[REDACTED]]";}
}
