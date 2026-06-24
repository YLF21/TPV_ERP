package com.tpverp.backend.party;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesRepresentativeService {

    private final SalesRepresentativeRepository representatives;
    private final SupplierRepresentativeRepository links;
    private final PartyContext context;
    private final PartyCodeAllocator codes;

    public SalesRepresentativeService(
            SalesRepresentativeRepository representatives,
            SupplierRepresentativeRepository links,
            PartyContext context,
            PartyCodeAllocator codes) {
        this.representatives = representatives;
        this.links = links;
        this.context = context;
        this.codes = codes;
    }

    @Transactional(readOnly = true)
    public List<SalesRepresentativeView> list() {
        return representatives.findByCompanyIdOrderByNombre(context.currentCompany().getId())
                .stream().map(SalesRepresentativeView::from).toList();
    }

    @Transactional(readOnly = true)
    public SalesRepresentativeView get(UUID id) {
        return SalesRepresentativeView.from(representative(id));
    }

    @Transactional
    public SalesRepresentativeView create(SalesRepresentativeCommand command) {
        var company = context.currentCompany();
        var representative = new SalesRepresentative(
                company, command.name(), command.phone(), command.email(), command.otherContact());
        representative.assignCode(codes.nextCommercial(company));
        return SalesRepresentativeView.from(representatives.save(representative));
    }

    @Transactional
    public SalesRepresentativeView update(UUID id, SalesRepresentativeCommand command) {
        SalesRepresentative representative = representative(id);
        representative.update(
                command.name(), command.phone(), command.email(), command.otherContact());
        return SalesRepresentativeView.from(representative);
    }

    @Transactional
    public void delete(UUID id) {
        SalesRepresentative representative = representative(id);
        if (links.existsByRepresentativeId(id)) {
            throw new IllegalStateException("El comercial esta relacionado con proveedores");
        }
        representatives.delete(representative);
    }

    private SalesRepresentative representative(UUID id) {
        return representatives.findByIdAndCompanyId(id, context.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Comercial no encontrado"));
    }

    public record SalesRepresentativeCommand(
            String name, String phone, String email, String otherContact) {
    }

    public record SalesRepresentativeView(
            UUID id, String codeCommercial, String name, String phone,
            String email, String otherContact) {

        static SalesRepresentativeView from(SalesRepresentative representative) {
            return new SalesRepresentativeView(
                    representative.getId(), representative.getCodeCommercial(),
                    representative.getName(),
                    representative.getPhone(), representative.getEmail(),
                    representative.getOtherContact());
        }
    }
}
