package com.tpverp.saas.customer;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public final class CustomerAddressBookApi {
    private CustomerAddressBookApi() { }

    public record Entry(UUID id, CustomerAdoptionApi.Address address, String phone, String email, String notes) { }
    public record Request(@NotNull UUID companyId, @NotNull UUID storeId, @NotNull UUID localCustomerId,
            @NotNull UUID customerId, UUID operationId) { }
    public record Snapshot(UUID customerId, long revision, List<Entry> addresses, String operationStatus,
            UUID operationId) { }
}
