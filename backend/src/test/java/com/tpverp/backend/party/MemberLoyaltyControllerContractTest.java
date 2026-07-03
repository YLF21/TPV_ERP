package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(method("get").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/members/{id}");
        assertThat(method("createCategory", MemberLoyaltyController.CategoryRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("CUSTOMERS_WRITE");
        assertThat(method("setCategory", MemberLoyaltyController.SetCategoryRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
        assertThat(method("settings").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/member-settings");
        assertThat(method("updateSettings", MemberLoyaltyController.SettingsRequest.class)
                .getAnnotation(PutMapping.class).value())
                .containsExactly("/api/v1/member-settings");
        assertThat(method("createChannel", MemberLoyaltyController.ChannelRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/v1/commercial-contact-channels");
        assertThat(method("cardDeliveries", MemberCardDeliveryStatus.class)
                .getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/member-card-deliveries");
        assertThat(method("retryCardDelivery", java.util.UUID.class)
                .getAnnotation(PatchMapping.class).value())
                .containsExactly("/api/v1/member-card-deliveries/{id}/retry");
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
