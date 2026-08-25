package com.tpverp.backend.pdawork;

import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PdaWorkService {
    private final PdaWorkRepository repository;
    private final CurrentOrganization organization;
    private final Clock clock;
    public PdaWorkService(PdaWorkRepository repository, CurrentOrganization organization, Clock clock) {
        this.repository=repository; this.organization=organization; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public List<PdaWorkView> list(PdaWorkType type, PdaWorkStatus status) {
        var storeId=organization.currentStore().getId();
        var values=status==null?repository.findByStoreIdOrderByCreatedAtDesc(storeId):repository.findByStoreIdAndStatusOrderByCreatedAtDesc(storeId,status);
        return values.stream().filter(value->type==null||value.getType()==type).map(PdaWorkView::from).toList();
    }
    @Transactional
    public PdaWorkView create(CreateCommand command, Authentication authentication) {
        if(command.evidenceData()!=null&&command.evidenceData().length()>1_500_000) throw new IllegalArgumentException("La evidencia supera el tamaño permitido");
        if(command.quantity()!=null&&command.quantity().signum()<0) throw new IllegalArgumentException("La cantidad no puede ser negativa");
        var user=organization.currentUser(authentication);
        return PdaWorkView.from(repository.save(new PdaWorkItem(organization.currentStore().getId(),command.type(),command.title(),command.reference(),
                command.productCode(),command.warehouseId(),command.quantity(),command.lotNumber(),command.expiryDate(),command.location(),command.priority(),
                command.notes(),command.evidenceName(),command.evidenceType(),command.evidenceData(),user.getId(),Instant.now(clock))));
    }
    @Transactional public PdaWorkView finish(UUID id,Authentication auth){var value=find(id);value.finish(organization.currentUser(auth).getId(),Instant.now(clock));return PdaWorkView.from(repository.save(value));}
    @Transactional public PdaWorkView cancel(UUID id,Authentication auth){var value=find(id);value.cancel(organization.currentUser(auth).getId(),Instant.now(clock));return PdaWorkView.from(repository.save(value));}
    private PdaWorkItem find(UUID id){return repository.findByIdAndStoreId(id,organization.currentStore().getId()).orElseThrow(()->new IllegalArgumentException("Operación PDA no encontrada"));}
    public record CreateCommand(PdaWorkType type,String title,String reference,String productCode,UUID warehouseId,BigDecimal quantity,
            String lotNumber,LocalDate expiryDate,String location,String priority,String notes,String evidenceName,String evidenceType,String evidenceData){}
}