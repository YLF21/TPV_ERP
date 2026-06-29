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
            "login.subtitle",
            "login.user",
            "login.userPrompt",
            "login.password",
            "login.passwordPrompt",
            "login.submit",
            "login.error.invalid",
            "login.error.noAccess",
            "product.dialog.title",
            "product.dialog.searchPrompt",
            "product.management.title",
            "product.management.family",
            "product.management.subfamily",
            "product.management.add",
            "product.management.edit",
            "product.management.delete",
            "product.form.save",
            "product.form.code",
            "product.form.barcode",
            "product.form.name",
            "product.form.price",
            "product.form.units",
            "product.form.family",
            "product.form.subfamily",
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
            "shortcut.parked",
            "shortcut.productManagement"
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
