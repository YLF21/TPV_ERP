package com.tpverp.backend.excel;

final class ExcelColumn {

    private ExcelColumn() {
    }

    static int index(String letters) {
        if (letters == null || letters.isBlank()) {
            throw new IllegalArgumentException("columna obligatoria");
        }
        int result = 0;
        for (char value : letters.trim().toUpperCase().toCharArray()) {
            if (value < 'A' || value > 'Z') {
                throw new IllegalArgumentException("columna no valida");
            }
            result = result * 26 + (value - 'A' + 1);
        }
        return result - 1;
    }
}
