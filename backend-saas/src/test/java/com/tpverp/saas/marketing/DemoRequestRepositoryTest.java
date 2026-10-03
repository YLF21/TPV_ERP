package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class DemoRequestRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:15:30Z");

    @Test
    void writesAllProductsAsAPostgresArrayAlongsideTheLegacySummary() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Array products = mock(Array.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn("PostgreSQL JDBC Driver");
        when(connection.createArrayOf(eq("varchar"), any(Object[].class))).thenReturn(products);
        when(statement.executeUpdate()).thenReturn(1);
        DemoRequestRepository repository = new DemoRequestRepository(new JdbcTemplate(dataSource));

        repository.save(new DemoRequestView(UUID.randomUUID(), DemoProduct.APP_VENTA,
                List.of(DemoProduct.APP_VENTA, DemoProduct.APP_GESTION, DemoProduct.APP_PDA, DemoProduct.APP_SAAS),
                "Laura", "Mercado Centro", "laura@example.com", null, null, "es",
                null, null, null, null, null, "NEW", NOW, NOW));

        ArgumentCaptor<Object[]> arrayValues = ArgumentCaptor.forClass(Object[].class);
        verify(connection).createArrayOf(eq("varchar"), arrayValues.capture());
        assertThat(arrayValues.getValue()).containsExactly("APP_VENTA", "APP_GESTION", "APP_PDA", "APP_SAAS");
        verify(statement).setString(2, "APP_VENTA");
        verify(statement).setArray(3, products);
        verify(products).free();
    }

    @Test
    void readsTheEntireSelectionForTheAdminInboxAndReleasesTheJdbcArray() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        Array products = mock(Array.class);
        UUID id = UUID.randomUUID();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getObject("id", UUID.class)).thenReturn(id);
        when(result.getString("product")).thenReturn("APP_GESTION");
        when(result.getArray("products")).thenReturn(products);
        when(products.getArray()).thenReturn(new String[]{"APP_GESTION", "APP_SAAS"});
        when(result.getString("contact_name")).thenReturn("Laura");
        when(result.getString("company_name")).thenReturn("Mercado Centro");
        when(result.getString("email")).thenReturn("laura@example.com");
        when(result.getString("locale")).thenReturn("es");
        when(result.getString("status")).thenReturn("NEW");
        when(result.getTimestamp("privacy_accepted_at")).thenReturn(Timestamp.from(NOW));
        when(result.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
        DemoRequestRepository repository = new DemoRequestRepository(new JdbcTemplate(dataSource));

        List<DemoRequestView> inbox = repository.findLatest(100);

        assertThat(inbox).singleElement().satisfies(request -> {
            assertThat(request.id()).isEqualTo(id);
            assertThat(request.product()).isEqualTo(DemoProduct.APP_GESTION);
            assertThat(request.products()).containsExactly(DemoProduct.APP_GESTION, DemoProduct.APP_SAAS);
        });
        verify(statement).setObject(1, 100);
        verify(products).free();
    }
}
