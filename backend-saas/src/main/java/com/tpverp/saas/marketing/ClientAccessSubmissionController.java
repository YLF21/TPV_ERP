package com.tpverp.saas.marketing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.saas.admin.AdminPasswordHasher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/client-access-requests")
public class ClientAccessSubmissionController {
    private final JdbcTemplate jdbc;
    private final AdminPasswordHasher passwords;
    private final DemoRequestRateLimiter limiter;

    public ClientAccessSubmissionController(JdbcTemplate jdbc, AdminPasswordHasher passwords, Clock clock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.limiter = new DemoRequestRateLimiter(clock, 30);
    }

    public record Submission(
            @NotNull UUID submissionId,
            @NotNull @Pattern(regexp = "[a-zA-Z0-9._@-]{3,80}") String username,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(min = 12, max = 72) String password,
            @NotBlank @Size(max = 160) String fullName, @NotBlank @Size(max = 40) String taxId,
            @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 40) String phone,
            @NotBlank @Size(max = 240) String address, @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 100) String province,
            @NotBlank @Size(max = 100) String country,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 200) String> requestedStores,
            @Size(max = 1000) String notes, @Pattern(regexp = "es|en|zh") String locale) {
        @Override public String toString() { return "Client access submission"; }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(@Valid @RequestBody Submission request, HttpServletRequest http) {
        if (request.password().codePointCount(0, request.password().length()) < 12
                || request.password().getBytes(StandardCharsets.UTF_8).length > 72
                || Stream.concat(Stream.of(request.password(), request.fullName(), request.taxId(), request.email(),
                        request.phone(), request.address(), request.postalCode(), request.city(), request.province(),
                        request.country(), request.notes()), request.requestedStores().stream())
                        .anyMatch(s -> s != null && s.indexOf(0) >= 0)) {
            throw new DemoRequestFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "La solicitud contiene campos inválidos");
        }
        // A retry can safely follow a lost response, even after staff have reviewed it.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from saas_client_access_request where submission_id = ?)",
                Boolean.class, request.submissionId()))) return;
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        limiter.accept(http.getRemoteAddr(), email);
        String hash = passwords.hash(request.password());
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into saas_client_access_request (id, submission_id, username, password_hash,
                        full_name, tax_id, email, phone, address, postal_code, city, province, country, requested_stores, notes, locale)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (submission_id) do nothing
                    """);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, request.submissionId());
            statement.setString(3, request.username());
            statement.setString(4, hash);
            statement.setString(5, request.fullName().strip());
            statement.setString(6, request.taxId().strip());
            statement.setString(7, email);
            statement.setString(8, request.phone().strip());
            statement.setString(9, request.address().strip());
            statement.setString(10, request.postalCode().strip());
            statement.setString(11, request.city().strip());
            statement.setString(12, request.province().strip());
            statement.setString(13, request.country().strip());
            statement.setArray(14, connection.createArrayOf("text",
                    request.requestedStores().stream().map(String::strip).toArray(String[]::new)));
            statement.setString(15, request.notes() == null || request.notes().isBlank() ? null : request.notes().strip());
            statement.setString(16, request.locale() == null ? "es" : request.locale());
            return statement;
        });
    }
}
