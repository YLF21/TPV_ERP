package com.tpverp.backend.inventory;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ReplenishmentConflictException extends RuntimeException {
    public ReplenishmentConflictException() {
        super("La solicitud ha cambiado o ya no existe. Actualice el listado.");
    }
}
