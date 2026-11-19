package com.tpverp.saas.inventory;

import com.tpverp.saas.sync.SaasSyncEvent;
import com.tpverp.saas.sync.SyncEventRequest;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class InventorySnapshotProjector {
    public static final String ENTITY_TYPE = "INVENTORY_SNAPSHOT";
    private final JdbcTemplate jdbc;

    public InventorySnapshotProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The authenticated receive transaction holds the company advisory lock. */
    public void project(SaasSyncEvent event, SyncEventRequest request, Instant now) {
        var part = InventoryChunk.validate(event, request, now);
        Object[] manifest = {
            event.getCompany().getId(),
            event.getStore().getId(),
            part.snapshotId(),
            event.getInstallation().getId(),
            Timestamp.from(part.capturedAt()),
            part.count(),
            part.products(),
            part.warehouses(),
            part.balances()
        };
        jdbc.update(
                """
                insert into saas_inventory_snapshot(company_id,store_id,snapshot_id,installation_id,captured_at,
                    chunk_count,product_count,warehouse_count,balance_count)
                values (?,?,?,?,?,?,?,?,?) on conflict(company_id,store_id,snapshot_id) do nothing
                """,
                manifest);
        var keys =
                jdbc.queryForList(
                        """
                        select id from saas_inventory_snapshot where company_id=? and store_id=? and snapshot_id=?
                          and installation_id=? and captured_at=? and chunk_count=? and product_count=? and warehouse_count=?
                          and balance_count=? for update
                        """,
                        Long.class,
                        manifest);
        if (keys.isEmpty()) throw conflict("Metadatos distintos para la misma captura");
        long key = keys.getFirst();
        if (jdbc.update(
                        """
                        insert into saas_inventory_chunk(snapshot,chunk_index,payload_hash) values (?,?,?)
                        on conflict do nothing
                        """,
                        key,
                        part.index(),
                        event.getPayloadHash())
                == 0) {
            String hash =
                    jdbc.queryForObject(
                            "select payload_hash from saas_inventory_chunk where snapshot=? and"
                                + " chunk_index=?",
                            String.class,
                            key,
                            part.index());
            if (!event.getPayloadHash().equals(hash))
                throw conflict("Contenido distinto para el mismo fragmento");
            return;
        }
        String payload = event.getPayload();
        writeRows(part.productRows(), PRODUCTS, key, payload);
        writeRows(part.warehouseRows(), WAREHOUSES, key, payload);
        writeRows(part.balanceRows(), BALANCES, key, payload);
        if (jdbc.queryForObject(
                                "select count(*) from saas_inventory_chunk where snapshot=?",
                                Long.class,
                                key)
                        == part.count()
                && jdbc.update(COMPLETE, key, key, key, Timestamp.from(now), key) != 1) {
            throw conflict("Totales o referencias de la captura incompatibles");
        }
    }

    private void writeRows(int expected, String sql, long key, String payload) {
        if (expected > 0 && jdbc.update(sql, key, payload) != expected)
            throw conflict("Clave de fila repetida en la captura");
    }

    private static ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private static final String PRODUCTS =
            """
            insert into saas_inventory_product
              (snapshot,product_id,code,name,barcode,secondary_barcode,active,product_type,family_id,family_name,subfamily_id,subfamily_name)
            select ?, "productId",code,name,barcode,"secondaryBarcode",active,"productType","familyId","familyName","subfamilyId","subfamilyName"
            from jsonb_to_recordset(cast(? as jsonb)->'products') as r("productId" uuid,code text,name text,barcode text,
              "secondaryBarcode" text,active boolean,"productType" text,"familyId" uuid,"familyName" text,"subfamilyId" uuid,"subfamilyName" text)
            on conflict do nothing
            """;
    private static final String WAREHOUSES =
            """
            insert into saas_inventory_warehouse(snapshot,warehouse_id,name,active,default_warehouse)
            select ?,"warehouseId",name,active,"defaultWarehouse" from jsonb_to_recordset(cast(? as jsonb)->'warehouses')
              as r("warehouseId" uuid,name text,active boolean,"defaultWarehouse" boolean) on conflict do nothing
            """;
    private static final String BALANCES =
            """
            insert into saas_inventory_balance(snapshot,product_id,warehouse_id,quantity)
            select ?,"productId","warehouseId",quantity from jsonb_to_recordset(cast(? as jsonb)->'balances')
              as r("productId" uuid,"warehouseId" uuid,quantity numeric) on conflict do nothing
            """;
    private static final String COMPLETE =
            """
            with p as (select count(*) n,count(*) filter(where active) active from saas_inventory_product where snapshot=?),
              w as (select count(*) n from saas_inventory_warehouse where snapshot=?),
              b as (select count(*) n,count(*) filter(where quantity>0) positive,count(*) filter(where quantity=0) zeroes,
                      count(*) filter(where quantity<0) negative,count(*) filter(where p.product_id is null or w.warehouse_id is null) missing
                    from saas_inventory_balance b
                    left join saas_inventory_product p using(snapshot,product_id)
                    left join saas_inventory_warehouse w using(snapshot,warehouse_id) where b.snapshot=?)
            update saas_inventory_snapshot s set completed_at=?,active_product_count=p.active,positive_count=b.positive,
               zero_count=b.zeroes,negative_count=b.negative from p,w,b
             where s.id=? and p.n=s.product_count and w.n=s.warehouse_count and b.n=s.balance_count and b.missing=0
            """;
}
