package com.tpverp.frontend.common.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketSaleTest {

    @Test
    void editsSelectedLineNotLastLine() {
        var sale = new TicketSale();
        sale.addLine(product("A", "Articulo A", "10.00", 12));
        sale.addLine(product("B", "Articulo B", "20.00", 6));

        sale.selectLine(0);
        sale.setSelectedQuantity(new BigDecimal("3"));
        sale.applySelectedDiscount(new BigDecimal("10"));

        assertEquals(new BigDecimal("3"), sale.lines().get(0).quantity());
        assertEquals(new BigDecimal("10"), sale.lines().get(0).discountPercent());
        assertEquals(BigDecimal.ONE, sale.lines().get(1).quantity());
        assertEquals(new BigDecimal("50.00"), sale.totalBeforeDiscount());
        assertEquals(new BigDecimal("47.00"), sale.totalAfterDiscount());
    }

    @Test
    void packageCommandConvertsPackagesToUnits() {
        var sale = new TicketSale();
        sale.addLine(product("A", "Articulo A", "10.00", 12));

        sale.applySelectedPackages(new BigDecimal("2"));

        assertEquals(new BigDecimal("2"), sale.lines().getFirst().packages());
        assertEquals(new BigDecimal("24"), sale.lines().getFirst().quantity());
        assertEquals(new BigDecimal("240.00"), sale.totalAfterDiscount());
    }

    @Test
    void globalDiscountAppliesToCurrentAndFutureLines() {
        var sale = new TicketSale();
        sale.addLine(product("A", "Articulo A", "10.00", 12));

        sale.applyGlobalDiscount(new BigDecimal("20"));
        sale.addLine(product("B", "Articulo B", "5.00", 1));

        assertEquals(new BigDecimal("20"), sale.lines().get(0).discountPercent());
        assertEquals(new BigDecimal("20"), sale.lines().get(1).discountPercent());
        assertEquals(new BigDecimal("12.00"), sale.totalAfterDiscount());
    }

    @Test
    void quickCommandKeepsValueAndActionSeparate() {
        assertEquals(QuickCommand.Action.ADD_PRODUCT, QuickCommand.from("20", "ENTER").action());
        assertEquals(QuickCommand.Action.SET_QUANTITY, QuickCommand.from("20", "PAUSE").action());
        assertEquals(QuickCommand.Action.LINE_DISCOUNT, QuickCommand.from("20", "SLASH").action());
        assertEquals(QuickCommand.Action.GLOBAL_DISCOUNT, QuickCommand.from("20", "CTRL_SLASH").action());
        assertEquals(new BigDecimal("20"), QuickCommand.from("20", "CTRL_SLASH").value());
    }

    private ProductSnapshot product(String code, String name, String price, int unitsPerPackage) {
        return new ProductSnapshot(code, name, new BigDecimal(price), unitsPerPackage);
    }
}
