package com.tpverp.backend.observability;

import java.util.Locale;
import java.util.Optional;

final class BusinessOperationClassifier {

    private BusinessOperationClassifier() {
    }

    static Optional<BusinessOperation> classify(String method, String requestUri) {
        var verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        var path = normalizedPath(requestUri);

        if (path.startsWith("/api/v1/pos/cash")) {
            return Optional.of(posCash(verb, path));
        }
        if (path.startsWith("/api/v1/pos/card")) {
            return Optional.of(posCard(verb, path));
        }
        if (path.startsWith("/api/v1/pos/payment-sessions")) {
            return Optional.of(paymentSession(verb, path));
        }
        if (path.startsWith("/api/v1/pos/customer-pending-sales")) {
            return Optional.of(customerPendingSale(verb, path));
        }
        if (path.startsWith("/api/v1/customer-receivables")) {
            return Optional.of(receivable(verb, path));
        }
        if (path.startsWith("/api/v1/customer-credit-accounts")) {
            return Optional.of(read("credit", "account.read"));
        }
        if (path.startsWith("/api/v1/tickets")) {
            return Optional.of(ticket(verb, path));
        }
        if (path.startsWith("/api/v1/promotions")
                || path.startsWith("/api/v1/promotional-coupons")) {
            return Optional.of(promotion(verb, path));
        }
        if (path.startsWith("/api/v1/payment-terminal")
                || path.startsWith("/api/v1/terminal-configuration/payment")) {
            return Optional.of(terminal(verb, path));
        }
        return Optional.empty();
    }

    private static BusinessOperation posCash(String verb, String path) {
        if (path.endsWith("/quote")) {
            return read("sale", "cash.quote");
        }
        return "POST".equals(verb)
                ? operation("sale", "cash.charge", "POS_CASH_SALE_COMPLETED")
                : read("sale", "cash.read");
    }

    private static BusinessOperation posCard(String verb, String path) {
        if (path.endsWith("/quote")) {
            return read("sale", "card.quote");
        }
        return "POST".equals(verb)
                ? operation("payment", "card.charge", "POS_CARD_CHARGE_REQUESTED")
                : read("payment", "card.read");
    }

    private static BusinessOperation paymentSession(String verb, String path) {
        if ("POST".equals(verb) && path.endsWith("/finalize")) {
            return operation("payment", "session.finalize", "PAYMENT_SESSION_FINALIZED");
        }
        if ("POST".equals(verb) && path.endsWith("/cancel")) {
            return operation("payment", "session.cancel", "PAYMENT_SESSION_CANCELLED");
        }
        if ("POST".equals(verb) && path.contains("/allocations")) {
            return operation("payment", "allocation.process", "PAYMENT_ALLOCATION_PROCESSED");
        }
        if ("POST".equals(verb)) {
            return operation("payment", "session.reserve", "PAYMENT_SESSION_RESERVED");
        }
        return read("payment", "session.read");
    }

    private static BusinessOperation customerPendingSale(String verb, String path) {
        if (path.endsWith("/quote")) {
            return read("credit", "pending_sale.quote");
        }
        if (path.endsWith("/card-charges")) {
            return operation("credit", "pending_sale.card_charge", "CUSTOMER_PENDING_CARD_CHARGE_REQUESTED");
        }
        return "POST".equals(verb)
                ? operation("credit", "pending_sale.create", "CUSTOMER_PENDING_SALE_CREATED")
                : read("credit", "pending_sale.read");
    }

    private static BusinessOperation receivable(String verb, String path) {
        if ("POST".equals(verb) && path.endsWith("/payments")) {
            return operation("credit", "receivable.payment", "CUSTOMER_RECEIVABLE_PAYMENT_RECORDED");
        }
        if ("POST".equals(verb) && path.contains("/card-charges")) {
            return operation("credit", "receivable.card_charge", "CUSTOMER_RECEIVABLE_CARD_CHARGE_REQUESTED");
        }
        return read("credit", "receivable.read");
    }

    private static BusinessOperation ticket(String verb, String path) {
        if ("POST".equals(verb) && path.endsWith("/returns")) {
            return operation("return", "ticket.return", "TICKET_RETURN_CREATED");
        }
        if ("POST".equals(verb) && path.endsWith("/cancel")) {
            return operation("sale", "ticket.cancel", "TICKET_CANCELLED");
        }
        if ("POST".equals(verb) && path.endsWith("/invoice")) {
            return operation("sale", "ticket.invoice", "TICKET_CONVERTED_TO_INVOICE");
        }
        if ("POST".equals(verb) && "/api/v1/tickets".equals(path)) {
            return operation("sale", "ticket.create", "TICKET_CREATED");
        }
        return read("sale", path.endsWith("/return-options") ? "ticket.return_options" : "ticket.read");
    }

    private static BusinessOperation promotion(String verb, String path) {
        if (path.endsWith("/preview") || path.endsWith("/evaluate")) {
            return read("promotion", "pricing.evaluate");
        }
        if ("GET".equals(verb)) {
            return read("promotion", "configuration.read");
        }
        if (path.startsWith("/api/v1/promotional-coupons")) {
            return operation("promotion", "coupon.mutate", "PROMOTIONAL_COUPON_CHANGED");
        }
        return operation("promotion", "configuration.mutate", "PROMOTION_CONFIGURATION_CHANGED");
    }

    private static BusinessOperation terminal(String verb, String path) {
        if ("GET".equals(verb)) {
            return read("payment", "terminal.read");
        }
        if (path.endsWith("/connection-test")) {
            return operation("payment", "terminal.connection_test", "PAYMENT_TERMINAL_CONNECTION_TESTED");
        }
        return operation("payment", "terminal.operation", "PAYMENT_TERMINAL_OPERATION_REQUESTED");
    }

    private static BusinessOperation operation(String domain, String name, String event) {
        return new BusinessOperation(domain, name, event);
    }

    private static BusinessOperation read(String domain, String name) {
        return new BusinessOperation(domain, name, null);
    }

    private static String normalizedPath(String requestUri) {
        if (requestUri == null || requestUri.isBlank()) {
            return "/";
        }
        var queryStart = requestUri.indexOf('?');
        var path = queryStart < 0 ? requestUri : requestUri.substring(0, queryStart);
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }
}
