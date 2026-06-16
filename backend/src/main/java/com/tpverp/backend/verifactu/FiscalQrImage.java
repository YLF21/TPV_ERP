package com.tpverp.backend.verifactu;

public record FiscalQrImage(
        byte[] bytes,
        String contentType) {

    public FiscalQrImage {
        bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
