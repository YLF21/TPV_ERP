package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class MemberLoyaltyControllerContractTest {

    @Test
    void exposesMemberLoyaltyEndpointsWithCustomerPermissions() throws Exception {
        assertThat(method("list").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/members");
        assertThat(method("get").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/members/{id}");
        assertThat(method("createCategory", MemberLoyaltyController.CategoryRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("ADMIN");
        assertThat(method("setCategory", MemberLoyaltyController.SetCategoryRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMERS_WRITE", "GESTION_CLIENTE_PROVEEDOR");
        assertThat(method("settings").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/member-settings");
        assertThat(method("updateSettings", MemberLoyaltyController.SettingsRequest.class)
                .getAnnotation(PutMapping.class).value())
                .containsExactly("/api/v1/member-settings");
        assertThat(method("createChannel", MemberLoyaltyController.ChannelRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/v1/commercial-contact-channels");
        assertThat(method("channels").getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMERS_READ", "GESTION_CLIENTE_PROVEEDOR", "VENTA");
        assertThat(method("cardDeliveries", MemberCardDeliveryStatus.class)
                .getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/member-card-deliveries");
        assertThat(method("retryCardDelivery", java.util.UUID.class)
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/api/v1/member-card-deliveries/{id}/retry");
    }

    @Test
    void mapsBothBaseAmountsAndRewardsToTheSettingsCommand() {
        var request = new MemberLoyaltyController.SettingsRequest(
                true, new BigDecimal("10.00"), new BigDecimal("5.00"),
                BalanceExpirationPolicy.NO_CADUCA,
                true, new BigDecimal("2.00"), new BigDecimal("3.00"),
                true, false, MemberCardCodeFormat.QR, null, null);

        var command = request.command();

        assertThat(command.balanceAccrualEnabled()).isTrue();
        assertThat(command.balanceAccrualBaseAmount()).isEqualByComparingTo("10.00");
        assertThat(command.balanceAccrualPercent()).isEqualByComparingTo("5.00");
        assertThat(command.pointsAccrualEnabled()).isTrue();
        assertThat(command.pointsAccrualBaseAmount()).isEqualByComparingTo("2.00");
        assertThat(command.pointsPerEuro()).isEqualByComparingTo("3.00");
    }

    private static java.lang.reflect.Method method(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return Arrays.stream(MemberLoyaltyController.class.getDeclaredMethods())
                .filter(value -> value.getName().equals(name))
                .filter(value -> value.getParameterCount() == parameterTypes.length
                        || value.getParameterCount() == parameterTypes.length + 1)
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
    }
}
