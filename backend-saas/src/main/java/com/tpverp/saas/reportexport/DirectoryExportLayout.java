package com.tpverp.saas.reportexport;

import com.tpverp.saas.admin.CompanySummaryResponse;
import com.tpverp.saas.stores.StoreWorkspaceApi.LicenseRow;
import com.tpverp.saas.stores.StoreWorkspaceApi.StoreRow;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

record DirectoryExportLayout(String language, Instant now) {
    static final List<String> COMPANIES = List.of("companyName", "taxId", "storeCount", "licenseCount",
            "contactName", "contactPhone", "contactEmail", "owners", "taxpayerType", "city", "province", "createdAt");
    static final List<String> STORES = List.of("code", "name", "internalCode", "companyName", "active",
            "taxRegime", "commercialProfile", "servicePrice", "billingPeriod", "validUntil",
            "maxWindows", "maxPda", "activeInstallations", "lastSyncAt");
    static final List<String> LICENSES = List.of("reference", "status", "companyName", "storeCode",
            "validUntil", "days", "lastValidatedAt", "lastSyncAt", "maxWindows", "maxPda",
            "activeInstallations", "billingStatus", "debt");

    List<?> company(CompanySummaryResponse row) {
        var address = row.companyAddress();
        return Arrays.asList(row.companyName(), row.taxId(), row.storeCount(), row.licenseCount(), row.contactName(), row.contactPhone(),
                row.contactEmail(), row.owners().isEmpty() ? null : row.owners().getFirst().name(),
                label(row.taxpayerType().name()), address == null ? null : address.get("ciudad"),
                address == null ? null : address.get("provincia"), row.createdAt());
    }

    List<?> store(StoreRow row) {
        return Arrays.asList(row.code(), row.name(), row.internalCode(), row.companyName(),
                label(row.active() ? "ACTIVE_STORE" : "INACTIVE_STORE"), row.taxRegime(),
                row.commercialProfile() == null ? null : label(row.commercialProfile().name()),
                row.servicePrice() == null ? null : row.servicePrice().toPlainString(),
                row.billingPeriod() == null ? null : label(row.billingPeriod().name()), row.validUntil(),
                row.maxWindows(), row.maxPda(), row.activeInstallations(), row.lastSyncAt());
    }

    List<?> license(LicenseRow row) {
        String stores = row.stores().stream()
                .map(store -> storeDisplay(store.code(), store.internalCode(), store.name()))
                .collect(Collectors.joining(" / "));
        String debt = row.companyDebt().stream().map(item -> item.outstanding().toPlainString() + " " + item.currency())
                .collect(Collectors.joining(" / "));
        Long days = row.validUntil() == null ? null
                : Math.ceilDiv(row.validUntil().toEpochMilli() - now.toEpochMilli(), 86_400_000L);
        return Arrays.asList(row.reference(), label(row.status()), row.companyName() + " · " + row.taxId(),
                stores.isEmpty() ? null : stores, row.validUntil(), days, row.lastValidatedAt(), row.lastSyncAt(),
                row.maxWindows(), row.maxPda(), row.activeInstallations(), label(row.companyBillingStatus()),
                debt.isEmpty() ? label("noDebt") : debt);
    }

    static String storeDisplay(String code, String internalCode, String name) {
        return (internalCode == null ? code : internalCode) + " · " + name;
    }

    String tr(String es, String en, String zh) {
        return ReportExportLayout.tr(language, es, en, zh);
    }

