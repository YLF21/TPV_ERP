package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

class PromotionalCouponControllerContractTest {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')";
    private static final String MANAGE_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')";

    @Test
    void exposesCouponApiWithoutPlaintextOrHashInViews() {
        assertThat(PromotionalCouponController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/promotional-coupons");

        assertThat(method("list").getAnnotation(GetMapping.class)).isNotNull();
        assertThat(method("list").getAnnotation(PreAuthorize.class).value()).isEqualTo(READ_PERMISSION);
        assertThat(method("redeem").getAnnotation(PostMapping.class).value()).containsExactly("/redeem");
        assertThat(method("redeem").getAnnotation(PreAuthorize.class).value()).isEqualTo(READ_PERMISSION);
        assertThat(method("cancel").getAnnotation(PatchMapping.class).value()).containsExactly("/{id}/cancel");
        assertThat(method("cancel").getAnnotation(PreAuthorize.class).value()).isEqualTo(MANAGE_PERMISSION);
        assertThat(method("reactivate").getAnnotation(PatchMapping.class).value()).containsExactly("/{id}/reactivate");
        assertThat(method("reactivate").getAnnotation(PreAuthorize.class).value()).isEqualTo(MANAGE_PERMISSION);

        assertThat(PromotionalCouponView.class.getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("hash") || name.equals("code") || name.contains("plaintext"));
    }

    @Test
    void writeRequestsAreValidated() {
        assertValidBody("redeem", PromotionalCouponRedeemRequest.class);
        assertValidBody("cancel", PromotionalCouponAdminActionRequest.class);
        assertValidBody("reactivate", PromotionalCouponAdminActionRequest.class);
    }

    private void assertValidBody(String methodName, Class<?> requestType) {
        var parameter = java.util.Arrays.stream(method(methodName).getParameters())
                .filter(candidate -> candidate.getType().equals(requestType))
                .findFirst()
                .orElseThrow();
        assertThat(parameter.getAnnotation(RequestBody.class)).isNotNull();
        assertThat(parameter.getAnnotation(Valid.class)).isNotNull();
    }

    private Method method(String name) {
        return java.util.Arrays.stream(PromotionalCouponController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
