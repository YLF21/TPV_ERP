package com.tpverp.backend.terminal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tpverp.backend.security.application.AuthenticationService;
import com.tpverp.backend.security.application.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same-origin browser facade; desktop/PDA protocols and approval permissions remain unchanged. */
@RestController
@RequestMapping(value = "/api/v1/web-terminal", produces = MediaType.APPLICATION_JSON_VALUE)
public class WebTerminalController {
    private final WebTerminalPolicy policy;
    private final WebTerminalCookie cookies;
    private final TerminalLinkingService linking;
    private final AuthenticationService authentication;

    public WebTerminalController(WebTerminalPolicy policy, WebTerminalCookie cookies,
            TerminalLinkingService linking, AuthenticationService authentication) {
        this.policy = policy; this.cookies = cookies; this.linking = linking; this.authentication = authentication;
    }

    @PostMapping("/status")
    public BrowserTerminalState status(HttpServletRequest request, HttpServletResponse response) {
        guard(request, response);
        var proof = cookies.read(request).orElseGet(cookies::create);
        var state = linking.browserStatus(proof.linkingProof(), proof.deviceId(), TerminalConnectionAddress.from(request)).orElse(null);
        var result = snapshot(state);
        cookies.write(response, proof);
        return result;
    }

    @PostMapping("/request")
    public BrowserTerminalState request(@Valid @RequestBody Registration registration,
            HttpServletRequest request, HttpServletResponse response) {
        guard(request, response);
        var proof = requireCookie(request);
        var previous = linking.browserStatus(proof.linkingProof(), proof.deviceId(), TerminalConnectionAddress.from(request)).orElse(null);
        if (previous != null && retired(previous.status()))
            // Reset is a separate handshake: a lost response must never reserve a seat with an unknown new proof.
            throw new TerminalLinkingException(HttpStatus.CONFLICT, "WEB_TERMINAL_RESET_REQUIRED");
        var state = linking.request(new TerminalLinkingService.LinkRequest(proof.requestId(), proof.deviceId(), proof.credential(),
                registration.code(), registration.name(), "Navegador web"));
        cookies.write(response, proof);
        return snapshot(state);
    }

    @PostMapping("/cancel")
    public BrowserTerminalState cancel(HttpServletRequest request, HttpServletResponse response) {
        guard(request, response);
        var proof = requireCookie(request);
        var state = linking.browserStatus(proof.linkingProof(), proof.deviceId(), TerminalConnectionAddress.from(request)).orElse(null);
        if (state != null) linking.cancel(proof.linkingProof()); // Existing service refuses ACTIVE/DISABLED.
        var result = snapshot(null);
        cookies.write(response, cookies.create());
        return result;
    }

    @PostMapping("/login")
    public LoginResult login(@Valid @RequestBody BrowserLogin login,
            HttpServletRequest request, HttpServletResponse response) {
        guard(request, response);
        var proof = requireCookie(request);
        var state = linking.browserStatus(proof.linkingProof(), proof.deviceId(), TerminalConnectionAddress.from(request))
                .filter(value -> "ACTIVE".equals(value.status()))
                .orElseThrow(() -> new TerminalLinkingException(HttpStatus.CONFLICT, "WEB_TERMINAL_NOT_ACTIVE"));
        // The normal authentication service checks terminal approval, current credential, user and store access.
        var result = authentication.login(state.terminalId(), proof.credential(), login.userName(), login.password(),
                TerminalConnectionAddress.from(request));
        cookies.write(response, proof);
        return result;
    }

    private void guard(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        policy.require(request);
    }

    private WebTerminalCookie.Proof requireCookie(HttpServletRequest request) {
        return cookies.read(request).orElseThrow(() -> new TerminalLinkingException(HttpStatus.PRECONDITION_REQUIRED,
                "WEB_TERMINAL_COOKIE_REQUIRED"));
    }

    private BrowserTerminalState snapshot(TerminalLinkingService.LinkState state) {
        // Bootstrap's signed public view never includes terminal credentials or management-only IP/device data.
        var bootstrap = linking.bootstrap(cookies.create().credential());
        var slots = bootstrap.slots().stream().map(slot -> new BrowserSlot(slot.code(),
                slot.outOfQuota() ? "OUT_OF_QUOTA" : slot.status(), slot.name())).toList();
        var context = state != null && "ACTIVE".equals(state.status())
                ? new BrowserContext(bootstrap.companyName(), state.storeName(), state.storeInternalCode(), state.terminalCode(),
                        state.terminalName(), state.terminalId(), state.installationId(), state.storeId(), state.bindingId(), "browser-cookie")
                : null;
        return new BrowserTerminalState(state == null ? "UNREGISTERED" : state.status(), context, slots,
                state == null ? null : state.expiresAt(), bootstrap.companyName(), bootstrap.storeName());
    }

    private static boolean retired(String status) {
        return "RELEASED".equals(status) || "CANCELLED".equals(status) || "EXPIRED".equals(status);
    }

    public record Registration(@NotBlank @Pattern(regexp = "[0-9]{3,9}") String code,
            @NotBlank @Size(max = 128) String name) { }
    public record BrowserLogin(@NotBlank @Size(max = 120) String userName, @NotBlank @Size(max = 512) String password) {
        @Override public String toString() { return "BrowserLogin[redacted]"; }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserTerminalState(String status, BrowserContext context, List<BrowserSlot> slots,
            Instant expiresAt, String companyName, String storeName) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserSlot(String code, String status, String name) { }
    public record BrowserContext(String companyName, String storeName, String storeInternalCode, String terminalCode,
            String terminalName, UUID terminalId, UUID installationId, UUID storeId, UUID bindingId, String credentialMode) { }
}
