package com.tpverp.bridge.spi;

public record AdapterHealth(boolean available, String code, String version) {
    public static AdapterHealth unavailable(String code) {
        return new AdapterHealth(false, code, null);
    }
}
