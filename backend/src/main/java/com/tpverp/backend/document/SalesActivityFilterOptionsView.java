package com.tpverp.backend.document;

import java.time.LocalDate;

public record SalesActivityFilterOptionsView(
        LocalDate earliestDate,
        LocalDate currentDate) {
}