    String label(String key) {
        if (key == null) return null;
        return switch (key) {
            case "companies" -> tr("Empresas", "Companies", "企业");
            case "taxId" -> tr("NIF", "Tax ID", "税号");
            case "contactName" -> tr("Nombre de contacto", "Contact name", "联系人");
            case "contactPhone" -> tr("Teléfono", "Phone", "电话");
            case "contactEmail" -> tr("Correo de contacto", "Contact email", "联系邮箱");
            case "owners" -> tr("Primer propietario", "First owner", "第一位所有者");
            case "owner" -> tr("Propietario", "Owner", "所有者");
            case "taxpayerType" -> tr("Tipo de contribuyente", "Taxpayer type", "纳税人类型");
            case "city" -> tr("Ciudad", "City", "城市");
            case "postalCode" -> tr("Código postal", "Postal code", "邮政编码");
            case "createdAt" -> tr("Fecha de alta (UTC)", "Created (UTC)", "创建时间 (UTC)");
            case "storeCount" -> label("stores");
            case "licenseCount" -> label("licenses");
            case "dateFrom" -> tr("Alta desde (UTC)", "Created from (UTC)", "创建开始日期 (UTC)");
            case "dateTo" -> tr("Alta hasta (UTC)", "Created through (UTC)", "创建截止日期 (UTC)");
            case "internalCode" -> tr("Código interno", "Internal code", "内部编号");
            case "code" -> tr("Código local", "Local code", "本地编号");
            case "name" -> label("storeName");
            case "active" -> label("status");
            case "taxRegime" -> tr("Impuestos", "Taxes", "税务制度");
            case "commercialProfile" -> tr("Perfil comercial", "Commercial profile", "经营类型");
            case "commercialConfiguration" -> tr("Configuración comercial", "Commercial configuration", "经营配置");
            case "pending" -> tr("Pendiente", "Pending", "待配置");
            case "complete" -> tr("Completa", "Complete", "已配置");
            case "servicePrice" -> tr("Precio del servicio (EUR)", "Service price (EUR)", "服务价格 (EUR)");
            case "billingPeriod" -> tr("Periodicidad", "Billing period", "计费周期");
            case "validUntil" -> tr("Caducidad (UTC)", "Expiry (UTC)", "到期时间 (UTC)");
            case "maxWindows" -> "Windows";
            case "maxPda" -> "PDA";
            case "activeInstallations" -> tr("Instalaciones activas", "Active installations", "有效安装");
            case "lastSyncAt" -> tr("Última sincronización (UTC)", "Last synchronization (UTC)", "最后同步 (UTC)");
            case "reference" -> tr("Referencia", "Reference", "编号");
            case "storeCode" -> label("stores");
            case "days" -> tr("Días restantes", "Days remaining", "剩余天数");
            case "lastValidatedAt" -> tr("Última validación (UTC)", "Last validation (UTC)", "最后验证 (UTC)");
            case "billingStatus" -> tr("Facturación de la empresa", "Company billing", "企业账单");
            case "debt" -> tr("Saldo pendiente por moneda", "Outstanding balance by currency", "各币种待付余额");
            case "noDebt" -> tr("Sin saldo pendiente", "No outstanding balance", "无待付余额");
            case "expiresBefore" -> tr("Caduca antes de (UTC)", "Expires before (UTC)", "在此日期前到期 (UTC)");
            case "hasConnections" -> tr("Con instalaciones activas", "With active installations", "有有效安装");
            case "query", "q" -> label("search");
            case "SOCIEDAD" -> tr("Sociedad", "Company", "公司");
            case "AUTONOMO" -> tr("Autónomo", "Self-employed", "个体经营者");
            case "ACTIVE_STORE" -> tr("Activa", "Active", "启用");
            case "INACTIVE_STORE" -> tr("Inactiva", "Inactive", "停用");
            case "MINORISTA" -> tr("Minorista", "Retail", "零售");
            case "MAYORISTA" -> tr("Mayorista", "Wholesale", "批发");
            case "MONTHLY" -> tr("Mensual", "Monthly", "每月");
            case "ANNUAL" -> tr("Anual", "Annual", "每年");
            case "VALIDA" -> tr("Válida", "Valid", "有效");
            case "CADUCADA" -> tr("Caducada", "Expired", "已过期");
            case "BLOQUEADA_MANUAL" -> tr("Bloqueada", "Blocked", "已锁定");
            case "VENCIDO", "IMPAGADO" -> tr("Vencido", "Overdue", "已逾期");
            default -> ReportExportLayout.valueLabel(key, language);
        };
    }
}
