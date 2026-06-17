package com.tpverp.backend.verifactu;

import org.springframework.stereotype.Component;

@Component
public class VerifactuSignaturePolicy {

    public boolean requiredForVerifactu() {
        return false;
    }
    // Explicita que AEAT no exige firma electronica en modalidad VERI*FACTU.

    public String mode() {
        return "NOT_REQUIRED_FOR_VERIFACTU";
    }
    // Identificador estable para mostrar y probar la politica aplicada.
}
