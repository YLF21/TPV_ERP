package com.tpverp.saas.marketing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record UpdateDemoRequestFollowUp(
        @Size(max = 80) String assigneeUsername,
        @Size(max = 500) String nextAction,
        Instant nextActionAt,
        @Size(max = 2000) String note,
        @NotNull @PositiveOrZero Long expectedVersion) { }
