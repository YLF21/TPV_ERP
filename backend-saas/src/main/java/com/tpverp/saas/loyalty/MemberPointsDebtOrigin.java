package com.tpverp.saas.loyalty;

public enum MemberPointsDebtOrigin {
    RETURN_REVERSAL,
    SALE_CANCELLATION,
    BOOTSTRAP_OPENING;

    public static MemberPointsDebtOrigin fromOperation(MemberPointsOperationType type) {
        return switch (type) {
            case RETURN_REVERSAL -> RETURN_REVERSAL;
            case SALE_CANCELLATION -> SALE_CANCELLATION;
            default -> throw new IllegalArgumentException("La operacion no puede originar deuda de puntos");
        };
    }
}
