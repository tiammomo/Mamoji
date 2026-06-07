package com.mamoji.service;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.ReceiptVoucher;
import com.mamoji.domain.Models.TaxItem;
import com.mamoji.domain.Models.User;
import com.mamoji.repository.EnterpriseStore;
import com.mamoji.service.support.AccessControlService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class TaxComplianceService {
    private static final BigDecimal SMALL_SCALE_MONTHLY_VAT_EXEMPTION = new BigDecimal("100000");
    private static final BigDecimal SMALL_SCALE_QUARTERLY_VAT_EXEMPTION = new BigDecimal("300000");
    private static final BigDecimal GENERAL_TAXPAYER_SALES_THRESHOLD = new BigDecimal("5000000");
    private static final BigDecimal GENERAL_TAXPAYER_SALES_WATCHLINE = new BigDecimal("4500000");
    private static final Map<Integer, String> OFFICIAL_2026_DUE_DATES = Map.ofEntries(
        Map.entry(1, "2026-01-20"),
        Map.entry(2, "2026-02-24"),
        Map.entry(3, "2026-03-16"),
        Map.entry(4, "2026-04-20"),
        Map.entry(5, "2026-05-22"),
        Map.entry(6, "2026-06-15"),
        Map.entry(7, "2026-07-15"),
        Map.entry(8, "2026-08-17"),
        Map.entry(9, "2026-09-15"),
        Map.entry(10, "2026-10-26"),
        Map.entry(11, "2026-11-16"),
        Map.entry(12, "2026-12-15")
    );

    private final AccessControlService accessControl;
    private final EnterpriseStore enterpriseStore;

    public TaxComplianceService(AccessControlService accessControl, EnterpriseStore enterpriseStore) {
        this.accessControl = accessControl;
        this.enterpriseStore = enterpriseStore;
    }

    public Map<String, Object> report(String authorization, Long companyId) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        List<TaxItem> taxItems = enterpriseStore.sortedTaxItems(company.id);
        List<ReceiptVoucher> vouchers = enterpriseStore.sortedReceiptVouchers(company.id);
        Map<String, Object> policyProfile = policyProfile(company);
        List<Map<String, Object>> filingCalendar = filingCalendar(company, taxItems);
        List<Map<String, Object>> riskItems = riskItems(company, taxItems, vouchers, filingCalendar);
        Map<String, Object> metrics = metrics(taxItems, filingCalendar, riskItems, vouchers);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policyProfile", policyProfile);
        result.put("filingCalendar", filingCalendar);
        result.put("riskItems", riskItems);
        result.put("metrics", metrics);
        result.put("assumptions", List.of(
            "当前默认使用深圳初创公司轻税务画像，仅作经营合规提醒和资料闭环，不替代正式纳税申报。",
            "2026 年月度/季度申报截止日按国家税务总局办公厅 2026 年度申报纳税期限通知维护。",
            "小规模增值税按月 10 万、按季 30 万免征阈值做提醒；特殊销售、不动产、差额征税等场景需财务确认。"
        ));
        return result;
    }

    private Map<String, Object> policyProfile(Company company) {
        boolean smallScale = isSmallScale(company);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("key", safeText(company.policyProfileKey, "CN-GD-SZ-STARTUP-LITE"));
        profile.put("name", smallScale ? "深圳小规模初创公司轻税务画像" : "深圳一般纳税人经营税务画像");
        profile.put("region", safeText(company.operatingRegion, "中国/广东省/深圳市"));
        profile.put("taxAuthority", safeText(company.taxAuthority, "主管税务机关待维护"));
        profile.put("taxpayerType", safeText(company.taxpayerType, smallScale ? "小规模纳税人" : "一般纳税人"));
        profile.put("vatFrequency", smallScale ? "quarterly" : "monthly");
        profile.put("vatMode", smallScale ? "小规模简易计税，关注免征阈值和零申报" : "一般计税，关注销项、进项、抵扣和留抵");
        profile.put("inputDeductionEnabled", !smallScale);
        profile.put("fiscalYearStartMonth", company.fiscalYearStartMonth <= 0 ? 1 : company.fiscalYearStartMonth);
        profile.put("smallScaleMonthlyVatExemption", SMALL_SCALE_MONTHLY_VAT_EXEMPTION);
        profile.put("smallScaleQuarterlyVatExemption", SMALL_SCALE_QUARTERLY_VAT_EXEMPTION);
        profile.put("smallScaleVatPolicyValidTo", "2027-12-31");
        profile.put("generalTaxpayerSalesThreshold", GENERAL_TAXPAYER_SALES_THRESHOLD);
        profile.put("coreTaxes", List.of("增值税", "企业所得税", "个人所得税代扣", "附加税费", "印花税"));
        profile.put("policySources", List.of(
            source("2026 年度申报纳税期限", "https://fgk.chinatax.gov.cn/zcfgk/c102424/c5245729/content.html"),
            source("小规模月销售额 10 万元以下免征增值税", "https://www.chinatax.gov.cn/chinatax/n810356/n3010387/c5211011/content.html"),
            source("增值税一般纳税人登记管理有关事项", "https://tianjin.chinatax.gov.cn/11200000000/0300/030004/03000418/20260104155628948.shtml"),
            source("个人所得税扣缴申报累计预扣法", "https://www.chinatax.gov.cn/chinatax/n810341/n810760/c3959585/content.html")
        ));
        return profile;
    }

    private Map<String, String> source(String name, String url) {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("name", name);
        source.put("url", url);
        return source;
    }

    private List<Map<String, Object>> filingCalendar(Company company, List<TaxItem> taxItems) {
        List<Map<String, Object>> calendar = new ArrayList<>();
        YearMonth current = YearMonth.now();
        boolean smallScale = isSmallScale(company);

        for (int offset = -1; offset <= 3; offset++) {
            YearMonth period = current.plusMonths(offset);
            calendar.add(calendarItem(company, taxItems, "personal_income_tax", period.toString(), "monthly", true));
            if (!smallScale) {
                calendar.add(calendarItem(company, taxItems, "vat", period.toString(), "monthly", true));
                calendar.add(calendarItem(company, taxItems, "surcharge", period.toString(), "monthly", true));
            }
        }

        int currentQuarter = quarterOf(current);
        int year = current.getYear();
        for (int quarter = Math.max(1, currentQuarter - 1); quarter <= Math.min(4, currentQuarter + 2); quarter++) {
            String period = year + "-Q" + quarter;
            if (smallScale) {
                calendar.add(calendarItem(company, taxItems, "vat", period, "quarterly", true));
                calendar.add(calendarItem(company, taxItems, "surcharge", period, "quarterly", true));
            }
            calendar.add(calendarItem(company, taxItems, "corporate_income_tax", period, "quarterly", true));
        }

        return calendar.stream()
            .sorted(Comparator
                .comparing((Map<String, Object> row) -> String.valueOf(row.get("dueDate")))
                .thenComparing(row -> String.valueOf(row.get("taxType"))))
            .toList();
    }

    private Map<String, Object> calendarItem(
        Company company,
        List<TaxItem> taxItems,
        String taxType,
        String period,
        String frequency,
        boolean required
    ) {
        Optional<TaxItem> matched = taxItems.stream()
            .filter(item -> taxType.equals(item.taxType) && period.equals(item.period))
            .findFirst();
        String dueDate = matched.map(item -> item.dueDate).orElseGet(() -> dueDateFor(period, frequency));
        boolean zeroDeclarationRequired = required && List.of("vat", "surcharge", "corporate_income_tax", "personal_income_tax").contains(taxType);
        String status = matched.map(item -> item.status).orElse("missing");
        String filingStatus = matched.map(item -> item.filingStatus).orElse("not_started");
        String paymentStatus = matched.map(item -> item.paymentStatus).orElse("unpaid");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", taxType + "-" + period);
        row.put("taxType", taxType);
        row.put("taxTypeName", taxTypeName(taxType));
        row.put("period", period);
        row.put("frequency", frequency);
        row.put("dueDate", dueDate);
        row.put("required", required);
        row.put("zeroDeclarationRequired", zeroDeclarationRequired);
        row.put("matchedTaxItemId", matched.map(item -> item.id).orElse(null));
        row.put("status", status);
        row.put("filingStatus", filingStatus);
        row.put("paymentStatus", paymentStatus);
        row.put("riskLevel", calendarRiskLevel(dueDate, status, filingStatus));
        row.put("policyBasis", safeText(company.policyProfileKey, "CN-GD-SZ-STARTUP-LITE"));
        row.put("note", matched.map(item -> item.note).orElse("按政策画像自动生成的税期待办"));
        return row;
    }

    private List<Map<String, Object>> riskItems(
        Company company,
        List<TaxItem> taxItems,
        List<ReceiptVoucher> vouchers,
        List<Map<String, Object>> filingCalendar
    ) {
        List<Map<String, Object>> risks = new ArrayList<>();
        boolean smallScale = isSmallScale(company);
        LocalDate today = LocalDate.now();

        for (TaxItem item : taxItems) {
            BigDecimal unpaid = money(item.taxAmount).subtract(money(item.paidAmount)).max(BigDecimal.ZERO);
            LocalDate dueDate = parseDate(item.dueDate).orElse(today);
            long days = ChronoUnit.DAYS.between(today, dueDate);
            boolean filingOpen = !List.of("submitted", "accepted").contains(safeText(item.filingStatus, ""));
            boolean zeroDeclaration = money(item.taxAmount).compareTo(BigDecimal.ZERO) == 0;

            if (unpaid.compareTo(BigDecimal.ZERO) > 0 && dueDate.isBefore(today)) {
                risks.add(risk("tax-" + item.id + "-payment-overdue", "high", "税款已逾期",
                    item.name + " 已逾期 " + Math.abs(days) + " 天，待缴 " + moneyText(unpaid),
                    item.taxType, item.period, item.dueDate, item.id, "补缴税款并上传缴款回执", item.policyBasis));
            } else if ((unpaid.compareTo(BigDecimal.ZERO) > 0 || filingOpen) && !dueDate.isAfter(today.plusDays(15))) {
                risks.add(risk("tax-" + item.id + "-due-soon", dueDate.isBefore(today) ? "high" : "medium", "临近申报缴纳截止日",
                    item.name + " " + dueLabel(dueDate) + "，需确认申报和缴款状态",
                    item.taxType, item.period, item.dueDate, item.id, "检查申报、扣款和回执", item.policyBasis));
            }

            if (filingOpen && (unpaid.compareTo(BigDecimal.ZERO) > 0 || zeroDeclaration)) {
                risks.add(risk("tax-" + item.id + "-filing-open", !dueDate.isAfter(today.plusDays(7)) ? "high" : "medium", "申报状态未闭环",
                    item.name + " 当前申报状态为「" + safeText(item.filingStatus, "未开始") + "」",
                    item.taxType, item.period, item.dueDate, item.id, "标记申报状态或补充申报回执", item.policyBasis));
            }

            if (zeroDeclaration && filingOpen && isRequiredTaxType(item.taxType)) {
                risks.add(risk("tax-" + item.id + "-zero-filing", !dueDate.isAfter(today.plusDays(7)) ? "high" : "medium", "零税款仍需申报",
                    item.name + " 应缴税额为 0，但税期仍需完成申报闭环",
                    item.taxType, item.period, item.dueDate, item.id, "完成零申报并归档申报回执", item.policyBasis));
            }

            if (isBlank(item.responsiblePerson)) {
                risks.add(risk("tax-" + item.id + "-owner", "medium", "负责人缺失",
                    item.name + " 未设置财务或代理记账责任人",
                    item.taxType, item.period, item.dueDate, item.id, "补充责任人", item.policyBasis));
            }

            if (isBlank(item.policyBasis)) {
                risks.add(risk("tax-" + item.id + "-policy", "low", "政策依据缺失",
                    item.name + " 未关联政策画像或填报依据",
                    item.taxType, item.period, item.dueDate, item.id, "关联政策版本", item.policyBasis));
            }

            if ("vat".equals(item.taxType) && smallScale) {
                BigDecimal threshold = "monthly".equals(item.frequency)
                    ? SMALL_SCALE_MONTHLY_VAT_EXEMPTION
                    : SMALL_SCALE_QUARTERLY_VAT_EXEMPTION;
                if (money(item.taxableAmount).compareTo(threshold) <= 0 && money(item.taxAmount).compareTo(BigDecimal.ZERO) > 0) {
                    risks.add(risk("tax-" + item.id + "-small-scale-exemption", "medium", "小规模免征阈值待复核",
                        item.name + " 计税销售额未超过 " + moneyText(threshold) + "，但仍记录应缴增值税",
                        item.taxType, item.period, item.dueDate, item.id, "复核免税栏次和特殊销售情形", item.policyBasis));
                }
                if (money(item.taxableAmount).compareTo(threshold) > 0 && money(item.taxAmount).compareTo(BigDecimal.ZERO) == 0) {
                    risks.add(risk("tax-" + item.id + "-small-scale-threshold", "high", "超过免征阈值仍为零税款",
                        item.name + " 计税销售额超过 " + moneyText(threshold) + "，需确认是否适用减免或差额征税",
                        item.taxType, item.period, item.dueDate, item.id, "复核增值税计税口径", item.policyBasis));
                }
            }
        }

        for (Map<String, Object> calendar : filingCalendar) {
            if (calendar.get("matchedTaxItemId") != null) {
                continue;
            }
            LocalDate dueDate = parseDate(String.valueOf(calendar.get("dueDate"))).orElse(today);
            if (dueDate.isAfter(today.plusDays(30))) {
                continue;
            }
            String taxType = String.valueOf(calendar.get("taxType"));
            String period = String.valueOf(calendar.get("period"));
            String severity = dueDate.isBefore(today) ? "high" : !dueDate.isAfter(today.plusDays(15)) ? "medium" : "low";
            risks.add(risk("calendar-" + taxType + "-" + period, severity, "税期待办未建档",
                taxTypeName(taxType) + " " + period + " 尚未创建税务事项，截止日 " + calendar.get("dueDate"),
                taxType, period, String.valueOf(calendar.get("dueDate")), null, "创建税务事项或确认不适用", String.valueOf(calendar.get("policyBasis"))));
        }

        receiptRisks(vouchers).forEach(risks::add);
        salesThresholdRisk(company, vouchers).ifPresent(risks::add);

        return risks.stream()
            .sorted(Comparator
                .comparingInt((Map<String, Object> row) -> severityWeight(String.valueOf(row.get("severity")))).reversed()
                .thenComparing(row -> String.valueOf(row.get("dueDate") == null ? "9999-12-31" : row.get("dueDate"))))
            .limit(30)
            .toList();
    }

    private List<Map<String, Object>> receiptRisks(List<ReceiptVoucher> vouchers) {
        List<Map<String, Object>> risks = new ArrayList<>();
        for (ReceiptVoucher voucher : vouchers) {
            String taxType = "income".equals(voucher.direction) ? "vat" : "corporate_income_tax";
            String period = safeText(voucher.taxPeriod, taxPeriodFor(voucher.issueDate));
            if (!"not_required".equals(voucher.invoiceCheckStatus) && !"verified".equals(voucher.invoiceCheckStatus)) {
                risks.add(risk("receipt-" + voucher.id + "-invoice-check", "high", "发票待查验",
                    voucher.title + " 尚未完成发票查验，影响申报资料可信度",
                    taxType, period, voucher.dueDate, null, "完成发票查验并归档", "票据凭证规则"));
            }
            if ("pending".equals(voucher.deductionStatus) || "deductible".equals(voucher.deductionStatus)) {
                risks.add(risk("receipt-" + voucher.id + "-deduction", "medium", "进项抵扣未确认",
                    voucher.title + " 仍处于进项抵扣待确认状态",
                    "vat", period, voucher.dueDate, null, "确认抵扣用途或转出", "票据凭证规则"));
            }
            if (voucher.transactionId == null) {
                risks.add(risk("receipt-" + voucher.id + "-transaction", "medium", "凭证未关联流水",
                    voucher.title + " 未关联经营流水或资金流水",
                    taxType, period, voucher.dueDate, null, "关联银行流水或经营流水", "票据凭证规则"));
            }
            if (isBlank(voucher.fileName)) {
                risks.add(risk("receipt-" + voucher.id + "-attachment", "low", "附件缺口",
                    voucher.title + " 缺少附件原件或扫描件",
                    taxType, period, voucher.dueDate, null, "补充附件", "票据凭证规则"));
            }
            if (isBlank(voucher.taxPeriod) && List.of("sales_invoice", "purchase_invoice", "tax_receipt").contains(voucher.voucherType)) {
                risks.add(risk("receipt-" + voucher.id + "-tax-period", "low", "票据税期缺失",
                    voucher.title + " 未设置税期，影响税期归集",
                    taxType, period, voucher.dueDate, null, "补充票据税期", "票据凭证规则"));
            }
        }
        return risks;
    }

    private Optional<Map<String, Object>> salesThresholdRisk(Company company, List<ReceiptVoucher> vouchers) {
        if (!isSmallScale(company)) {
            return Optional.empty();
        }
        YearMonth current = YearMonth.now();
        YearMonth start = current.minusMonths(11);
        BigDecimal rollingSales = vouchers.stream()
            .filter(voucher -> "sales_invoice".equals(voucher.voucherType))
            .filter(voucher -> parseYearMonth(voucher.issueDate).map(period -> !period.isBefore(start) && !period.isAfter(current)).orElse(false))
            .map(voucher -> money(voucher.amount).subtract(money(voucher.taxAmount)).max(BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rollingSales.compareTo(GENERAL_TAXPAYER_SALES_THRESHOLD) >= 0) {
            return Optional.of(risk("vat-general-registration-threshold", "high", "达到一般纳税人登记关注线",
                "近 12 个月应征增值税销售额约 " + moneyText(rollingSales) + "，已达到 500 万口径",
                "vat", current.toString(), null, null, "核验销售额并办理一般纳税人登记", company.policyProfileKey));
        }
        if (rollingSales.compareTo(GENERAL_TAXPAYER_SALES_WATCHLINE) >= 0) {
            return Optional.of(risk("vat-general-registration-watch", "medium", "接近一般纳税人登记阈值",
                "近 12 个月应征增值税销售额约 " + moneyText(rollingSales) + "，接近 500 万口径",
                "vat", current.toString(), null, null, "持续跟踪滚动销售额", company.policyProfileKey));
        }
        return Optional.empty();
    }

    private Map<String, Object> metrics(
        List<TaxItem> taxItems,
        List<Map<String, Object>> filingCalendar,
        List<Map<String, Object>> riskItems,
        List<ReceiptVoucher> vouchers
    ) {
        long highRiskCount = riskItems.stream().filter(row -> "high".equals(row.get("severity"))).count();
        long mediumRiskCount = riskItems.stream().filter(row -> "medium".equals(row.get("severity"))).count();
        long missingPeriodCount = filingCalendar.stream().filter(row -> row.get("matchedTaxItemId") == null).count();
        long zeroDeclarationOpenCount = taxItems.stream()
            .filter(item -> money(item.taxAmount).compareTo(BigDecimal.ZERO) == 0)
            .filter(item -> !List.of("submitted", "accepted").contains(safeText(item.filingStatus, "")))
            .count();
        long dueSoonCount = filingCalendar.stream()
            .filter(row -> !"accepted".equals(row.get("filingStatus")))
            .filter(row -> parseDate(String.valueOf(row.get("dueDate")))
                .map(dueDate -> !dueDate.isAfter(LocalDate.now().plusDays(15)))
                .orElse(false))
            .count();
        long completed = filingCalendar.stream()
            .filter(row -> List.of("submitted", "accepted").contains(String.valueOf(row.get("filingStatus"))))
            .count();
        int completionRate = filingCalendar.isEmpty()
            ? 0
            : BigDecimal.valueOf(completed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(filingCalendar.size()), 0, RoundingMode.HALF_UP)
                .intValue();
        long receiptGapCount = vouchers.stream().filter(voucher ->
            isBlank(voucher.fileName)
                || voucher.transactionId == null
                || (!"not_required".equals(voucher.invoiceCheckStatus) && !"verified".equals(voucher.invoiceCheckStatus))
                || "pending".equals(voucher.deductionStatus)
                || "deductible".equals(voucher.deductionStatus)
                || (isBlank(voucher.taxPeriod) && List.of("sales_invoice", "purchase_invoice", "tax_receipt").contains(voucher.voucherType))
        ).count();

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("riskCount", riskItems.size());
        metrics.put("highRiskCount", highRiskCount);
        metrics.put("mediumRiskCount", mediumRiskCount);
        metrics.put("missingPeriodCount", missingPeriodCount);
        metrics.put("zeroDeclarationOpenCount", zeroDeclarationOpenCount);
        metrics.put("dueSoonCount", dueSoonCount);
        metrics.put("filingCompletionRate", completionRate);
        metrics.put("receiptGapCount", receiptGapCount);
        return metrics;
    }

    private Map<String, Object> risk(
        String key,
        String severity,
        String title,
        String description,
        String taxType,
        String period,
        String dueDate,
        Long taxItemId,
        String action,
        String policyBasis
    ) {
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("key", key);
        risk.put("severity", severity);
        risk.put("title", title);
        risk.put("description", description);
        risk.put("taxType", taxType);
        risk.put("taxTypeName", taxTypeName(taxType));
        risk.put("period", period);
        risk.put("dueDate", dueDate);
        risk.put("taxItemId", taxItemId);
        risk.put("action", action);
        risk.put("policyBasis", safeText(policyBasis, "CN-GD-SZ-STARTUP-LITE"));
        return risk;
    }

    private String dueDateFor(String period, String frequency) {
        YearMonth dueMonth = dueMonthFor(period, frequency);
        if (dueMonth.getYear() == 2026) {
            return OFFICIAL_2026_DUE_DATES.get(dueMonth.getMonthValue());
        }
        LocalDate fifteenth = dueMonth.atDay(15);
        DayOfWeek day = fifteenth.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY) {
            return fifteenth.plusDays(2).toString();
        }
        if (day == DayOfWeek.SUNDAY) {
            return fifteenth.plusDays(1).toString();
        }
        return fifteenth.toString();
    }

    private YearMonth dueMonthFor(String period, String frequency) {
        if ("quarterly".equals(frequency) || period.toUpperCase().contains("Q")) {
            String[] parts = period.toUpperCase().split("-Q");
            int year = Integer.parseInt(parts[0]);
            int quarter = Integer.parseInt(parts[1]);
            int quarterEndMonth = quarter * 3;
            return YearMonth.of(year, quarterEndMonth).plusMonths(1);
        }
        return YearMonth.parse(period).plusMonths(1);
    }

    private int quarterOf(YearMonth month) {
        return ((month.getMonthValue() - 1) / 3) + 1;
    }

    private String calendarRiskLevel(String dueDate, String status, String filingStatus) {
        if ("accepted".equals(filingStatus) || "paid".equals(status)) {
            return "low";
        }
        LocalDate due = parseDate(dueDate).orElse(LocalDate.now());
        if (due.isBefore(LocalDate.now())) {
            return "high";
        }
        if (!due.isAfter(LocalDate.now().plusDays(15)) || "missing".equals(status)) {
            return "medium";
        }
        return "low";
    }

    private String dueLabel(LocalDate dueDate) {
        LocalDate today = LocalDate.now();
        long days = ChronoUnit.DAYS.between(today, dueDate);
        if (days < 0) {
            return "逾期 " + Math.abs(days) + " 天";
        }
        if (days == 0) {
            return "今日截止";
        }
        return days + " 天后截止";
    }

    private boolean isSmallScale(Company company) {
        String value = safeText(company.taxpayerType, "").toLowerCase();
        return value.isBlank() || value.contains("小规模") || value.contains("small");
    }

    private boolean isRequiredTaxType(String taxType) {
        return List.of("vat", "corporate_income_tax", "personal_income_tax", "surcharge", "stamp_duty").contains(taxType);
    }

    private String taxTypeName(String taxType) {
        return switch (safeText(taxType, "")) {
            case "vat" -> "增值税";
            case "corporate_income_tax" -> "企业所得税";
            case "personal_income_tax" -> "个税代扣";
            case "surcharge" -> "附加税费";
            case "stamp_duty" -> "印花税";
            default -> safeText(taxType, "税务事项");
        };
    }

    private int severityWeight(String severity) {
        return switch (severity) {
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    private Optional<LocalDate> parseDate(String value) {
        try {
            return isBlank(value) ? Optional.empty() : Optional.of(LocalDate.parse(value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<YearMonth> parseYearMonth(String value) {
        try {
            return isBlank(value) || value.length() < 7 ? Optional.empty() : Optional.of(YearMonth.parse(value.substring(0, 7)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private String taxPeriodFor(String issueDate) {
        return parseYearMonth(issueDate).map(YearMonth::toString).orElse(YearMonth.now().toString());
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String moneyText(BigDecimal value) {
        return "¥" + money(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safeText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }
}
