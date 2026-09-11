package com.tpverp.backend.party;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class CustomerAdoptionApi {
    private CustomerAdoptionApi() { }

    public record Lookup(@NotNull DocumentType documentType, @NotBlank @Size(max = 64) String documentNumber) { }
    public record Adopt(@NotNull UUID customerId, @NotNull @Min(0)
            @tools.jackson.databind.annotation.JsonDeserialize(using = RevisionDeserializer.class) Long expectedRevision,
            @NotNull DocumentType documentType, @NotBlank @Size(max = 64) String documentNumber) { }
    public record Address(String address, String postalCode, String city, String province, String country) {
        FiscalAddress local() { return new FiscalAddress(address, postalCode, city, province, country); }
    }
    public record Profile(UUID customerId, Long revision, String centralCode, String fiscalName,
            DocumentType documentType, String documentNumber, Address address, String phone,
            String email, Boolean active, UUID localCustomerId) {
        Profile withLocalCustomerId(UUID id) {
            return new Profile(customerId, revision, centralCode, fiscalName, documentType,
                    documentNumber, address, phone, email, active, id);
        }
    }
    public record Reservation(UUID operationId, UUID localCustomerId, Profile customer) { }
    public record Adopted(CustomerService.CustomerView customer, String centralLinkStatus) { }

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
