package com.tpverp.saas.loyalty;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.FeedRequest;
import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.FeedResponse;
import com.tpverp.saas.loyalty.MemberPointsOfficialApiModels.OfficialAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberPointsOfficialService {
    private static final int MAX_PAGE_SIZE = 500;

    private final SaasInstallationRepository installations;
    private final InstallationAuthenticator authenticator;
    private final SaasMemberPointsAuthorityRepository authorities;
    private final SaasMemberBalanceAccountRepository accounts;

    public MemberPointsOfficialService(
            SaasInstallationRepository installations,
            InstallationAuthenticator authenticator,
            SaasMemberPointsAuthorityRepository authorities,
            SaasMemberBalanceAccountRepository accounts) {
        this.installations = installations;
        this.authenticator = authenticator;
        this.authorities = authorities;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public FeedResponse feed(FeedRequest request, String token) {
        requireRequest(request.companyId(), request.storeId(), token);
        if (request.afterRevision() < 0) {
            throw invalid("afterRevision no puede ser negativa");
        }
        int pageSize = request.limit() <= 0
                ? MAX_PAGE_SIZE
                : Math.min(request.limit(), MAX_PAGE_SIZE);
        List<SaasMemberBalanceAccountRepository.OfficialPointsRow> rows =
                accounts.findOfficialFeed(
                        request.companyId(), request.afterRevision(), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<OfficialAccount> result = rows.stream()
                .limit(pageSize)
                .map(MemberPointsOfficialService::toAccount)
                .toList();
        long nextRevision = result.isEmpty()
                ? request.afterRevision()
                : result.get(result.size() - 1).officialRevision();
        return new FeedResponse(
                request.afterRevision(), nextRevision, hasMore, result);
    }

    @Transactional(readOnly = true)
    public OfficialAccount account(
            UUID companyId,
            UUID storeId,
            UUID memberId,
            String token) {
        requireRequest(companyId, storeId, token);
        if (memberId == null) {
            throw invalid("memberId es obligatorio");
        }
        return accounts.findOfficialAccount(companyId, memberId)
                .map(MemberPointsOfficialService::toAccount)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El ajuste central no genero una cuenta oficial"));
    }

    private void requireRequest(UUID companyId, UUID storeId, String token) {
        if (companyId == null || storeId == null) {
            throw invalid("companyId y storeId son obligatorios");
        }
        authenticator.requireLinkedInstallation(
                companyId,
                storeId,
                installations.findByCompany_IdAndStore_Id(companyId, storeId),
                token);
        if (authorities.findById(companyId).filter(SaasMemberPointsAuthority::isActive).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La autoridad central de puntos no esta activa");
        }
    }

    private static OfficialAccount toAccount(
            SaasMemberBalanceAccountRepository.OfficialPointsRow row) {
        return new OfficialAccount(
                row.getMemberId(),
                row.getPoints(),
                row.getPointsDebt(),
                row.getOfficialRevision(),
                row.getUpdatedAt());
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
