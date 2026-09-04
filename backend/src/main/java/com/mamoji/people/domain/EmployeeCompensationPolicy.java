package com.mamoji.people.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the current Shenzhen compensation projection in one People Core policy.
 * Persisted inputs remain authoritative; calculated totals and explanation rows
 * are refreshed together before employee writes and reads.
 */
public final class EmployeeCompensationPolicy {
    private static final String DEFAULT_SOCIAL_INSURANCE_REGION = "深圳";
    private static final String DEFAULT_HUKOU_TYPE = "non_local";
    private static final String DEFAULT_MEDICAL_TIER = "tier1";
    private static final BigDecimal SHENZHEN_PENSION_MIN_BASE = new BigDecimal("4775");
    private static final BigDecimal SHENZHEN_PENSION_MAX_BASE = new BigDecimal("27549");
    private static final BigDecimal SHENZHEN_MEDICAL_MIN_BASE = new BigDecimal("6727");
    private static final BigDecimal SHENZHEN_MEDICAL_MAX_BASE = new BigDecimal("33633");
    private static final BigDecimal SHENZHEN_UNEMPLOYMENT_MIN_BASE = new BigDecimal("2520");
    private static final BigDecimal SHENZHEN_UNEMPLOYMENT_MAX_BASE = new BigDecimal("44265");
    private static final BigDecimal SHENZHEN_HOUSING_FUND_MIN_BASE = new BigDecimal("2520");
    private static final BigDecimal SHENZHEN_HOUSING_FUND_MAX_BASE = new BigDecimal("44265");
    private static final BigDecimal DEFAULT_PENSION_PERSONAL_RATE = new BigDecimal("8");
    private static final BigDecimal DEFAULT_PENSION_COMPANY_RATE = new BigDecimal("16");
    private static final BigDecimal DEFAULT_LOCAL_SUPPLEMENT_PENSION_COMPANY_RATE = BigDecimal.ONE;
    private static final BigDecimal DEFAULT_MEDICAL_TIER1_PERSONAL_RATE = new BigDecimal("2");
    private static final BigDecimal DEFAULT_MEDICAL_TIER1_COMPANY_RATE = new BigDecimal("6");
    private static final BigDecimal DEFAULT_MEDICAL_TIER2_PERSONAL_RATE = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_MEDICAL_TIER2_COMPANY_RATE = new BigDecimal("1.5");
    private static final BigDecimal DEFAULT_MATERNITY_COMPANY_RATE = new BigDecimal("0.5");
    private static final BigDecimal DEFAULT_UNEMPLOYMENT_PERSONAL_RATE = new BigDecimal("0.2");
    private static final BigDecimal DEFAULT_UNEMPLOYMENT_COMPANY_RATE = new BigDecimal("0.8");
    private static final BigDecimal DEFAULT_WORK_INJURY_COMPANY_RATE = new BigDecimal("0.2");
    private static final BigDecimal MAX_WORK_INJURY_COMPANY_RATE = new BigDecimal("1.4");
    private static final BigDecimal MIN_HOUSING_FUND_RATE = new BigDecimal("5");
    private static final BigDecimal DEFAULT_HOUSING_FUND_RATE = new BigDecimal("12");
    private static final BigDecimal MAX_HOUSING_FUND_RATE = new BigDecimal("12");
    private static final BigDecimal OVERTIME_MONTHLY_PAID_DAYS = new BigDecimal("21.75");
    private static final BigDecimal STANDARD_DAILY_WORK_HOURS = new BigDecimal("8");
    private static final BigDecimal WEEKDAY_OVERTIME_RATE = new BigDecimal("1.5");
    private static final BigDecimal REST_DAY_OVERTIME_RATE = new BigDecimal("2");
    private static final BigDecimal HOLIDAY_OVERTIME_RATE = new BigDecimal("3");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private EmployeeCompensationPolicy() {
    }

