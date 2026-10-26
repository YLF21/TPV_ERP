package com.tpverp.saas.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.AdminAuditService;
import com.tpverp.saas.backup.CloudBackupVaultService;
import com.tpverp.saas.license.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.web.server.ResponseStatusException;

class DisasterRecoveryLicenseCredentialTest {
    private final UUID recovery=UUID.randomUUID(),installationId=UUID.randomUUID(),backup=UUID.randomUUID();
    private final String digest="a".repeat(64);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SaasInstallationRepository installations=mock(SaasInstallationRepository.class);
    private final SaasInstallation installation=mock(SaasInstallation.class);
    private final TokenHasher tokens=spy(new TokenHasher());
    private final CloudBackupVaultService vault=mock(CloudBackupVaultService.class);
    private final AdminAuditService audit=mock(AdminAuditService.class);
    private final DisasterRecoveryService service=new DisasterRecoveryService(jdbc,installations,vault,tokens,audit,new ObjectMapper(),Clock.systemUTC());
    private final ResultSet row=mock(ResultSet.class);
    private KeyPair destination;

    @BeforeEach void setup()throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);destination=generator.generateKeyPair();
        when(row.getObject("id",UUID.class)).thenReturn(recovery);when(row.getObject("installation_id",UUID.class)).thenReturn(installationId);
        when(row.getObject("backup_id",UUID.class)).thenReturn(backup);when(row.getObject("ticket_id",UUID.class)).thenReturn(UUID.randomUUID());
        when(row.getString("state")).thenReturn("ACTIVATED");when(row.getString("backup_sha256")).thenReturn(digest);
        when(row.getString("destination_public_key")).thenReturn(Base64.getEncoder().encodeToString(destination.getPublic().getEncoded()));
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(Instant.now().minusSeconds(86400)));
        when(jdbc.query(eq("select * from saas_disaster_recovery where id=?"),org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),eq(recovery)))
                .thenAnswer(invocation->List.of(((RowMapper<?>)invocation.getArgument(1)).mapRow(row,0)));
        when(installations.findByInstallationIdForUpdate(installationId)).thenReturn(Optional.of(installation));
        when(installation.isActive()).thenReturn(true);
        String activatedHash=tokens.hash("new-token");
        when(jdbc.queryForObject("select activated_token_hash from saas_disaster_recovery where id=?",String.class,recovery)).thenReturn(activatedHash);
        when(installation.hasTokenHash(activatedHash)).thenReturn(true);
    }

    private DisasterRecoveryModels.Proof proof(String action,KeyPair key)throws Exception {
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());
        signature.update(("TPV-RECOVERY-V1\n"+recovery+"\n"+action+"\n"+digest+"\n").getBytes(StandardCharsets.UTF_8));
        return new DisasterRecoveryModels.Proof(Base64.getEncoder().encodeToString(signature.sign()));
    }

    @Test void acceptsOnlyActivatedCredentialAndBoundDestinationEvenAfterGrantExpiryWithoutMutations()throws Exception {
        var proof=proof("license",destination);
        assertThat(service.requireActivatedLicenseCredential(recovery,proof,"new-token")).isSameAs(installation);
        assertThat(service.requireActivatedLicenseCredential(recovery,proof,"new-token")).isSameAs(installation);
        verify(tokens,never()).newToken();verifyNoInteractions(vault,audit);
        verify(installation,never()).validatedAt(any());verify(installations,never()).save(any());
    }

    @Test void oldOrMissingTokenCannotUseTheClosedRecovery()throws Exception {
        var proof=proof("license",destination);
        for(String token:new String[]{"old-token","",null})
            assertThatThrownBy(()->service.requireActivatedLicenseCredential(recovery,proof,token))
                    .isInstanceOf(ResponseStatusException.class).hasMessageContaining("RECOVERY_CREDENTIAL_INVALID");
        verify(tokens,never()).newToken();
    }

    @Test void wrongActionSignatureAndForeignDestinationAreRejected()throws Exception {
        assertThatThrownBy(()->service.requireActivatedLicenseCredential(recovery,proof("activate",destination),"new-token"))
                .hasMessageContaining("DESTINATION_PROOF_INVALID");
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);var other=generator.generateKeyPair();
        assertThatThrownBy(()->service.requireActivatedLicenseCredential(recovery,proof("license",other),"new-token"))
                .hasMessageContaining("DESTINATION_PROOF_INVALID");
    }

    @Test void supersededOrUnactivatedGrantCannotReconcileEvenWithACurrentInstallationToken()throws Exception {
        var proof=proof("license",destination);
        when(installation.hasTokenHash(tokens.hash("new-token"))).thenReturn(false);
        assertThatThrownBy(()->service.requireActivatedLicenseCredential(recovery,proof,"another-current-token")).hasMessageContaining("SUPERSEDED");
        when(row.getString("state")).thenReturn("ACTIVATION_APPROVED");
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
        assertThatThrownBy(()->service.requireActivatedLicenseCredential(recovery,proof,"new-token")).hasMessageContaining("NOT_ACTIVATED");
    }
}
