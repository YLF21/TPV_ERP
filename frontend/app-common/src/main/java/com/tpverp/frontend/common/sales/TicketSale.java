package com.tpverp.frontend.common.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TicketSale {

    private final List<SaleLine> lines = new ArrayList<>();
    private BigDecimal globalDiscount = BigDecimal.ZERO;
    private int selectedIndex = -1;

    public void addLine(ProductSnapshot product) {
        lines.add(new SaleLine(product, globalDiscount));
        selectedIndex = lines.size() - 1;
    }

    public void selectLine(int index) {
        if (index < 0 || index >= lines.size()) {
            throw new IllegalArgumentException("selected line out of range");
        }
        selectedIndex = index;
    }

    public void setSelectedQuantity(BigDecimal quantity) {
        replaceSelected(selected().withQuantity(quantity));
    }

    public void applySelectedPackages(BigDecimal packages) {
        replaceSelected(selected().withPackages(packages));
    }

    public void applySelectedDiscount(BigDecimal discountPercent) {
        replaceSelected(selected().withDiscount(discountPercent));
    }

    public void changeSelectedPrice(BigDecimal price) {
        replaceSelected(selected().withPrice(price));
    }

    public void applyGlobalDiscount(BigDecimal discountPercent) {
        globalDiscount = discountPercent;
        for (int i = 0; i < lines.size(); i++) {
            lines.set(i, lines.get(i).withDiscount(discountPercent));
        }
    }

    public List<SaleLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public BigDecimal totalBeforeDiscount() {
        return money(lines.stream()
                .map(SaleLine::totalBeforeDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public BigDecimal totalAfterDiscount() {
        return money(lines.stream()
                .map(SaleLine::totalAfterDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public BigDecimal totalQuantity() {
        return normalize(lines.stream().map(SaleLine::quantity).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public BigDecimal totalPackages() {
        return normalize(lines.stream().map(SaleLine::packages).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    public void clear() {
        lines.clear();
        selectedIndex = -1;
        globalDiscount = BigDecimal.ZERO;
    }

    private SaleLine selected() {
        if (selectedIndex < 0 || selectedIndex >= lines.size()) {
            throw new IllegalStateException("No hay linea seleccionada");
        }
        return lines.get(selectedIndex);
    }

    private void replaceSelected(SaleLine line) {
        lines.set(selectedIndex, line);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return new BigDecimal(value.stripTrailingZeros().toPlainString());
    }
}
