package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

class MemberSmtpSettingsControllerContractTest {

    @Test
    void exposesAdminOnlySmtpEndpoints() throws Exception {
        assertThat(method("get").getAnnotation(GetMapping.class).value())
                .containsExactly("/api/v1/member-smtp-settings");
        assertThat(method("update", MemberSmtpSettingsController.SettingsRequest.class)
                .getAnnotation(PutMapping.class).value())
                .containsExactly("/api/v1/member-smtp-settings");
        assertThat(method("test", MemberSmtpSettingsController.TestRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/api/v1/member-smtp-settings/test");
        assertThat(method("get").getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasRole('ADMIN')");
    }

    private static java.lang.reflect.Method method(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return Arrays.stream(MemberSmtpSettingsController.class.getDeclaredMethods())
                .filter(value -> value.getName().equals(name))
                .filter(value -> value.getParameterCount() == parameterTypes.length)
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
    }
}
