package com.tpverp.backend.cash;

import java.util.List;

public record CashAlertDetailView(CashAlertView alert, List<CashAlertAttemptView> attempts) { }