    public static void initialize(Employee employee, BigDecimal monthlyCost) {
        employee.overtimeBase = employee.salary;
        employee.weekdayOvertimeHours = BigDecimal.ZERO;
        employee.restDayOvertimeHours = BigDecimal.ZERO;
        employee.holidayOvertimeHours = BigDecimal.ZERO;
        employee.overtimePay = BigDecimal.ZERO;
        employee.overtimePolicyNote = overtimePolicyNote();
        employee.socialInsuranceBase = employee.salary;
        employee.socialInsurancePersonalRate = DEFAULT_PENSION_PERSONAL_RATE;
        employee.socialInsuranceCompanyRate = percentageOf(
            employee.socialInsurance,
            employee.socialInsuranceBase,
            DEFAULT_PENSION_COMPANY_RATE
        );
        employee.socialInsurancePersonalAmount = BigDecimal.ZERO;
        employee.socialInsuranceCompanyAmount = employee.socialInsurance;
        employee.housingFundBase = employee.salary;
        employee.housingFundPersonalRate = DEFAULT_HOUSING_FUND_RATE;
        employee.housingFundCompanyRate = percentageOf(
            employee.housingFund,
            employee.housingFundBase,
            DEFAULT_HOUSING_FUND_RATE
        );
        employee.housingFundPersonalAmount = BigDecimal.ZERO;
        employee.housingFundCompanyAmount = employee.housingFund;
        employee.personalDeduction = BigDecimal.ZERO;
        employee.netPayEstimate = BigDecimal.ZERO;
        employee.socialInsuranceRegion = DEFAULT_SOCIAL_INSURANCE_REGION;
        employee.hukouType = DEFAULT_HUKOU_TYPE;
        employee.medicalTier = DEFAULT_MEDICAL_TIER;
        employee.pensionBase = clamp(employee.salary, SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE);
        employee.medicalBase = clamp(employee.salary, SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE);
        employee.unemploymentBase = clamp(
            employee.salary,
            SHENZHEN_UNEMPLOYMENT_MIN_BASE,
            SHENZHEN_UNEMPLOYMENT_MAX_BASE
        );
        employee.workInjuryBase = max(employee.salary, SHENZHEN_UNEMPLOYMENT_MIN_BASE);
        employee.maternityBase = employee.medicalBase;
        employee.workInjuryCompanyRate = DEFAULT_WORK_INJURY_COMPANY_RATE;
        employee.socialInsurancePolicyNote = shenzhenPolicyNote();
        employee.monthlyCost = money(monthlyCost);
        hydrate(employee);
    }

