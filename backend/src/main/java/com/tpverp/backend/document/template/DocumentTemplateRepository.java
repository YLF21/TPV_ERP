package com.tpverp.backend.document.template;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

    @Query("""
            select template
            from DocumentTemplate template
            where template.store.id = :storeId
              and template.type = :type
              and template.status = com.tpverp.backend.document.template.DocumentTemplateStatus.ACTIVE
            order by template.templateVersion desc
            """)
    Optional<DocumentTemplate> findActiveForStore(UUID storeId, DocumentTemplateType type);

    @Query("""
            select template
            from DocumentTemplate template
            where template.company.id = :companyId
              and template.store is null
              and template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.COMPANY
              and template.type = :type
              and template.status = com.tpverp.backend.document.template.DocumentTemplateStatus.ACTIVE
            order by template.templateVersion desc
            """)
    Optional<DocumentTemplate> findActiveForCompany(UUID companyId, DocumentTemplateType type);

    @Query("""
            select template
            from DocumentTemplate template
            where template.company is null
              and template.store is null
              and template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.SYSTEM
              and template.type = :type
              and template.status = com.tpverp.backend.document.template.DocumentTemplateStatus.ACTIVE
            order by template.templateVersion desc
            """)
    Optional<DocumentTemplate> findActiveForSystem(DocumentTemplateType type);

    @Query("""
            select template
            from DocumentTemplate template
            where template.company is null
              and template.store is null
              and template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.SYSTEM
              and template.code = :code
              and template.templateVersion = :templateVersion
            """)
    Optional<DocumentTemplate> findSystemTemplate(
            String code, int templateVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select template
            from DocumentTemplate template
            where template.company is null
              and template.store is null
              and template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.SYSTEM
              and template.type = :type
              and template.status = com.tpverp.backend.document.template.DocumentTemplateStatus.ACTIVE
            """)
    Optional<DocumentTemplate> findActiveSystemTemplateForUpdate(
            DocumentTemplateType type);

    @Query("""
            select template
            from DocumentTemplate template
            where template.store.id = :storeId
            order by template.createdAt desc, template.code asc, template.templateVersion desc
            """)
    List<DocumentTemplate> findAllForStore(UUID storeId);

    @Query("""
            select max(template.templateVersion)
            from DocumentTemplate template
            where template.store.id = :storeId and template.code = :code
            """)
    Integer findMaxVersionForStore(UUID storeId, String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select template
            from DocumentTemplate template
            where template.id = :id
              and template.company.id = :companyId
              and template.store.id = :storeId
              and template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.STORE
            """)
    Optional<DocumentTemplate> findStoreTemplateForUpdate(
            UUID id, UUID companyId, UUID storeId);

    @Query("""
            select template
            from DocumentTemplate template
            where template.id = :id
              and template.company.id = :companyId
              and template.store.id = :storeId
              and template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.STORE
            """)
    Optional<DocumentTemplate> findStoreTemplate(
            UUID id, UUID companyId, UUID storeId);

    @Query("""
            select template
            from DocumentTemplate template
            where template.id = :id
              and (
                    (template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.STORE
                      and template.company.id = :companyId and template.store.id = :storeId)
                 or (template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.COMPANY
                      and template.company.id = :companyId and template.store is null)
                 or (template.scope = com.tpverp.backend.document.template.DocumentTemplateScope.SYSTEM
                      and template.company is null and template.store is null)
              )
            """)
    Optional<DocumentTemplate> findPrintableTemplate(
            UUID id, UUID companyId, UUID storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select template
            from DocumentTemplate template
            where template.store.id = :storeId
              and template.type = :type
              and template.status = com.tpverp.backend.document.template.DocumentTemplateStatus.ACTIVE
            """)
    Optional<DocumentTemplate> findActiveStoreTemplateForUpdate(
            UUID storeId, DocumentTemplateType type);
}
