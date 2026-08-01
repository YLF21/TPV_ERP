package com.tpverp.backend.catalog;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/pos/internal-ean")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + VENTA + "','" + GESTION_VENTAS + "')")
public class InternalEanController {

    private final InternalEanService service;
    private final ProductImageService images;

    public InternalEanController(
            InternalEanService service,
            ProductImageService images) {
        this.service = service;
        this.images = images;
    }

    @PostMapping("/validate")
    public InternalEanFormat.Validation validate(
            @Valid @RequestBody ValidateRequest request) {
        return service.validate(request.code());
    }

    @PostMapping("/reservations")
    public InternalEanService.ReservationView reserve(
            @Valid @RequestBody ReserveRequest request,
            Authentication authentication) {
        return service.reserve(
                request.format(), request.authorization(), authentication);
    }

    @PostMapping("/manual/reservations")
    public InternalEanService.ReservationView reserveManual(
            @Valid @RequestBody ManualReserveRequest request,
            Authentication authentication) {
        return service.reserveManual(
                request.code(), request.authorization(), authentication);
    }

    @PostMapping("/assign-existing")
    public ProductView assignExisting(
            @Valid @RequestBody ReservationAssignmentRequest request,
            Authentication authentication) {
        return service.assignReservationToExistingProduct(
                request.reservationId(), request.productId(),
                request.replaceExisting(), authentication);
    }

    @PostMapping("/create-product")
    public ProductView createProduct(
            @Valid @RequestBody ReservationProductCreateRequest request,
            Authentication authentication) {
        return service.createProductFromReservation(
                request.reservationId(), request.product(), authentication);
    }

    @PutMapping(
            path = "/assignments/{allocationId}/products/{productId}/image",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProductView uploadCreatedProductImage(
            @PathVariable UUID allocationId,
            @PathVariable UUID productId,
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {
        service.requireAssignedProduct(
                allocationId, productId, authentication);
        try {
            return ProductView.publicView(images.upload(productId, file.getBytes()));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "internal_ean_image_upload_failed", exception);
        }
    }

    @PostMapping("/manual/assign-existing")
    public ProductView assignManualExisting(
            @Valid @RequestBody ManualAssignmentRequest request,
            Authentication authentication) {
        return service.assignManualToExistingProduct(
                request.code(), request.productId(), request.replaceExisting(),
                request.authorization(), authentication);
    }

    @PostMapping("/manual/create-product")
    public ProductView createManualProduct(
            @Valid @RequestBody ManualProductCreateRequest request,
            Authentication authentication) {
        return service.createProductWithManualCode(
                request.code(), request.product(), request.authorization(),
                authentication);
    }

    public record ValidateRequest(@NotBlank String code) {
    }

    public record ReserveRequest(
            @NotNull InternalEanFormat format,
            @Valid OperationAuthorizationRequest authorization) {
    }

    public record ManualReserveRequest(
            @NotBlank String code,
            @Valid OperationAuthorizationRequest authorization) {
    }

    public record ReservationAssignmentRequest(
            @NotNull UUID reservationId,
            @NotNull UUID productId,
            boolean replaceExisting) {
    }

    public record ReservationProductCreateRequest(
            @NotNull UUID reservationId,
            @NotNull @Valid CatalogService.ProductRequest product) {
    }

    public record ManualAssignmentRequest(
            @NotBlank String code,
            @NotNull UUID productId,
            boolean replaceExisting,
            @Valid OperationAuthorizationRequest authorization) {
    }

    public record ManualProductCreateRequest(
            @NotBlank String code,
            @NotNull @Valid CatalogService.ProductRequest product,
            @Valid OperationAuthorizationRequest authorization) {
    }
}