    public static boolean hydrate(Employee employee) {
        boolean updated = false;
        BigDecimal salary = money(employee.salary);
        if (!sameMoney(employee.salary, salary)) {
            employee.salary = salary;
            updated = true;
        }
        String region = blankToDefault(employee.socialInsuranceRegion, DEFAULT_SOCIAL_INSURANCE_REGION);
        String hukouType = normalizeHukouType(employee.hukouType);
        String medicalTier = normalizeMedicalTier(employee.medicalTier);
        String policyNote = shenzhenPolicyNote();
        if (!sameText(employee.socialInsuranceRegion, region)) {
            employee.socialInsuranceRegion = region;
            updated = true;
        }
        if (!sameText(employee.hukouType, hukouType)) {
            employee.hukouType = hukouType;
            updated = true;
        }
        if (!sameText(employee.medicalTier, medicalTier)) {
            employee.medicalTier = medicalTier;
            updated = true;
        }
        if (!sameText(employee.socialInsurancePolicyNote, policyNote)) {
            employee.socialInsurancePolicyNote = policyNote;
            updated = true;
        }
        String overtimePolicyNote = overtimePolicyNote();
        if (!sameText(employee.overtimePolicyNote, overtimePolicyNote)) {
            employee.overtimePolicyNote = overtimePolicyNote;
            updated = true;
        }

        List<String> warnings = new ArrayList<>();
        BigDecimal overtimeBase = boundedBase(
            "加班工资",
            firstPositive(employee.overtimeBase, salary),
            SHENZHEN_UNEMPLOYMENT_MIN_BASE,
            null,
            warnings
        );
        BigDecimal weekdayOvertimeHours = nonNegative(employee.weekdayOvertimeHours);
        BigDecimal restDayOvertimeHours = nonNegative(employee.restDayOvertimeHours);
        BigDecimal holidayOvertimeHours = nonNegative(employee.holidayOvertimeHours);
        BigDecimal overtimePay = overtimePay(
            overtimeBase,
            weekdayOvertimeHours,
            restDayOvertimeHours,
            holidayOvertimeHours
        );
        BigDecimal pensionBase = boundedBase(
            "养老保险",
            firstPositive(employee.pensionBase, firstPositive(employee.socialInsuranceBase, salary)),
            SHENZHEN_PENSION_MIN_BASE,
            SHENZHEN_PENSION_MAX_BASE,
            warnings
        );
        BigDecimal medicalBase = boundedBase(
            "医疗保险",
            firstPositive(employee.medicalBase, firstPositive(employee.socialInsuranceBase, salary)),
            SHENZHEN_MEDICAL_MIN_BASE,
            SHENZHEN_MEDICAL_MAX_BASE,
            warnings
        );
        BigDecimal unemploymentBase = boundedBase(
            "失业保险",
            firstPositive(employee.unemploymentBase, salary),
            SHENZHEN_UNEMPLOYMENT_MIN_BASE,
            SHENZHEN_UNEMPLOYMENT_MAX_BASE,
            warnings
        );
        BigDecimal maternityBase = boundedBase(
            "生育保险",
            firstPositive(employee.maternityBase, medicalBase),
            SHENZHEN_MEDICAL_MIN_BASE,
            SHENZHEN_MEDICAL_MAX_BASE,
            warnings
        );
        BigDecimal workInjuryBase = max(
            firstPositive(employee.workInjuryBase, salary),
            SHENZHEN_UNEMPLOYMENT_MIN_BASE
        );
        BigDecimal housingFundBase = boundedBase(
            "住房公积金",
            firstPositive(employee.housingFundBase, salary),
            SHENZHEN_HOUSING_FUND_MIN_BASE,
            SHENZHEN_HOUSING_FUND_MAX_BASE,
            warnings
        );
        BigDecimal workInjuryCompanyRate = boundedRate(
            "工伤公司费率",
            firstPositive(employee.workInjuryCompanyRate, DEFAULT_WORK_INJURY_COMPANY_RATE),
            DEFAULT_WORK_INJURY_COMPANY_RATE,
            MAX_WORK_INJURY_COMPANY_RATE,
            warnings
        );
        BigDecimal housingFundPersonalRate = boundedRate(
            "公积金个人比例",
            firstPositive(employee.housingFundPersonalRate, DEFAULT_HOUSING_FUND_RATE),
            MIN_HOUSING_FUND_RATE,
            MAX_HOUSING_FUND_RATE,
            warnings
        );
        BigDecimal housingFundCompanyRate = boundedRate(
            "公积金公司比例",
            firstPositive(
                employee.housingFundCompanyRate,
                percentageOf(
                    firstPositive(employee.housingFundCompanyAmount, employee.housingFund),
                    housingFundBase,
                    DEFAULT_HOUSING_FUND_RATE
                )
            ),
            MIN_HOUSING_FUND_RATE,
            MAX_HOUSING_FUND_RATE,
            warnings
        );

        updated |= assign(employee.pensionBase, pensionBase, value -> employee.pensionBase = value);
        updated |= assign(employee.medicalBase, medicalBase, value -> employee.medicalBase = value);
        updated |= assign(employee.unemploymentBase, unemploymentBase, value -> employee.unemploymentBase = value);
        updated |= assign(employee.maternityBase, maternityBase, value -> employee.maternityBase = value);
        updated |= assign(employee.workInjuryBase, workInjuryBase, value -> employee.workInjuryBase = value);
        updated |= assign(
            employee.workInjuryCompanyRate,
            workInjuryCompanyRate,
            value -> employee.workInjuryCompanyRate = value
        );
        updated |= assign(employee.housingFundBase, housingFundBase, value -> employee.housingFundBase = value);
        updated |= assign(
            employee.housingFundPersonalRate,
            housingFundPersonalRate,
            value -> employee.housingFundPersonalRate = value
        );
        updated |= assign(
            employee.housingFundCompanyRate,
            housingFundCompanyRate,
            value -> employee.housingFundCompanyRate = value
        );
        updated |= assign(employee.overtimeBase, overtimeBase, value -> employee.overtimeBase = value);
        updated |= assign(
            employee.weekdayOvertimeHours,
            weekdayOvertimeHours,
            value -> employee.weekdayOvertimeHours = value
        );
        updated |= assign(
            employee.restDayOvertimeHours,
            restDayOvertimeHours,
            value -> employee.restDayOvertimeHours = value
        );
        updated |= assign(
            employee.holidayOvertimeHours,
            holidayOvertimeHours,
            value -> employee.holidayOvertimeHours = value
        );
        updated |= assign(employee.overtimePay, overtimePay, value -> employee.overtimePay = value);
        if (employee.taxEstimate == null) {
            employee.taxEstimate = BigDecimal.ZERO;
            updated = true;
        }
        if (employee.personalDeduction == null) {
            employee.personalDeduction = BigDecimal.ZERO;
            updated = true;
        }

        BigDecimal medicalPersonalRate = "tier2".equals(medicalTier)
            ? DEFAULT_MEDICAL_TIER2_PERSONAL_RATE
            : DEFAULT_MEDICAL_TIER1_PERSONAL_RATE;
        BigDecimal medicalCompanyRate = "tier2".equals(medicalTier)
            ? DEFAULT_MEDICAL_TIER2_COMPANY_RATE
            : DEFAULT_MEDICAL_TIER1_COMPANY_RATE;
        List<SocialInsuranceItem> socialInsuranceItems = new ArrayList<>();
        socialInsuranceItems.add(socialInsuranceItem(
            "pension", "养老保险", "养老", pensionBase, SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE,
            DEFAULT_PENSION_PERSONAL_RATE, DEFAULT_PENSION_COMPANY_RATE,
            "广东企业职工养老基数 2025-07 起；单位 16%，个人 8%", "2025-07-01 至 2026-06-30"
        ));
        if (isLocalHukou(hukouType)) {
            socialInsuranceItems.add(socialInsuranceItem(
                "localSupplementPension", "地方补充养老", "养老", pensionBase,
                SHENZHEN_PENSION_MIN_BASE, SHENZHEN_PENSION_MAX_BASE,
                BigDecimal.ZERO, DEFAULT_LOCAL_SUPPLEMENT_PENSION_COMPANY_RATE,
                "深圳本市户籍地方补充养老，单位承担", "长期政策，按最新通知调整"
            ));
        }
        socialInsuranceItems.add(socialInsuranceItem(
            "medical", "医疗保险" + ("tier2".equals(medicalTier) ? "二档" : "一档"), "医疗", medicalBase,
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE,
            medicalPersonalRate, medicalCompanyRate,
            "深圳医保 2026 基数；一档单位 6%/个人 2%，二档单位 1.5%/个人 0.5%", "2026-01-01 至 2026-12-31"
        ));
        socialInsuranceItems.add(socialInsuranceItem(
            "maternity", "生育保险", "生育", maternityBase,
            SHENZHEN_MEDICAL_MIN_BASE, SHENZHEN_MEDICAL_MAX_BASE,
            BigDecimal.ZERO, DEFAULT_MATERNITY_COMPANY_RATE,
            "深圳生育保险按职工医保基数，单位 0.5%，个人不缴", "2026-01-01 至 2026-12-31"
        ));
        socialInsuranceItems.add(socialInsuranceItem(
            "unemployment", "失业保险", "失业", unemploymentBase,
            SHENZHEN_UNEMPLOYMENT_MIN_BASE, SHENZHEN_UNEMPLOYMENT_MAX_BASE,
            DEFAULT_UNEMPLOYMENT_PERSONAL_RATE, DEFAULT_UNEMPLOYMENT_COMPANY_RATE,
            "深圳失业保险 2025-07 至 2026-06 基数；单位 0.8%，个人 0.2%", "2025-07-01 至 2026-06-30"
        ));
        socialInsuranceItems.add(socialInsuranceItem(
            "workInjury", "工伤保险", "工伤", workInjuryBase,
            SHENZHEN_UNEMPLOYMENT_MIN_BASE, null,
            BigDecimal.ZERO, workInjuryCompanyRate,
            "深圳工伤基数不低于 2520，普通单位按职工工资总额计缴；行业基准费率 0.2%-1.4%，个人不缴", "2024-07-01 起"
        ));

        BigDecimal socialPersonalAmount = socialPersonalAmount(socialInsuranceItems);
        BigDecimal socialCompanyAmount = socialCompanyAmount(socialInsuranceItems);
        BigDecimal housingPersonalAmount = contribution(housingFundBase, housingFundPersonalRate);
        BigDecimal housingCompanyAmount = contribution(housingFundBase, housingFundCompanyRate);
        BigDecimal payableSalary = salary.add(overtimePay);
        BigDecimal monthlyCost = payableSalary.add(socialCompanyAmount).add(housingCompanyAmount);
        BigDecimal netPayEstimate = payableSalary
            .subtract(socialPersonalAmount)
            .subtract(housingPersonalAmount)
            .subtract(money(employee.taxEstimate))
            .subtract(money(employee.personalDeduction));
        if (netPayEstimate.signum() < 0) netPayEstimate = BigDecimal.ZERO;

        updated |= assign(employee.socialInsuranceBase, pensionBase, value -> employee.socialInsuranceBase = value);
        BigDecimal aggregatePersonalRate = percentageOf(socialPersonalAmount, pensionBase, BigDecimal.ZERO);
        BigDecimal aggregateCompanyRate = percentageOf(socialCompanyAmount, pensionBase, BigDecimal.ZERO);
        updated |= assign(
            employee.socialInsurancePersonalRate,
            aggregatePersonalRate,
            value -> employee.socialInsurancePersonalRate = value
        );
        updated |= assign(
            employee.socialInsuranceCompanyRate,
            aggregateCompanyRate,
            value -> employee.socialInsuranceCompanyRate = value
        );
        updated |= assign(
            employee.socialInsurancePersonalAmount,
            socialPersonalAmount,
            value -> employee.socialInsurancePersonalAmount = value
        );
        updated |= assign(
            employee.socialInsuranceCompanyAmount,
            socialCompanyAmount,
            value -> employee.socialInsuranceCompanyAmount = value
        );
        updated |= assign(
            employee.housingFundPersonalAmount,
            housingPersonalAmount,
            value -> employee.housingFundPersonalAmount = value
        );
        updated |= assign(
            employee.housingFundCompanyAmount,
            housingCompanyAmount,
            value -> employee.housingFundCompanyAmount = value
        );
        updated |= assign(employee.socialInsurance, socialCompanyAmount, value -> employee.socialInsurance = value);
        updated |= assign(employee.housingFund, housingCompanyAmount, value -> employee.housingFund = value);
        updated |= assign(employee.monthlyCost, monthlyCost, value -> employee.monthlyCost = value);
        updated |= assign(employee.netPayEstimate, netPayEstimate, value -> employee.netPayEstimate = value);
        employee.socialInsuranceItems = List.copyOf(socialInsuranceItems);
        employee.socialInsuranceWarnings = List.copyOf(warnings);
        return updated;
    }

