package com.tpverp.backend.management;

import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;

/** The small, stable row contract for the management directories. */
public record ManagementItem(
        UUID id,
        long version,
        String code,
        String name,
        String clientId,
        String fiscalName,
        String supplierId,
        String legalName,
        String tradeName,
        String commercialId,
        String documentType,
        String documentNumber,
        Object address,
        String phone,
        String email,
        String notes,
        String otherContact,
        boolean active,
        boolean isMember,
        String numMember,
        BigDecimal discount,
        UUID memberUuid,
        LocalDate memberSince,
        LocalDate birthday,
        String gender,
        boolean commercialConsent,
        UUID preferredCommercialChannelId,
        boolean creditEnabled,
        BigDecimal creditLimit,
        Integer paymentTermDays,
        boolean creditBlocked,
        boolean blockOnOverdue,
        List<RepresentativeLink> representatives,
        List<SupplierLink> suppliers) {

    public record RepresentativeLink(UUID representativeId, String name, boolean primary) {
    }

    public record SupplierLink(UUID supplierId, String supplierCode, String supplierName, boolean primary) {
    }

    public ManagementItem withSuppliers(List<SupplierLink> links) {
        return new ManagementItem(id, version, code, name, clientId, fiscalName, supplierId,
                legalName, tradeName, commercialId, documentType, documentNumber, address,
                phone, email, notes, otherContact, active, isMember, numMember,
                discount, memberUuid, memberSince, birthday, gender, commercialConsent,
                preferredCommercialChannelId, creditEnabled, creditLimit, paymentTermDays,
                creditBlocked, blockOnOverdue,
                representatives, links);
    }
}
