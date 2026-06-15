package com.tpverp.backend.verifactu;

public class VerifactuInactiveException extends IllegalStateException {

    public VerifactuInactiveException() {
        super("VERI*FACTU no esta activo");
    }
}
