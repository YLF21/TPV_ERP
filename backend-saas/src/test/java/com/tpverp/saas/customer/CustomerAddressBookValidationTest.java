package com.tpverp.saas.customer;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class CustomerAddressBookValidationTest {
    @Test void preservesOrderAndIdsAndNormalizesOptionalContactFields() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var values = CustomerAddressBookService.normalize(List.of(
                Map.of("id", first.toString(), "address", Map.of("address", "  Calle 1  ", "country", "es"),
                        "phone", " 600123123 ", "notes", " Primary "),
                Map.of("id", second.toString(), "address", Map.of(), "email", "  ")));
        assertThat(values).extracting(CustomerAddressBookApi.Entry::id).containsExactly(first, second);
        assertThat(values.getFirst().address().country()).isEqualTo("ES");
        assertThat(values.getFirst().address().address()).isEqualTo("Calle 1");
        assertThat(values.getFirst().phone()).isEqualTo("600123123");
        assertThat(values.getFirst().notes()).isEqualTo("Primary");
        assertThat(values.getLast().email()).isNull();
    }

    @Test void rejectsDuplicateIdsEmptyBooksUnexpectedFieldsAndMalformedEntries() {
        var valid = Map.of("id", UUID.randomUUID().toString(), "address", Map.of());
        for (Object bad : List.of(List.of(), List.of(valid, valid), List.of(Map.of("id", "1-1-1-1-1", "address", Map.of())),
                List.of(Map.of("id", UUID.randomUUID().toString(), "address", "street")),
                List.of(Map.of("id", UUID.randomUUID().toString(), "address", Map.of("country", "Spain"))),
                List.of(Map.of("id", UUID.randomUUID().toString(), "address", Map.of(), "unexpected", true)))) {
            assertThatThrownBy(() -> CustomerAddressBookService.normalize(bad)).isInstanceOf(ResponseStatusException.class);
        }
        List<Object> excessive = new ArrayList<>();
        for (int i = 0; i < 51; i++) excessive.add(Map.of("id", UUID.randomUUID().toString(), "address", Map.of()));
        assertThatThrownBy(() -> CustomerAddressBookService.normalize(excessive)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void revisionsCannotBeRoundedOrCoercedFromStrings() {
        assertThat(CustomerAddressBookService.revision(1L)).isEqualTo(1);
        for (Object bad : List.of(0L, -1L, 1.5, 1.0, new BigDecimal("1.0"), "1")) {
            assertThatThrownBy(() -> CustomerAddressBookService.revision(bad)).isInstanceOf(ResponseStatusException.class);
        }
    }
}
