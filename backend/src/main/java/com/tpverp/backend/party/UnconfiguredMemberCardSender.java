package com.tpverp.backend.party;

import org.springframework.stereotype.Component;

@Component
public class UnconfiguredMemberCardSender implements MemberCardSender {

    @Override
    public void send(MemberCardDelivery delivery) {
        throw new IllegalStateException("message.member_card_delivery.sender_not_configured");
    }
}
