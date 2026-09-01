package com.tpverp.backend.promotion;

import java.util.UUID;

public interface PromotionTargetReference {

    UUID getPromotionId();

    String getPromotionName();

    PromotionTargetType getType();

    UUID getTargetId();
}
