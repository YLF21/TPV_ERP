package com.tpverp.frontend.venta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AppVentaMessagesTest {

    private static final List<String> REQUIRED_KEYS = List.of(
            "dialog.charge.title",
            "dialog.charge.message",
            "total.zero",
            "dialog.parked.title",
            "dialog.parked.empty",
            "document.window.title",
            "document.placeholder",
            "status.ticketDeleted",
            "status.userClosed",
            "status.unknownShortcut",
            "status.productNotFound",
            "status.productAdded",
            "status.saleParked",
            "status.operationRejected",
            "shortcut.enter",
            "shortcut.pause",
            "shortcut.lineDiscount",
            "shortcut.ticketDiscount",
            "shortcut.packages",
            "shortcut.charge",
            "shortcut.document",
            "shortcut.parked"
    );

    @Test
    void allVisibleMessagesExistInEveryLocale() {
        for (Locale locale : List.of(Locale.of("es"), Locale.ENGLISH, Locale.CHINESE)) {
            ResourceBundle bundle = ResourceBundle.getBundle("com.tpverp.frontend.venta.i18n.messages", locale);
            for (String key : REQUIRED_KEYS) {
                assertTrue(bundle.containsKey(key), () -> "Missing " + key + " in " + locale);
            }
        }
    }
}
