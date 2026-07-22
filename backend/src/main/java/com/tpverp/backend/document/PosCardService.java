package com.tpverp.backend.document;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.terminal.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PosCardService {
    private final PosCashService sales; private final DocumentService documents;
    private final PosCardCheckoutCoordinator coordinator; private final PosCardTicketCreator ticketCreator;
    private final PosCardDocumentSnapshot snapshots;
    private final CardTerminalConfigurationReader configurations; private final List<CardTerminalGateway> gateways;
    private final PaymentTerminalOperationService terminalOperations;
    private final CurrentTerminal currentTerminal;
    private final PaymentMethodRepository paymentMethods; private final CurrentOrganization organization;
    @org.springframework.beans.factory.annotation.Autowired
    public PosCardService(PosCashService sales,DocumentService documents,
            PosCardCheckoutCoordinator coordinator,PosCardTicketCreator ticketCreator,PosCardDocumentSnapshot snapshots,CardTerminalConfigurationReader configurations,
            List<CardTerminalGateway> gateways,PaymentTerminalOperationService terminalOperations,CurrentTerminal currentTerminal,PaymentMethodRepository paymentMethods,CurrentOrganization organization){
        this.sales=sales;this.documents=documents;this.coordinator=coordinator;this.ticketCreator=ticketCreator;this.snapshots=snapshots;
        this.configurations=configurations;this.gateways=List.copyOf(gateways);this.terminalOperations=terminalOperations;this.currentTerminal=currentTerminal;this.paymentMethods=paymentMethods;this.organization=organization;
    }
    PosCardService(PosCashService sales,DocumentService documents,PosCardCheckoutCoordinator coordinator,PosCardTicketCreator ticketCreator,
            PosCardDocumentSnapshot snapshots,CardTerminalConfigurationReader configurations,List<CardTerminalGateway> gateways,
            CurrentTerminal currentTerminal,PaymentMethodRepository paymentMethods,CurrentOrganization organization){
        this(sales,documents,coordinator,ticketCreator,snapshots,configurations,gateways,null,currentTerminal,paymentMethods,organization);
    }

    @Transactional(readOnly=true)
    public PosCashService.Quote quote(PosCashController.SaleRequest request,Authentication authentication){return sales.quote(request,authentication);}

    public Result charge(PosCardController.CardRequest request,Authentication authentication){
        UUID terminalId=currentTerminal.terminalId(authentication); BigDecimal quoted=Money.euros(request.quotedTotal());
        String requestHash=hash(request.sale(),quoted); UUID owner=UUID.randomUUID();
        var existing=coordinator.existing(request.checkoutId(),terminalId,requestHash);
        if(existing.isPresent())return replay(existing.orElseThrow(),owner,authentication,terminalId);

        var command=sales.authoritativeCommand(request.sale(),authentication);
        var quotedTicket=request.sale().promotionalCouponCode()==null||request.sale().promotionalCouponCode().isBlank()
                ?documents.quoteTicket(command,authentication)
                :documents.quoteTicket(command,request.sale().promotionalCouponCode(),authentication); BigDecimal total=quotedTicket.getTotal();
        if(quoted.compareTo(total)!=0)throw new IllegalStateException("El total de la venta ha cambiado; vuelve a abrir el cobro");
        var configuration=configurations.required(terminalId);
        validateConfiguration(configuration);
        var gateway=gateways.stream().filter(g->g.supports(configuration.provider(),configuration.testMode())).findFirst()
                .orElseThrow(()->new IllegalStateException("No hay un conector disponible para el datafono"));

        var cardMethod=paymentMethods.findByEmpresaIdAndNombreAndActivoTrue(organization.currentCompany().getId(),"TARJETA")
                .orElseThrow(()->new IllegalStateException("El metodo TARJETA no esta activo"));
        var frozen=ApprovedCardTicketSnapshot.from(quotedTicket,cardMethod.getId()); String snapshot=snapshots.serialize(frozen);
        if(!(authentication.getPrincipal() instanceof com.tpverp.backend.security.domain.UserAccount user))
            throw new IllegalStateException("No se puede identificar de forma segura al usuario del cobro");
        var store=organization.currentStore();var company=organization.currentCompany();
        if(!configuration.storeId().equals(store.getId()))throw new IllegalStateException("La configuracion pertenece a otra tienda");
        var identity=new PosCardCheckoutCoordinator.RecoveryIdentity(user.getId(),store.getId(),company.getId(),user.getUserName());
        var reservation=coordinator.reserve(request.checkoutId(),terminalId,requestHash,snapshot,total,owner,identity);
        var checkout=reservation.checkout();
        if(!reservation.acquired())return replay(checkout,owner,authentication,terminalId);

        CardTerminalResult terminalResult;
        if(terminalOperations!=null){
            var normalized=terminalOperations.charge(request.checkoutId(),requestHash,total,configuration);
            terminalResult=new CardTerminalResult(normalized.status(),normalized.reference(),normalized.authorization(),normalized.message());
        } else try { terminalResult=gateway.charge(new CardTerminalRequest(request.checkoutId(),terminalId,
                configuration.provider(),total,configuration.testMode()),configuration); }
        catch(RuntimeException ex){terminalResult=new CardTerminalResult(PaymentTerminalOperationStatus.TIMEOUT,null,null,"Resultado incierto del datafono; revise el terminal");}
        checkout=coordinator.recordResult(checkout.getId(),owner,terminalResult);
        return resume(checkout,owner,frozen,authentication,terminalId);
    }

    private Result replay(PosCardCheckout checkout,UUID owner,Authentication authentication,UUID terminalId){
        if(terminalOperations!=null && checkout.getStatus()!=PaymentTerminalOperationStatus.APPROVED){
            var recovered=terminalOperations.find(checkout.getId());
            if(recovered.isPresent()&&recovered.orElseThrow().getStatus()==PaymentTerminalOperationStatus.APPROVED){var operation=recovered.orElseThrow();
                checkout=coordinator.recoverApproved(checkout.getId(),operation.getExternalReference(),operation.getAuthorizationCode(),"Pago recuperado por consulta al datafono");}
        }
        if(checkout.getStatus()!=PaymentTerminalOperationStatus.APPROVED||checkout.getDocumentId()!=null)return checkout.toResult();
        try{return resume(checkout,owner,snapshots.deserialize(checkout.getDocumentSnapshot()),authentication,terminalId);}
        catch(ApprovedCardSnapshotException ex){var diagnostic="La instantanea aprobada esta corrupta; se requiere revision manual";
            coordinator.diagnostic(checkout.getId(),diagnostic);return new Result(PaymentTerminalOperationStatus.ERROR,null,null,
                    checkout.getTotal(),null,null,diagnostic);}
    }

    private Result resume(PosCardCheckout checkout,UUID owner,ApprovedCardTicketSnapshot frozen,Authentication authentication,UUID terminalId){
        if(checkout.getStatus()!=PaymentTerminalOperationStatus.APPROVED||checkout.getDocumentId()!=null)return checkout.toResult();
        if(!coordinator.claimTicket(checkout.getId(),owner))return new Result(PaymentTerminalOperationStatus.PENDING,null,null,
                checkout.getTotal(),null,null,"El ticket aprobado se esta registrando");
        try {
            return ticketCreator.create(checkout.getId(),frozen,authentication,terminalId).toResult();
        } catch(RuntimeException ex){coordinator.releaseTicket(checkout.getId(),owner);throw ex;}
    }

    private static void validateConfiguration(CardTerminalConfiguration c){
        if(!c.enabled())throw new IllegalStateException("El datafono esta desactivado");
        if(c.mode()!=PaymentCardMode.INTEGRATED)throw new IllegalStateException("El datafono no esta en modo integrado");
        if(c.provider()==PaymentTerminalProvider.NONE)throw new IllegalStateException("No hay proveedor de datafono configurado");
    }
    private static String hash(PosCashController.SaleRequest sale,BigDecimal total){
        var coupon=sale.promotionalCouponCode()==null?"":sale.promotionalCouponCode().trim();
        var c=new StringBuilder(coupon.isEmpty()?"v1|":"v2-coupon|").append(sale.customerId()).append('|');
        if(!coupon.isEmpty())c.append(coupon).append('|');
        c.append(Money.euros(total));
        sale.lines().forEach(l->c.append('|').append(l.productId()).append(':').append(l.quantity().stripTrailingZeros().toPlainString())
                .append(':').append(l.discount().stripTrailingZeros().toPlainString()));
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public record Result(PaymentTerminalOperationStatus status,UUID ticketId,String ticketNumber,BigDecimal total,String reference,String authorization,String message){}
}
