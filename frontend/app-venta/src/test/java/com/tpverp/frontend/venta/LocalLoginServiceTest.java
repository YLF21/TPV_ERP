package com.tpverp.frontend.venta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalLoginServiceTest {

    @Test
    void salesUserCanEnterAppVenta() {
        LocalLoginService service = new LocalLoginService();

        var result = service.login("venta", "venta");

        assertTrue(result.authenticated());
        assertTrue(result.canEnterAppVenta());
    }

    @Test
    void productManagerAloneCannotEnterAppVenta() {
        LocalLoginService service = new LocalLoginService();

        var result = service.login("producto", "producto");

        assertTrue(result.authenticated());
        assertFalse(result.canEnterAppVenta());
    }

    @Test
    void wrongPasswordDoesNotAuthenticate() {
        LocalLoginService service = new LocalLoginService();

        var result = service.login("venta", "bad");

        assertFalse(result.authenticated());
        assertFalse(result.canEnterAppVenta());
    }
}
