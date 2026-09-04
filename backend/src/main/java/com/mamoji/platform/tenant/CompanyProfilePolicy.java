package com.mamoji.platform.tenant;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Normalizes and validates the stable company profile used by tenant-aware modules. */
public final class CompanyProfilePolicy {
    private static final String DEFAULT_POLICY_PROFILE = "CN-DEFAULT-DEMO-POLICY";
    private static final String LEGACY_SHENZHEN_POLICY_PROFILE = "CN-GD-SZ-DEMO-POLICY";
    private static final String SHENZHEN_STARTUP_POLICY_PROFILE = "CN-GD-SZ-STARTUP-LITE";

    private CompanyProfilePolicy() {
    }

    public static void initialize(Company company) {
        company.entityType = defaultText(company.entityType, "company").trim().toLowerCase(Locale.ROOT);
        company.industry = defaultText(company.industry, "未设置");
        company.taxpayerType = defaultText(company.taxpayerType, "未设置");
        company.currency = defaultText(company.currency, "CNY");
        company.country = "中国";
        company.province = company.name != null && company.name.contains("深圳") ? "广东省" : "";
        company.city = company.name != null && company.name.contains("深圳") ? "深圳市" : "";
        company.district = "";
        company.operatingRegion = regionLabel(company);
        company.policyProfileKey = defaultPolicyProfileKey(company);
        company.fiscalYearStartMonth = 1;
    }

    /** Repairs defaults produced by historical application versions before normal validation. */
    public static boolean hydrateLegacyDefaults(Company company) {
        boolean updated = false;
        if (isBlank(company.entityType)) {
            company.entityType = "company";
            updated = true;
        }
        if (isBlank(company.country)) {
            company.country = "中国";
            updated = true;
        }
        if (isBlank(company.province) && company.name != null && company.name.contains("深圳")) {
            company.province = "广东省";
            updated = true;
        }
        if (isBlank(company.city) && company.name != null && company.name.contains("深圳")) {
            company.city = "深圳市";
            updated = true;
        }
        if (isBlank(company.operatingRegion)) {
            company.operatingRegion = regionLabel(company);
            updated = true;
        }
        if (company.city != null && company.city.contains("深圳")
            && (DEFAULT_POLICY_PROFILE.equals(company.policyProfileKey)
                || LEGACY_SHENZHEN_POLICY_PROFILE.equals(company.policyProfileKey))) {
            company.policyProfileKey = SHENZHEN_STARTUP_POLICY_PROFILE;
            updated = true;
        } else if (isBlank(company.policyProfileKey)) {
            company.policyProfileKey = defaultPolicyProfileKey(company);
            updated = true;
        }
        if (company.fiscalYearStartMonth < 1 || company.fiscalYearStartMonth > 12) {
            company.fiscalYearStartMonth = 1;
            updated = true;
        }
        return updated;
    }

    public static void normalizeAndValidate(Company company) {
        company.name = required(company.name, "name", 200);
        company.entityType = required(company.entityType, "entityType", 32).toLowerCase(Locale.ROOT);
        if (!List.of("company", "household").contains(company.entityType)) {
            throw new IllegalArgumentException("entityType must be company or household");
        }
        company.creditCode = optional(company.creditCode, 64);
        if (company.creditCode != null) company.creditCode = company.creditCode.toUpperCase(Locale.ROOT);
        company.industry = required(company.industry, "industry", 160);
        company.taxpayerType = required(company.taxpayerType, "taxpayerType", 120);
        company.currency = required(company.currency, "currency", 3).toUpperCase(Locale.ROOT);
        if (!company.currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        company.country = required(company.country, "country", 100);
        company.province = optionalOrEmpty(company.province, 100);
        company.city = optionalOrEmpty(company.city, 100);
        company.district = optionalOrEmpty(company.district, 100);
        company.registeredAddress = optional(company.registeredAddress, 500);
        company.operatingRegion = required(company.operatingRegion, "operatingRegion", 400);
        company.taxAuthority = optional(company.taxAuthority, 200);
        company.policyProfileKey = required(company.policyProfileKey, "policyProfileKey", 160);
        if (company.fiscalYearStartMonth < 1 || company.fiscalYearStartMonth > 12) {
            throw new IllegalArgumentException("fiscalYearStartMonth must be between 1 and 12");
        }
        if (company.ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be positive");
        }
    }

    public static String regionLabel(Company company) {
        return Stream.of(company.country, company.province, company.city, company.district)
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .reduce((left, right) -> left + "/" + right)
            .orElse("中国");
    }

    private static String defaultPolicyProfileKey(Company company) {
        if ("household".equals(company.entityType)) return "CN-HOUSEHOLD-ASSET-PROFILE";
        if (company.city != null && company.city.contains("深圳")) return SHENZHEN_STARTUP_POLICY_PROFILE;
        return DEFAULT_POLICY_PROFILE;
    }

    private static String required(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        if (normalized.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return normalized;
    }

    private static String optional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new IllegalArgumentException("company profile value is too long");
        return normalized;
    }

    private static String optionalOrEmpty(String value, int maxLength) {
        String normalized = optional(value, maxLength);
        return normalized == null ? "" : normalized;
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