    private static boolean assign(BigDecimal current, BigDecimal value, MoneySetter setter) {
        if (sameMoney(current, value)) return false;
        setter.set(value);
        return true;
    }

    private static SocialInsuranceItem socialInsuranceItem(
        String key,
        String name,
        String category,
        BigDecimal base,
        BigDecimal minBase,
        BigDecimal maxBase,
        BigDecimal personalRate,
        BigDecimal companyRate,
        String policyBasis,
        String validPeriod
    ) {
        SocialInsuranceItem item = new SocialInsuranceItem();
        item.key = key;
        item.name = name;
        item.category = category;
        item.base = money(base);
        item.minBase = minBase;
        item.maxBase = maxBase;
        item.personalRate = money(personalRate);
        item.companyRate = money(companyRate);
        item.personalAmount = contribution(item.base, item.personalRate);
        item.companyAmount = contribution(item.base, item.companyRate);
        item.policyBasis = policyBasis;
        item.validPeriod = validPeriod;
        item.status = "normal";
        return item;
    }

    private static BigDecimal socialPersonalAmount(List<SocialInsuranceItem> items) {
        return items.stream().map(item -> money(item.personalAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal socialCompanyAmount(List<SocialInsuranceItem> items) {
        return items.stream().map(item -> money(item.companyAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal boundedBase(
        String label,
        BigDecimal value,
        BigDecimal min,
        BigDecimal max,
        List<String> warnings
    ) {
        BigDecimal safeValue = money(value);
        if (safeValue.compareTo(min) < 0) {
            warnings.add(label + "基数低于深圳当前下限，已按 " + moneyText(min) + " 计算");
            return min;
        }
        if (max != null && safeValue.compareTo(max) > 0) {
            warnings.add(label + "基数高于深圳当前上限，已按 " + moneyText(max) + " 计算");
            return max;
        }
        return safeValue;
    }

    private static BigDecimal boundedRate(
        String label,
        BigDecimal value,
        BigDecimal min,
        BigDecimal max,
        List<String> warnings
    ) {
        BigDecimal safeValue = money(value);
        if (safeValue.compareTo(min) < 0) {
            warnings.add(label + "低于当前下限，已按 " + moneyText(min) + "% 计算");
            return min;
        }
        if (safeValue.compareTo(max) > 0) {
            warnings.add(label + "高于当前上限，已按 " + moneyText(max) + "% 计算");
            return max;
        }
        return safeValue;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        BigDecimal safeValue = money(value);
        if (safeValue.compareTo(min) < 0) return min;
        if (max != null && safeValue.compareTo(max) > 0) return max;
        return safeValue;
    }

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        return money(left).compareTo(money(right)) >= 0 ? money(left) : money(right);
    }

    private static String normalizeHukouType(String value) {
        String normalized = blankToDefault(value, DEFAULT_HUKOU_TYPE);
        return "local".equals(normalized) || "shenzhen".equals(normalized) || "深户".equals(normalized)
            ? "local"
            : "non_local";
    }

    private static boolean isLocalHukou(String value) {
        return "local".equals(normalizeHukouType(value));
    }

    private static String normalizeMedicalTier(String value) {
        String normalized = blankToDefault(value, DEFAULT_MEDICAL_TIER);
        return "tier2".equals(normalized) || "二档".equals(normalized) ? "tier2" : "tier1";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean sameText(String left, String right) {
        return blankToDefault(left, "").equals(blankToDefault(right, ""));
    }

    private static String shenzhenPolicyNote() {
        return "深圳五险一金演示政策：养老 2025-07 至 2026-06 基数 4775-27549；医保/生育 2026 年基数 6727-33633；失业 2025-07 至 2026-06 基数 2520-44265；工伤基数不低于 2520，普通单位无单人工资上限，行业基准费率 0.2%-1.4%；公积金 2025-07 至 2026-06 基数 2520-44265。";
    }

    private static String overtimePolicyNote() {
        return "国家加班费演示政策：工作日延时 150%，休息日未调休 200%，法定节假日 300%；日工资=月工资收入/21.75，小时工资=月工资收入/(21.75×8)。";
    }

    private static BigDecimal overtimePay(
        BigDecimal base,
        BigDecimal weekdayHours,
        BigDecimal restDayHours,
        BigDecimal holidayHours
    ) {
        BigDecimal hourlyRate = money(base).divide(
            OVERTIME_MONTHLY_PAID_DAYS.multiply(STANDARD_DAILY_WORK_HOURS),
            6,
            RoundingMode.HALF_UP
        );
        return hourlyRate.multiply(nonNegative(weekdayHours)).multiply(WEEKDAY_OVERTIME_RATE)
            .add(hourlyRate.multiply(nonNegative(restDayHours)).multiply(REST_DAY_OVERTIME_RATE))
            .add(hourlyRate.multiply(nonNegative(holidayHours)).multiply(HOLIDAY_OVERTIME_RATE))
            .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal contribution(BigDecimal base, BigDecimal rate) {
        return money(base).multiply(money(rate)).divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentageOf(BigDecimal amount, BigDecimal base, BigDecimal fallback) {
        BigDecimal safeBase = money(base);
        BigDecimal safeAmount = money(amount);
        if (safeBase.signum() <= 0 || safeAmount.signum() <= 0) return fallback;
        return safeAmount.multiply(ONE_HUNDRED).divide(safeBase, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal firstPositive(BigDecimal first, BigDecimal second) {
        BigDecimal safeFirst = money(first);
        return safeFirst.signum() > 0 ? safeFirst : money(second);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        BigDecimal safeValue = money(value);
        return safeValue.signum() < 0 ? BigDecimal.ZERO : safeValue;
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        return money(left).compareTo(money(right)) == 0;
    }

    private static BigDecimal money(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return BigDecimal.ZERO;
        return new BigDecimal(String.valueOf(value));
    }

    private static String moneyText(BigDecimal value) {
        return money(value).stripTrailingZeros().toPlainString();
    }

    @FunctionalInterface
    private interface MoneySetter {
        void set(BigDecimal value);
    }
}
