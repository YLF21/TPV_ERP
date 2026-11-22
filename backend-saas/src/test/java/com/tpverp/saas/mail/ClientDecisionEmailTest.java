package com.tpverp.saas.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientDecisionEmailTest {
    @Test
    void escapesUntrustedNameReasonAndPortalUrl() {
        ClientDecisionEmail.Content approved = ClientDecisionEmail.render(true, "es", "<Ana & \"A\">",
                "ana", null, "https://portal.example/path?a=1&b='x'", true);
        assertThat(approved.html()).contains("&lt;Ana &amp; &quot;A&quot;&gt;")
                .contains("href=\"https://portal.example/path?a=1&amp;b=&#39;x&#39;\"")
                .doesNotContain("<Ana &");

        ClientDecisionEmail.Content rejected = ClientDecisionEmail.render(false, "es", null,
                null, "<script>alert('x') & \"y\"</script>\nsegunda línea", null, false);
        assertThat(rejected.html()).contains("&lt;script&gt;alert(&#39;x&#39;) &amp; &quot;y&quot;&lt;/script&gt;\nsegunda línea")
                .doesNotContain("<script>");
        assertThat(rejected.text()).contains("<script>alert('x') & \"y\"</script>\nsegunda línea");
    }

    @Test
    void localizesEnglishAndChineseAndDefaultsUnknownLocaleToSpanish() {
        assertThat(ClientDecisionEmail.render(true, "en-US", null, "u", null, null, true).subject())
                .isEqualTo("Your esPOS account has been approved");
        assertThat(ClientDecisionEmail.render(false, "zh-CN", null, null, "原始原因", null, false).text())
                .contains("您的账户申请未获批准", "原因:\n原始原因");
        assertThat(ClientDecisionEmail.render(false, "fr", null, null, "motivo", null, false).subject())
                .isEqualTo("Tu solicitud de cuenta esPOS no ha sido aprobada");
    }

    @Test
    void approvalWithoutStoresExplainsLinkingAndNeverIncludesPassword() {
        ClientDecisionEmail.Content content = ClientDecisionEmail.render(true, null, null, "client-user",
                null, "https://portal.example", false);
        assertThat(content.text()).contains("client-user", "contraseña que elegiste", "personal de SaaS puede vincularlas")
                .doesNotContain("password", "secret", "contraseña123");
        assertThat(content.html()).contains("client-user", "personal de SaaS puede vincularlas");
    }

    @Test
    void subjectsAreFixedAndUnsafePortalSchemesDoNotCreateLinks() {
        ClientDecisionEmail.Content content = ClientDecisionEmail.render(true, "es", "Mallory\nBcc: x",
                "<admin>", null, "javascript:alert(1)", true);
        assertThat(content.subject()).isEqualTo("Tu cuenta de esPOS ha sido aprobada")
                .doesNotContain("Mallory", "admin");
        assertThat(content.html()).doesNotContain("href=").contains("&lt;admin&gt;");
    }
}
