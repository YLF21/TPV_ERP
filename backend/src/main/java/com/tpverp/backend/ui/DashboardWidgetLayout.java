package com.tpverp.backend.ui;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DashboardWidgetLayout(
        @NotBlank
        @Size(max = DashboardPreference.MAX_WIDGET_KEY_LENGTH)
        @Pattern(regexp = DashboardPreference.WIDGET_KEY_REGEXP)
        String key,
        @Min(3) @Max(12) int width,
        @Min(1) @Max(3) int height) {
}
