package com.tpverp.saas.document;

import static com.tpverp.saas.SaasTestData.validCif;
import static com.tpverp.saas.document.ProductSalesHistoryApi.*;
import static com.tpverp.saas.document.ProductSalesHistoryQuery.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.document.CommercialDocumentQuery.Scope;
import com.tpverp.saas.license.*;
import com.tpverp.saas.sync.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** Synthetic fixtures only, in an isolated schema; exercises actual PostgreSQL keysets and aggregates. */
@SpringBootTest(properties = {"spring.flyway.default-schema=product_sales_history_test",
        "spring.datasource.hikari.schema=product_sales_history_test",
        "spring.jpa.properties.hibernate.default_schema=product_sales_history_test"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductSalesHistoryPostgreSqlTest {
    private static final AtomicInteger COMPANY_NUMBER = new AtomicInteger(9400000);
    @Autowired ProductSalesHistoryService service;
    @MockitoSpyBean ProductSalesHistoryRepository repository;
    @Autowired SyncEventService sync;
    @Autowired SaasCompanyRepository companies;
    @Autowired SaasStoreRepository stores;
    @Autowired SaasLicenseRepository licenses;
    @Autowired SaasInstallationRepository installations;
    @Autowired TokenHasher tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;

    @BeforeEach void isolatedSchema() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo("product_sales_history_test");
    }

    @Test void authenticationScopesAllStoresToVerifiedCompanyAndPreservesExactCodes() throws Exception {
        Site first = site(); Site second = site(first.company(), "002"); Site foreign = site();
        publish(first, document("A", "TICKET", "PAGADO", "EUR", List.of(line(1,"0012","1","8.123456"))));
        publish(second, document("B", "TICKET", "PAGADO", "EUR", List.of(line(1,"0012","2","16.246912"))));
        publish(first, document("OTHER", "TICKET", "PAGADO", "EUR", List.of(line(1,"12","9","72"))));
        publish(foreign, document("FOREIGN", "TICKET", "PAGADO", "EUR", List.of(line(1,"0012","99","999"))));
        Request request = request(first,"0012",null,null,null,null,null,1,null,null);
        Response response = service.page(request,first.token());
        assertThat(response.items()).hasSize(1);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.stores()).extracting(Store::id).containsExactlyInAnyOrder(first.store().getId(),second.store().getId());
        assertThat(response.totals()).singleElement().satisfies(total -> {
            assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("3");
            assertThat(new BigDecimal(total.netAmount())).isEqualByComparingTo("24.370368");
        });
        assertThat(response.comparison()).extracting(Comparison::storeId).containsExactly(second.store().getId(),first.store().getId());
        assertThat(response.incompleteDocuments()).isZero();
        mvc.perform(post("/api/v1/product-sales-history/page").contentType(MediaType.APPLICATION_JSON)
                .header("X-TPV-Installation-Token",first.token()).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.items[0].quantity").isString());
        mvc.perform(post("/api/v1/product-sales-history/page").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/product-sales-history/page").contentType(MediaType.APPLICATION_JSON)
                .header("X-TPV-Installation-Token",foreign.token()).content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isUnauthorized());
        assertThat(service.page(request(first,"0012",null,null,null,List.of(foreign.store().getId()),null,200,null,null),first.token()).items()).isEmpty();
    }

    @Test void conversionCancellationReturnsAndCompensationRespectReceivedDocumentSemantics() {
        Site site=site();
        UUID origin=publish(site,document("T-ORIGIN","TICKET","PAGADO","EUR",List.of(line(1,"P","2","16.40"))));
        var invoice=document("INVOICE","FACTURA_VENTA","PAGADO","EUR",List.of(line(1,"P","2","16.40")));
        invoice.put("relaciones",List.of(Map.of("tipo","FACTURA_DE","origenId",origin.toString())));
        publish(site,invoice);
        UUID refund=publish(site,document("RETURN","RECTIFICATIVA_VENTA","PAGADO","EUR",List.of(line(1,"P","-1","-8.20"))));
        var exchange=document("EXCHANGE","TICKET","PAGADO","EUR",List.of(line(1,"P","1","8.20")));
        exchange.put("relaciones",List.of(Map.of("tipo","COMPENSA","origenId",refund.toString()))); publish(site,exchange);
        publish(site,document("CANCELLED","TICKET","ANULADO","EUR",List.of(line(1,"P","90","738"))));
        var adjustment=line(1,"P","100","999"); adjustment.put("tipoLinea","MANUAL_DISCOUNT");
        publish(site,document("ADJUSTMENT","TICKET","PAGADO","EUR",List.of(adjustment)));
        publish(site,document("USD","TICKET","PAGADO","USD",List.of(line(1,"P","3","5"))));
        Response response=service.page(request(site,"P",null,null,null,null,null,200,null,null),site.token());
        assertThat(response.items()).hasSize(6);
        assertThat(response.items().stream().filter(item -> !item.countsAsSale()).map(Item::documentNumber)).containsExactlyInAnyOrder("T-ORIGIN","CANCELLED");
        assertThat(response.totals()).filteredOn(total -> total.currency().equals("EUR")).singleElement().satisfies(total -> {
            assertThat(new BigDecimal(total.quantitySold())).isEqualByComparingTo("3");
            assertThat(new BigDecimal(total.quantityReturned())).isEqualByComparingTo("1");
            assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("2");
            assertThat(new BigDecimal(total.netAmount())).isEqualByComparingTo("16.40");
        });
    }

    @Test void economicRectificationsChangeAmountsWithoutInventingSoldOrReturnedUnits() {
        Site site = site();
        UUID invoice = publish(site, document("SALE", "FACTURA_VENTA", "PAGADO", "EUR",
                List.of(line(1, "P", "5", "50"))));
        for (var specification : List.of(List.of("DISCOUNT", "-1", "-4", "DIFERENCIA"),
                List.of("PRICE_INCREASE", "2", "2", "DIFERENCIA"),
                List.of("PHYSICAL_RETURN", "-2", "-20", "PVP"))) {
            var adjustment = line(1, "P", specification.get(1), specification.get(2));
            adjustment.put("tarifa", specification.get(3));
            var rectification = document(specification.get(0), "RECTIFICATIVA_VENTA", "PAGADO", "EUR", List.of(adjustment));
            rectification.put("relaciones", List.of(Map.of("tipo", "RECTIFICA", "origenId", invoice.toString())));
            publish(site, rectification);
        }
        // A normal sale is still a sale even if its tariff happens to have the same name.
        var normalSale = line(1, "P", "3", "30");
        normalSale.put("tarifa", "DIFERENCIA");
        publish(site, document("NORMAL_SALE", "FACTURA_VENTA", "PAGADO", "EUR", List.of(normalSale)));

        Request request = request(site, "P", null, null, null, null, null, 200, null, null);
        Response response = service.page(request, site.token());
        assertThat(response.items()).hasSize(5).allSatisfy(item -> assertThat(item.countsAsSale()).isTrue());
        assertThat(response.items()).filteredOn(item -> item.documentNumber().equals("DISCOUNT")).singleElement()
                .satisfies(item -> {
                    assertThat(new BigDecimal(item.quantity())).isEqualByComparingTo("-1");
                    assertThat(new BigDecimal(item.lineTotal())).isEqualByComparingTo("-4");
                });
        assertThat(response.items()).filteredOn(item -> item.documentNumber().equals("PRICE_INCREASE")).singleElement()
                .satisfies(item -> assertThat(new BigDecimal(item.quantity())).isEqualByComparingTo("2"));
        assertThat(response.totals()).singleElement().satisfies(total -> {
            assertThat(new BigDecimal(total.quantitySold())).isEqualByComparingTo("8");
            assertThat(new BigDecimal(total.quantityReturned())).isEqualByComparingTo("2");
            assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("6");
            assertThat(new BigDecimal(total.netAmount())).isEqualByComparingTo("58");
        });
        assertThat(response.comparison()).singleElement().satisfies(total -> {
            assertThat(new BigDecimal(total.quantitySold())).isEqualByComparingTo("8");
            assertThat(new BigDecimal(total.quantityReturned())).isEqualByComparingTo("2");
            assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("6");
            assertThat(new BigDecimal(total.netAmount())).isEqualByComparingTo("58");
        });
        Response exported = service.export(request, site.token());
        assertThat(exported.items()).isEqualTo(response.items());
        assertThat(exported.totals()).isEqualTo(response.totals());
        assertThat(exported.comparison()).isEqualTo(response.comparison());
    }

    @Test void invoiceOutsideDateAndStatusFilterStillReplacesOriginButCancelledInvoiceDoesNot() {
        Site site=site();
        UUID first=publish(site,document("ORIGIN","ALBARAN_VENTA","PENDIENTE","EUR",List.of(line(1,"P","2","16.40"))));
        UUID second=publish(site,document("KEPT","TICKET","PENDIENTE","EUR",List.of(line(1,"P","1","8.20"))));
        for (UUID id:List.of(first,second)) {
            var invoice=document("F-"+id.toString().substring(0,8),"FACTURA_VENTA",id.equals(first)?"PAGADO":"ANULADO","EUR",List.of(line(1,"P","2","16.40")));
            invoice.put("fecha","2026-10-01"); invoice.put("relaciones",List.of(Map.of("tipo","FACTURA_DE","origenId",id.toString()))); publish(site,invoice);
        }
        Response response=service.page(request(site,"P",LocalDate.parse("2026-09-19"),LocalDate.parse("2026-09-19"),"PENDIENTE",null,null,200,null,null),site.token());
        assertThat(response.items()).hasSize(2);
        assertThat(response.totals()).singleElement().satisfies(total -> assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("1"));
    }

    @ParameterizedTest @EnumSource(Field.class)
    void everySortKeyTraversesTiesInBothDirectionsWithoutMissingOrDuplicatingLines(Field field) {
        Site first=site(); Site second=site(first.company(),"002");
        for (int i=0;i<3;i++) {
            var document=document("DOC"+i,"TICKET","PAGADO","EUR",List.of(line(1,"P","1","8.20"),line(2,"P","2","16.40")));
            document.put("usuarioNombre", i%2==0?"User A":"User B");
            publish(i==1?second:first,document);
        }
        for (String direction:List.of("asc","desc")) {
            String cursor=null; var keys=new HashSet<String>(); int pageCount=0;
            do {
                Response page=service.page(request(first,"P",null,null,null,null,field.key,2,cursor,direction),first.token());
                for (Item row:page.items()) assertThat(keys.add(row.storeId()+"/"+row.documentId()+"/"+row.linePosition())).isTrue();
                assertThat(page.totals()).singleElement().satisfies(total -> assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("9"));
                cursor=page.nextCursor(); pageCount++;
                assertThat(pageCount).isLessThanOrEqualTo(3);
            } while(cursor!=null);
            assertThat(keys).hasSize(6);
        }
    }

    @Test void cursorsRejectChangedProductCompanyStoresPeriodStatusAndOrder() {
        Site site=site(); Site foreign=site();
        publish(site,document("A","TICKET","PAGADO","EUR",List.of(line(1,"P","1","1"),line(2,"P","2","2"))));
        String cursor=service.page(request(site,"P",null,null,null,null,null,1,null,null),site.token()).nextCursor();
        for(Request changed:List.of(request(site,"P2",null,null,null,null,null,1,cursor,null),
                request(site,"P",LocalDate.parse("2026-09-19"),null,null,null,null,1,cursor,null),
                request(site,"P",null,null,"PAGADO",null,null,1,cursor,null),
                request(site,"P",null,null,null,List.of(site.store().getId()),null,1,cursor,null),
                request(site,"P",null,null,null,null,"quantity",1,cursor,null),
                request(site,"P",null,null,null,null,null,1,cursor,"asc"))) {
            assertThatThrownBy(() -> service.page(changed,site.token())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.page(request(foreign,"P",null,null,null,null,null,1,cursor,null),foreign.token()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void incompleteCoverageIsExplicitAndRestrictedToRequestedCompanyStoresAndPeriod() {
        Site first=site(); Site second=site(first.company(),"002");
        var missing=document("MISSING","TICKET","PAGADO","EUR",List.of()); missing.remove("lineas"); publish(first,missing);
        var legacy=document("LEGACY","TICKET","PAGADO","EUR",List.of(line(1,"P","1","2"))); legacy.remove("relaciones"); publish(first,legacy);
        var unresolved=document("UNRESOLVED","RECTIFICATIVA_VENTA","PAGADO","EUR",List.of(line(1,"P","-1","-2")));
        unresolved.put("relaciones",List.of(Map.of("tipo","RECTIFICA","origenId",UUID.randomUUID().toString()))); publish(first,unresolved);
        publish(second,missing);
        Response response=service.page(request(first,"P",null,null,null,List.of(first.store().getId()),null,200,null,null),first.token());
        assertThat(response.incompleteDocuments()).isEqualTo(3);
        assertThat(response.items()).hasSize(2);
        assertThat(response.receivedAt()).isNotNull();
        assertThat(service.page(request(first,"P",LocalDate.parse("2027-01-01"),null,null,null,null,200,null,null),first.token()).incompleteDocuments()).isZero();
    }

    @Test void exportKeepsItsSnapshotAcrossBatchesAndComparisonExportDoesNotReadDetails() throws Exception {
        Site site=site(); var lines=new ArrayList<Map<String,Object>>();
        for(int i=1;i<=201;i++) lines.add(line(i,"P","1","1"));
        UUID id=publish(site,document("MANY","TICKET","PAGADO","EUR",lines));
        var calls=new AtomicInteger();
        doAnswer(invocation -> {
            Object result=invocation.callRealMethod();
            if(calls.getAndIncrement()==0) CompletableFuture.runAsync(() -> jdbc.update(
                    "update saas_commercial_document_line set quantity=99,line_total=99 where company_id=? and source_document_id=? and line_position=201",
                    site.company().getId(),id)).get(10,TimeUnit.SECONDS);
            return result;
        }).when(repository).page(any(),any(),any(),any(),anyInt());
        Request request=request(site,"P",null,null,null,null,null,200,null,null);
        Response result=service.export(request,site.token());
        assertThat(result.items()).hasSize(201).allSatisfy(item -> assertThat(new BigDecimal(item.quantity())).isEqualByComparingTo("1"));
        assertThat(result.totals()).singleElement().satisfies(total -> assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("201"));
        int detailCalls=calls.get();
        Response comparison=service.export(new Request(request.companyId(),request.storeId(),"P",null,null,null,null,null,null,null,null,"comparison"),site.token());
        assertThat(comparison.items()).isEmpty();
        assertThat(comparison.hasMore()).isFalse();
        assertThat(calls.get()).isEqualTo(detailCalls);
        assertThat(comparison.totals()).singleElement().satisfies(total -> assertThat(new BigDecimal(total.netQuantity())).isEqualByComparingTo("299"));
    }

    private Request request(Site site,String code,LocalDate from,LocalDate to,String status,List<UUID> stores,String order,Integer size,String cursor,String direction) {
        return new Request(site.company().getId(),site.store().getId(),code,from,to,status,stores,order,direction,size,cursor,null);
    }
    private static Map<String,Object> line(int position,String code,String quantity,String total) {
        var line=new LinkedHashMap<String,Object>();
        line.put("posicion",position);line.put("tipoLinea","PRODUCT");line.put("codigo",code);line.put("nombre","Historical product");
        line.put("cantidad",quantity);line.put("precioUnitario","8.20");line.put("descuento","0");line.put("total",total);return line;
    }
    private static Map<String,Object> document(String number,String type,String status,String currency,List<Map<String,Object>> lines) {
        var data=new LinkedHashMap<String,Object>();data.put("schemaVersion",2);data.put("sourceRevision",1);
        data.put("tipo",type);data.put("estado",status);data.put("numero",number);data.put("fecha","2026-09-19");
        data.put("subtotal","10");data.put("impuestos","2.10");data.put("total","12.10");data.put("moneda",currency);
        data.put("lineas",lines);data.put("relaciones",List.of());return data;
    }
    private UUID publish(Site site,Map<String,Object> data) {
        UUID id=UUID.randomUUID();sync.receive(new SyncEventRequest(UUID.randomUUID(),site.company().getId(),site.store().getId(),null,"DOCUMENTO",id,
                "ANULADO".equals(data.get("estado"))?SyncOperation.ANULAR:SyncOperation.ACTUALIZAR,data),site.token());return id;
    }
    private Site site() {
        return site(companies.saveAndFlush(new SaasCompany(UUID.randomUUID(),"History test company",validCif("B"+COMPANY_NUMBER.getAndIncrement()+"0"),
                TaxpayerType.SOCIEDAD,TaxRegime.IVA,Instant.now())),"001");
    }
    private Site site(SaasCompany company,String code) {
        SaasStore store=stores.saveAndFlush(new SaasStore(UUID.randomUUID(),company,code,"History store "+code,"Atlantic/Canary",Instant.now()));
        SaasLicense license=licenses.saveAndFlush(new SaasLicense(UUID.randomUUID(),company,"HISTORY-"+UUID.randomUUID(),Instant.now().plusSeconds(86400),1,1,Instant.now()));
        String token=tokens.newToken();
        SaasInstallation installation=installations.saveAndFlush(new SaasInstallation(UUID.randomUUID(),company,store,license,UUID.randomUUID(),"HISTORY",null,tokens.hash(token),Instant.now()));
        return new Site(company,store,installation,token);
    }
    private record Site(SaasCompany company,SaasStore store,SaasInstallation installation,String token) { }
}
