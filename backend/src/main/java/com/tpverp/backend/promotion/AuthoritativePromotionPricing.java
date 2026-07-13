package com.tpverp.backend.promotion;

import com.tpverp.backend.catalog.DiscountType;
import com.tpverp.backend.catalog.PriceUseMode;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.document.DocumentLineCommand;
import com.tpverp.backend.document.Money;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthoritativePromotionPricing {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CustomerRepository customers;
    private final MemberRepository members;

    public AuthoritativePromotionPricing(
            CustomerRepository customers,
            MemberRepository members) {
        this.customers = customers;
        this.members = members;
    }

    public CustomerContext customerContext(UUID companyId, UUID customerId) {
        if (customerId == null) {
            return CustomerContext.anonymous();
        }
        customers.findByIdAndCompanyId(customerId, companyId)
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));
        var member = members.findByCustomerIdAndCompanyId(customerId, companyId)
                .filter(Member::isActive)
                .orElse(null);
        return new CustomerContext(
                customerId,
                member == null ? null : member.getId(),
                member == null || member.getMemberCategory() == null
                        ? null : member.getMemberCategory().getId());
    }

    public DocumentLineCommand priceLine(
            Product product,
            LocalDate documentDate,
            CustomerContext customer,
            DocumentLineCommand line) {
        var price = basePrice(product, documentDate, customer);
        var rate = rate(product, documentDate, customer);
        var priced = line.withPrice(price, rate);
        return product.getDiscountType() == DiscountType.NONE
                ? priced.withDiscount(BigDecimal.ZERO, rate)
                : priced;
    }

    public BigDecimal basePrice(
            Product product,
            LocalDate documentDate,
            CustomerContext customer) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(documentDate, "documentDate");
        Objects.requireNonNull(customer, "customer");
        var salePrice = requiredPrice(product.getSalePrice(), "precio de venta");
        if (product.getDiscountType() == DiscountType.NONE) {
            return salePrice;
        }
        var mode = product.getPriceUseMode() == null ? PriceUseMode.NORMAL : product.getPriceUseMode();
        return switch (mode) {
            case NORMAL -> salePrice;
            case MEMBER_PRICE -> customer.isMember() && product.getMemberPrice() != null
                    ? Money.euros(product.getMemberPrice()) : salePrice;
            case OFFER_PRICE -> offerApplies(product, documentDate) && product.getOfferPrice() != null
                    ? Money.euros(product.getOfferPrice()) : salePrice;
            case OFFER_DISCOUNT -> offerApplies(product, documentDate)
                    && product.getOfferDiscountPercent() != null
                    ? Money.euros(salePrice.multiply(BigDecimal.ONE.subtract(
                    product.getOfferDiscountPercent().divide(HUNDRED))))
                    : salePrice;
        };
    }

    public boolean matchesSegment(Promotion promotion, CustomerContext customer) {
        return switch (promotion.customerSegment()) {
            case ALL -> true;
            case IDENTIFIED_CUSTOMERS -> customer.customerId() != null;
            case MEMBERS_ONLY -> customer.isMember();
            case MEMBER_CATEGORY -> customer.isMember()
                    && Objects.equals(promotion.memberCategoryId(), customer.memberCategoryId());
        };
    }

    private String rate(Product product, LocalDate date, CustomerContext customer) {
        if (product.getDiscountType() == DiscountType.NONE) {
            return "VENTA";
        }
        var mode = product.getPriceUseMode() == null ? PriceUseMode.NORMAL : product.getPriceUseMode();
        return switch (mode) {
            case MEMBER_PRICE -> customer.isMember() && product.getMemberPrice() != null ? "MEMBER" : "VENTA";
            case OFFER_PRICE -> offerApplies(product, date) && product.getOfferPrice() != null ? "OFERTA" : "VENTA";
            case OFFER_DISCOUNT -> offerApplies(product, date)
                    && product.getOfferDiscountPercent() != null ? "OFERTA" : "VENTA";
            case NORMAL -> "VENTA";
        };
    }

    private static boolean offerApplies(Product product, LocalDate date) {
        return product.isOfferActive()
                && product.getOfferFrom() != null
                && !date.isBefore(product.getOfferFrom())
                && (product.getOfferUntil() == null || !date.isAfter(product.getOfferUntil()));
    }

    private static BigDecimal requiredPrice(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalStateException(field + " no configurado");
        }
        return Money.euros(value);
    }

    public record CustomerContext(
            UUID customerId,
            UUID memberId,
            UUID memberCategoryId) {

        public static CustomerContext anonymous() {
            return new CustomerContext(null, null, null);
        }

        public boolean isMember() {
            return memberId != null;
        }
    }
}
