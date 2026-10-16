package com.tpverp.saas.marketing;

import java.util.List;

public record DemoRequestPage(List<DemoRequestView> items, long total, int page, int size) {
    public DemoRequestPage {
        items = List.copyOf(items);
    }
}
