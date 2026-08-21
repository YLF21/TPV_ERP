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
@RequestMapping("/api/v2/loyalty/member-wallet")
public class MemberWalletReservationV2Controller {

    private static final String INSTALLATION_TOKEN_HEADER = "X-TPV-Installation-Token";

    private final MemberBalanceReservationService service;

    public MemberWalletReservationV2Controller(MemberBalanceReservationService service) {
        this.service = service;
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public LoyaltyApiModels.WalletReservationResponse reserve(
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.ReserveRequest request) {
        return service.reserveWallet(request, token);
    }

    @PostMapping("/reservations/{reservationId}/heartbeat")
    public LoyaltyApiModels.WalletReservationResponse heartbeat(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.ReservationOwnerRequest request) {
        return service.heartbeatWallet(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/release")
    public LoyaltyApiModels.WalletReservationResponse release(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.ReservationOwnerRequest request) {
        return service.releaseWallet(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/prepare")
    public LoyaltyApiModels.WalletReservationResponse prepare(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.WalletPrepareRequest request) {
        return service.prepareWallet(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/finalize")
    public LoyaltyApiModels.WalletReservationResponse finalizePrepared(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.PreparedOwnerRequest request) {
        return service.finalizePreparedWallet(reservationId, request, token);
    }

    @PostMapping("/reservations/{reservationId}/abort")
    public LoyaltyApiModels.WalletReservationResponse abortPrepared(
            @PathVariable UUID reservationId,
            @RequestHeader(INSTALLATION_TOKEN_HEADER) String token,
            @RequestBody LoyaltyApiModels.PreparedOwnerRequest request) {
        return service.abortPreparedWallet(reservationId, request, token);
    }
}
