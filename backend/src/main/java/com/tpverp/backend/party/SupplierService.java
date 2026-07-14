package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {

    private final SupplierRepository suppliers;
    private final SalesRepresentativeRepository representatives;
    private final SupplierRepresentativeRepository links;
    private final PartyContext context;
    private final PartyCodeAllocator codes;

    public SupplierService(
            SupplierRepository suppliers,
            SalesRepresentativeRepository representatives,
            SupplierRepresentativeRepository links,
            PartyContext context,
            PartyCodeAllocator codes) {
        this.suppliers = suppliers;
        this.representatives = representatives;
        this.links = links;
        this.context = context;
        this.codes = codes;
    }

    @Transactional(readOnly = true)
    public List<SupplierView> list() {
        return suppliers.findByCompanyIdOrderByDocumentNumberAsc(
                        context.currentCompany().getId())
                .stream().map(SupplierView::from).toList();
    }

    @Transactional(readOnly = true)
    public SupplierView get(UUID id) {
        return SupplierView.from(supplier(id));
    }

    @Transactional
    public SupplierView create(SupplierCommand command) {
        var company = context.currentCompany();
        ensureUnique(company.getId(), command.documentType(), command.documentNumber(), null);
        var supplier = new Supplier(
                company, command.legalName(), command.tradeName(), command.documentType(),
                command.documentNumber(), command.address(), command.phone(),
                command.email(), command.notes());
        supplier.assignCode(codes.nextSupplier(company));
        return SupplierView.from(suppliers.save(supplier));
    }

    @Transactional
    public SupplierView update(UUID id, SupplierCommand command) {
        Supplier supplier = supplier(id);
        ensureUnique(context.currentCompany().getId(), command.documentType(),
                command.documentNumber(), id);
        supplier.update(
                command.legalName(), command.tradeName(), command.documentType(),
                command.documentNumber(), command.address(), command.phone(),
                command.email(), command.notes());
        return SupplierView.from(supplier);
    }

    @Transactional
    public void deactivate(UUID id) {
        supplier(id).deactivate();
    }

    @Transactional
    public void activate(UUID id) {
        supplier(id).activate();
    }

    @Transactional
    public void delete(UUID id) {
        Supplier supplier = supplier(id);
        if (suppliers.hasHistory(id)) {
            throw new IllegalStateException("El proveedor tiene historial y no se puede eliminar");
        }
        suppliers.delete(supplier);
    }

    @Transactional
    public RepresentativeLinkView linkRepresentative(
            UUID supplierId, UUID representativeId, boolean primary) {
        Supplier supplier = supplier(supplierId);
        SalesRepresentative representative = representative(representativeId);
        SupplierRepresentative link = supplier.linkRepresentative(representative, primary);
        links.save(link);
        return RepresentativeLinkView.from(link);
    }

    @Transactional
    public void unlinkRepresentative(UUID supplierId, UUID representativeId) {
        Supplier supplier = supplier(supplierId);
        supplier.unlinkRepresentative(representativeId);
    }

    private Supplier supplier(UUID id) {
        return suppliers.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Proveedor no encontrado"));
    }

    private SalesRepresentative representative(UUID id) {
        return representatives.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comercial no encontrado"));
    }

    private void ensureUnique(
            UUID companyId, DocumentType type, String number, UUID currentId) {
        String normalized = PartyValues.document(number);
        suppliers.findByCompanyIdAndDocumentTypeAndDocumentNumber(companyId, type, normalized)
                .filter(value -> !value.getId().equals(currentId))
                .ifPresent(value -> {
                    throw new IllegalArgumentException("Ya existe ese documento de proveedor");
                });
    }

    public record SupplierCommand(
            String legalName,
            String tradeName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes) {
    }

    public record SupplierView(
            UUID id,
            String supplierId,
            String legalName,
            String tradeName,
            DocumentType documentType,
            String documentNumber,
            FiscalAddress address,
            String phone,
            String email,
            String notes,
            boolean active,
            List<RepresentativeLinkView> representatives) {

        static SupplierView from(Supplier supplier) {
            return new SupplierView(
                    supplier.getId(), supplier.getSupplierId(), supplier.getLegalName(),
                    supplier.getTradeName(),
                    supplier.getDocumentType(), supplier.getDocumentNumber(),
                    supplier.getFiscalAddress(), supplier.getPhone(), supplier.getEmail(),
                    supplier.getNotes(), supplier.isActive(),
                    supplier.getRepresentatives().stream()
                            .map(RepresentativeLinkView::from).toList());
        }
    }

    public record RepresentativeLinkView(
            UUID representativeId, String name, boolean primary) {

        static RepresentativeLinkView from(SupplierRepresentative link) {
            return new RepresentativeLinkView(
                    link.getRepresentative().getId(),
                    link.getRepresentative().getName(),
                    link.isPrimary());
        }
    }
}
