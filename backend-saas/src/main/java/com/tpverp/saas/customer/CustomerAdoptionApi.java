package com.tpverp.saas.customer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class CustomerAdoptionApi {
    private CustomerAdoptionApi() { }

    public record Lookup(@NotNull UUID companyId, @NotNull UUID storeId,
            @NotBlank @Size(max = 20) String documentType, @NotBlank @Size(max = 64) String documentNumber) { }

    public record Reserve(@NotNull UUID companyId, @NotNull UUID storeId, @NotNull UUID operationId,
            @NotNull UUID localCustomerId, @NotNull UUID customerId, @NotNull @Min(0)
            @tools.jackson.databind.annotation.JsonDeserialize(using = RevisionDeserializer.class) Long expectedRevision,
            @NotBlank @Size(max = 20) String documentType, @NotBlank @Size(max = 64) String documentNumber) { }

    public record Address(String address, String postalCode, String city, String province, String country) { }

    public record Profile(UUID customerId, long revision, String centralCode, String fiscalName,
            String documentType, String documentNumber, Address address, String phone, String email,
            boolean active, UUID localCustomerId) { }

    public record Reservation(UUID operationId, UUID localCustomerId, Profile customer) { }

    public static final class RevisionDeserializer extends tools.jackson.databind.deser.std.StdDeserializer<Long> {
        public RevisionDeserializer() { super(Long.class); }
        @Override public Long deserialize(tools.jackson.core.JsonParser parser,
                tools.jackson.databind.DeserializationContext context) {
            if (!parser.hasToken(tools.jackson.core.JsonToken.VALUE_NUMBER_INT)) {
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            }
            return parser.getLongValue();
        }
    }
}
