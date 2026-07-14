package com.mamoji.service;

import com.mamoji.domain.Models.Company;
import com.mamoji.domain.Models.User;
import com.mamoji.service.support.AccessControlService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GlobalSearchService {
    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;

    public GlobalSearchService(JdbcTemplate jdbc, AccessControlService accessControl) {
        this.jdbc = jdbc;
        this.accessControl = accessControl;
    }

    public SearchResponse search(String authorization, Long companyId, String keyword, Integer requestedLimit) {
        User user = accessControl.requireUser(authorization);
        Company company = accessControl.resolveCompany(user, companyId);
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 2) {
            return new SearchResponse(normalized, List.of());
        }
        int limit = Math.max(1, Math.min(requestedLimit == null ? 5 : requestedLimit, 10));
        String pattern = "%" + normalized + "%";
        List<SearchResult> results = new ArrayList<>();

        results.addAll(jdbc.query("""
            SELECT t.id, COALESCE(NULLIF(t.note, ''), '流水 #' || t.id) AS title,
                   t.date || ' · ' || c.name || ' · ¥' || t.amount AS subtitle
            FROM transactions t
            LEFT JOIN categories c ON c.id = t.category_id
            LEFT JOIN accounts a ON a.id = t.account_id
            WHERE t.company_id = ? AND (
                LOWER(COALESCE(t.note, '')) LIKE ? OR LOWER(COALESCE(c.name, '')) LIKE ?
                OR LOWER(COALESCE(a.name, '')) LIKE ? OR t.amount LIKE ?
            )
            ORDER BY t.date DESC, t.id DESC LIMIT ?
            """, (rs, rowNum) -> result("transaction", rs.getLong("id"), rs.getString("title"), rs.getString("subtitle"),
                "/transactions?keyword=" + url(keyword)), company.id, pattern, pattern, pattern, pattern, limit));

        results.addAll(jdbc.query("""
            SELECT id, title, voucher_no || ' · ' || counterparty || ' · ¥' || amount AS subtitle
            FROM receipt_vouchers
            WHERE company_id = ? AND (
                LOWER(title) LIKE ? OR LOWER(voucher_no) LIKE ? OR LOWER(counterparty) LIKE ?
                OR LOWER(COALESCE(business_purpose, '')) LIKE ?
            )
            ORDER BY updated_at DESC, id DESC LIMIT ?
            """, (rs, rowNum) -> result("receipt", rs.getLong("id"), rs.getString("title"), rs.getString("subtitle"),
                "/receipts?keyword=" + url(keyword)), company.id, pattern, pattern, pattern, pattern, limit));

        results.addAll(jdbc.query("""
            SELECT id, name AS title,
                   COALESCE(bank, '账户') || ' · ' || COALESCE(NULLIF(account_no, ''), '未设置账号') || ' · ¥' || balance AS subtitle
            FROM accounts
            WHERE company_id = ? AND status = 1 AND (
                LOWER(name) LIKE ? OR LOWER(COALESCE(bank, '')) LIKE ? OR LOWER(COALESCE(account_no, '')) LIKE ?
            )
            ORDER BY updated_at DESC, id DESC LIMIT ?
            """, (rs, rowNum) -> result("account", rs.getLong("id"), rs.getString("title"), rs.getString("subtitle"),
                "/accounts?keyword=" + url(keyword)), company.id, pattern, pattern, pattern, limit));

        results.addAll(jdbc.query("""
            SELECT id, name AS title, period || ' · ' || tax_type || ' · ¥' || tax_amount AS subtitle
            FROM tax_items
            WHERE company_id = ? AND (
                LOWER(name) LIKE ? OR LOWER(tax_type) LIKE ? OR LOWER(period) LIKE ?
                OR LOWER(COALESCE(responsible_person, '')) LIKE ?
            )
            ORDER BY due_date DESC, id DESC LIMIT ?
            """, (rs, rowNum) -> result("tax", rs.getLong("id"), rs.getString("title"), rs.getString("subtitle"),
                "/tax?keyword=" + url(keyword)), company.id, pattern, pattern, pattern, pattern, limit));

        if (accessControl.canReadPeopleDirectory(user, company.id)) {
            results.addAll(jdbc.query("""
                SELECT id, name AS title,
                       COALESCE(NULLIF(position, ''), '员工') || ' · ' || COALESCE(NULLIF(email, ''), '未设置邮箱') AS subtitle
                FROM employees
                WHERE company_id = ? AND (
                    LOWER(name) LIKE ? OR LOWER(COALESCE(employee_no, '')) LIKE ?
                    OR LOWER(COALESCE(position, '')) LIKE ? OR LOWER(COALESCE(email, '')) LIKE ?
                )
                ORDER BY updated_at DESC, id DESC LIMIT ?
                """, (rs, rowNum) -> result("employee", rs.getLong("id"), rs.getString("title"), rs.getString("subtitle"),
                    "/hr/organization?keyword=" + url(keyword)), company.id, pattern, pattern, pattern, pattern, limit));
        }
        return new SearchResponse(keyword.trim(), results);
    }

    private SearchResult result(String type, long id, String title, String subtitle, String path) {
        return new SearchResult(type, id, title, subtitle, path);
    }

    private String url(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value.trim(), java.nio.charset.StandardCharsets.UTF_8);
    }

    public record SearchResult(String type, long id, String title, String subtitle, String path) {}
    public record SearchResponse(String keyword, List<SearchResult> results) {}
}
