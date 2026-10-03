package com.tpverp.saas.marketing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

public record CreateDemoRequest(
        DemoProduct product,
        @Size(min = 1, max = 4) List<@NotNull DemoProduct> products,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 200) String company,
        @NotBlank @Email @Size(max = 160) String email,
        @Size(max = 40) String phone,
        @Size(max = 2000) String message,
        @NotBlank @Pattern(regexp = "es|en|zh") String locale,
        @NotNull @AssertTrue Boolean privacyAccepted,
        @Size(max = 200) String website,
        @Size(max = 500) String landingPath,
        @Size(max = 1000) String referrer,
        @Size(max = 160) String utmSource,
        @Size(max = 160) String utmMedium,
        @Size(max = 200) String utmCampaign) {

    @JsonIgnore
    @AssertTrue
    public boolean isProductSelectionValid() {
        if (products == null) return product != null;
        if (products.isEmpty() || products.size() > 4 || products.contains(null)
                || new HashSet<>(products).size() != products.size()) return false;
        // The legacy value is only a compatible summary, never a separate selection.
        return product == null || product == products.stream().min(Enum::compareTo).orElseThrow();
    }

    List<DemoProduct> selectedProducts() {
        return products == null ? List.of(product) : products.stream().sorted().toList();
    }
}
