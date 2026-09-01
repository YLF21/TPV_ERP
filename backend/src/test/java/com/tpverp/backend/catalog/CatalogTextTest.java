package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CatalogTextTest {

    @Test
    void searchTermUsesNormalizedUnicodeCodePointBounds() {
        assertThat(CatalogText.searchTerm(" café ")).isEqualTo("CAFE");
        assertThat(CatalogText.searchTerm("façana")).isEqualTo("FACANA");
        assertThat(CatalogText.searchTerm("e\u0301cl")).isEqualTo("ECL");
        assertThat(CatalogText.searchTerm("😀a")).isEqualTo("😀A");
        assertThat(CatalogText.searchTerm("a".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> CatalogText.searchTerm("a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("q debe contener entre 2 y 100 caracteres");
        assertThatThrownBy(() -> CatalogText.searchTerm("a".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("q debe contener entre 2 y 100 caracteres");
        assertThatThrownBy(() -> CatalogText.searchTerm("😀"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("q debe contener entre 2 y 100 caracteres");
        assertThatThrownBy(() -> CatalogText.searchTerm("e\u0301"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("q debe contener entre 2 y 100 caracteres");
    }

    @Test
    void normalizedCanonicalizesBeforeUppercase() {
        assertThat(CatalogText.normalized("e\u0301clair", "nombre")).isEqualTo("ÉCLAIR");
    }

    @Test
    void escapesLikeMetacharactersOnlyAfterSearchLengthValidation() {
        String normalized = CatalogText.searchTerm(" 50%_\\x ");

        assertThat(normalized).isEqualTo("50%_\\X");
        assertThat(CatalogText.escapeLikeLiteral(normalized))
                .isEqualTo("50\\%\\_\\\\X");
        assertThat(CatalogText.escapeLikeLiteral(CatalogText.searchTerm("%".repeat(100))))
                .hasSize(200);
        assertThatThrownBy(() -> CatalogText.searchTerm("%"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("q debe contener entre 2 y 100 caracteres");
    }
}
