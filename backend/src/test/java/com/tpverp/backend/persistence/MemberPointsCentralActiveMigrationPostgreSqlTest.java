package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Exercises the real historical checks and V285 on transaction-local PostgreSQL tables. */
class MemberPointsCentralActiveMigrationPostgreSqlTest {
    private static final Path MIGRATIONS=Path.of("src/main/resources/db/migration");

    @Test
    void permitsCentralActiveAndPreservesMetadataSequenceAndRevisionGuards() throws Exception {
        String url=System.getenv("TPV_ERP_TEST_DB_URL");
        assumeTrue(url!=null&&!url.isBlank(),"PostgreSQL de pruebas no configurado");
        try (Connection connection=DriverManager.getConnection(url,System.getenv("TPV_ERP_TEST_DB_USER"),
                System.getenv("TPV_ERP_TEST_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try (var statement=connection.createStatement()) {
                statement.execute("create temporary table empresa(id uuid primary key)");
                statement.execute("create temporary table tienda(id uuid primary key)");
                statement.execute("set local search_path to pg_temp");
                String v169=Files.readString(MIGRATIONS.resolve("V169__bootstrap_puntos_multitienda.sql"));
                int start=v169.indexOf("create table member_points_projection_state (");
                int end=v169.indexOf("\n);",start)+4;
                assertThat(start).isNotNegative();
                assertThat(end).isGreaterThan(start);
                statement.execute(v169.substring(start,end).replaceFirst("create table","create temporary table"));
                statement.execute(Files.readString(MIGRATIONS.resolve("V170__autoridad_central_puntos_socio.sql")));
                statement.execute("insert into empresa values ('00000000-0000-0000-0000-000000000001')");
                statement.execute("insert into tienda values ('00000000-0000-0000-0000-000000000002')");
                statement.execute("""
                        insert into member_points_projection_state(tienda_id,empresa_id,status,last_sequence,
                            cut_sequence,projected_through_sequence,official_through_sequence,
                            bootstrap_id,snapshot_id,cutoff_at)
                        values ('00000000-0000-0000-0000-000000000002',
                            '00000000-0000-0000-0000-000000000001','WAITING_OFFICIAL',23,23,23,0,
                            '00000000-0000-0000-0000-000000000003',
                            '00000000-0000-0000-0000-000000000004','2026-10-30T18:00:00Z')
                        """);
                rejects(connection,"status='CENTRAL_ACTIVE'","ck_member_points_projection_status");
                String before=scalar(connection,"select row_to_json(s)::text from member_points_projection_state s");
                statement.execute(Files.readString(MIGRATIONS.resolve("V285__member_points_central_active_status.sql")));
                assertThat(scalar(connection,"select row_to_json(s)::text from member_points_projection_state s"))
                        .isEqualTo(before);
                statement.executeUpdate("update member_points_projection_state set status='CENTRAL_ACTIVE'");
                assertThat(scalar(connection,"select status from member_points_projection_state")).isEqualTo("CENTRAL_ACTIVE");
                rejects(connection,"status='UNKNOWN'","23514");
                rejects(connection,"snapshot_id=null","ck_member_points_projection_state_status_v170");
                rejects(connection,"bootstrap_id=null","ck_member_points_projection_state_status_v170");
                rejects(connection,"cutoff_at=null","ck_member_points_projection_state_status_v170");
                rejects(connection,"cut_sequence=null","ck_member_points_projection_state_status_v170");
                rejects(connection,"last_sequence=22","ck_member_points_projection_sequences");
                rejects(connection,"projected_through_sequence=24","ck_member_points_projection_sequences");
                rejects(connection,"official_through_sequence=24","ck_member_points_projection_sequences");
                rejects(connection,"cut_sequence=24","ck_member_points_projection_sequences");
                rejects(connection,"official_revision=-1","ck_member_points_official_revision_nonnegative_v170");
                assertThat(scalar(connection,"select status from member_points_projection_state")).isEqualTo("CENTRAL_ACTIVE");
            } finally {
                connection.rollback();
            }
        }
    }

    private static void rejects(Connection connection,String assignment,String constraint) throws Exception {
        var savepoint=connection.setSavepoint();
        try (var statement=connection.createStatement()) {
            assertThatThrownBy(()->statement.executeUpdate("update member_points_projection_state set "+assignment))
                    .isInstanceOf(SQLException.class).satisfies(failure->{
                        SQLException sql=(SQLException)failure;
                        assertThat(sql.getSQLState()).isEqualTo("23514");
                        if(!"23514".equals(constraint)) assertThat(sql.getMessage()).contains(constraint);
                    });
        } finally {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static String scalar(Connection connection,String sql) throws SQLException {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
