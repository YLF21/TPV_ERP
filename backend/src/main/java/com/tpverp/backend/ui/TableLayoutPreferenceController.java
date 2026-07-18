package com.tpverp.backend.ui;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ui/table-preferences")
@PreAuthorize("isAuthenticated()")
public class TableLayoutPreferenceController {

    private final TableLayoutPreferenceService service;

    public TableLayoutPreferenceController(TableLayoutPreferenceService service) {
        this.service = service;
    }

    @GetMapping("/{app}")
    public TableLayoutPreferenceService.PreferenceListView list(
            @PathVariable
            @NotBlank
            @Pattern(regexp = TableLayoutPreferenceService.APP_PATTERN)
            String app,
            Authentication authentication) {
        return service.list(app, authentication);
    }

    @GetMapping("/{app}/{tableKey}")
    public TableLayoutPreferenceService.PreferenceView get(
            @PathVariable
            @NotBlank
            @Pattern(regexp = TableLayoutPreferenceService.APP_PATTERN)
            String app,
            @PathVariable
            @NotBlank
            @Size(max = TableLayoutPreference.MAX_TABLE_KEY_LENGTH)
            @Pattern(regexp = TableLayoutPreferenceService.TABLE_KEY_PATTERN)
            String tableKey,
            Authentication authentication) {
        return service.get(app, tableKey, authentication);
    }

    @PutMapping("/{app}/{tableKey}")
    public TableLayoutPreferenceService.PreferenceView save(
            @PathVariable
            @NotBlank
            @Pattern(regexp = TableLayoutPreferenceService.APP_PATTERN)
            String app,
            @PathVariable
            @NotBlank
            @Size(max = TableLayoutPreference.MAX_TABLE_KEY_LENGTH)
            @Pattern(regexp = TableLayoutPreferenceService.TABLE_KEY_PATTERN)
            String tableKey,
            @Valid @RequestBody TableLayoutPreferenceService.SavePreferenceRequest request,
            Authentication authentication) {
        return service.save(app, tableKey, request, authentication);
    }
}
