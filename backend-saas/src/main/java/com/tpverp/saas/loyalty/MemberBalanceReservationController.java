package com.tpverp.saas.loyalty;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loyalty/member-balance")
public class MemberBalanceReservationController {

    private static final String INSTALLATION_TOKEN_HEADER = "X-TPV-Installation-Token";

    private final MemberBalanceReservationService service;

    public MemberBalanceReservationController(MemberBalanceReservationService service) {
        this.service = service;
    }

    @PostMapping("/bootstrap")
    public LoyaltyApiModels.BootstrapResponse bootstrap(
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.BootstrapRequest request) {
        return service.bootstrap(request, token);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public LoyaltyApiModels.ReservationResponse reserve(
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.ReserveRequest request) {
        return service.reserve(request, token);
    }

    @PostMapping("/reservations/{reservationId}/heartbeat")
    public LoyaltyApiModels.ReservationResponse heartbeat(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.ReservationOwnerRequest request) {
        return service.heartbeat(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public LoyaltyApiModels.ReservationResponse release(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.ReservationOwnerRequest request) {
        return service.release(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/prepare")
    public LoyaltyApiModels.ReservationResponse prepare(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.PrepareRequest request) {
        return service.prepare(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/finalize")
    public LoyaltyApiModels.ReservationResponse finalizePrepared(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.PreparedOwnerRequest request) {
        return service.finalizePrepared(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/abort")
    public LoyaltyApiModels.ReservationResponse abortPrepared(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.PreparedOwnerRequest request) {
        return service.abortPrepared(reservationId, request, token);
    }
}
