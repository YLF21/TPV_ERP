package com.tpverp.saas.master;

import java.util.List;
import java.util.Map;

public record MasterSearchPage(List<Map<String, Object>> items, int page, int size, long total) {
}
