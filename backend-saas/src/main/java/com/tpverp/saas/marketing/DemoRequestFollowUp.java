package com.tpverp.saas.marketing;

import java.util.List;

public record DemoRequestFollowUp(DemoRequestView request, List<DemoRequestNote> notes, boolean hasMoreNotes) {
    public DemoRequestFollowUp {
        notes = List.copyOf(notes);
    }
}
