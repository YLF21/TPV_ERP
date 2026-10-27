package com.tpverp.saas.marketing;

import java.time.Instant;
import java.util.UUID;

public record DemoRequestNote(UUID id, String author, String body, Instant createdAt) { }
