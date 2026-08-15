package com.tpverp.backend.document.template;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTemplatePresentationSettingRepository extends JpaRepository<
        DocumentTemplatePresentationSetting, DocumentTemplatePresentationSetting.Key> {

    Optional<DocumentTemplatePresentationSetting> findByStoreIdAndTypeAndFormat(
            UUID storeId, DocumentTemplateType type, DocumentTemplateFormat format);
}
