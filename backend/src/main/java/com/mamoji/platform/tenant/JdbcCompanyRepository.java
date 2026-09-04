package com.mamoji.platform.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCompanyRepository implements CompanyRepository {
    private static final String COLUMNS = """
        id, version, name, entity_type, credit_code, industry, taxpayer_type, currency,
        country, province, city, district, registered_address, operating_region,
        tax_authority, policy_profile_key, fiscal_year_start_month, owner_id, created_at, updated_at
        """;

    private final JdbcTemplate jdbc;

    public JdbcCompanyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Company> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM companies ORDER BY id", this::map);
    }

    @Override
    public Optional<Company> findById(long id) {
        return jdbc.query(
            "SELECT " + COLUMNS + " FROM companies WHERE id = ?",
            this::map,
            id
        ).stream().findFirst();
    }

    @Override
    public boolean existsAny() {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM companies)", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Company insert(Company company) {
        CompanyProfilePolicy.normalizeAndValidate(company);
        String now = OffsetDateTime.now().toString();
        company.version = 0;
        company.createdAt = isBlank(company.createdAt) ? now : OffsetDateTime.parse(company.createdAt).toString();
        company.updatedAt = isBlank(company.updatedAt) ? company.createdAt : OffsetDateTime.parse(company.updatedAt).toString();
        Long id = jdbc.queryForObject("""
            INSERT INTO companies (
                version, name, entity_type, credit_code, industry, taxpayer_type, currency,
                country, province, city, district, registered_address, operating_region,
                tax_authority, policy_profile_key, fiscal_year_start_month, owner_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, Long.class, company.version, company.name, company.entityType, company.creditCode,
            company.industry, company.taxpayerType, company.currency, company.country, company.province,
            company.city, company.district, company.registeredAddress, company.operatingRegion,
            company.taxAuthority, company.policyProfileKey, company.fiscalYearStartMonth, company.ownerId,
            OffsetDateTime.parse(company.createdAt), OffsetDateTime.parse(company.updatedAt));
        if (id == null) throw new IllegalStateException("Database did not return a generated company id");
        company.id = id;
        return company;
    }

    @Override
    public void update(Company company) {
        CompanyProfilePolicy.normalizeAndValidate(company);
        company.updatedAt = OffsetDateTime.now().toString();
        int updated = jdbc.update("""
            UPDATE companies SET
                version = version + 1, name = ?, entity_type = ?, credit_code = ?, industry = ?,
                taxpayer_type = ?, currency = ?, country = ?, province = ?, city = ?, district = ?,
                registered_address = ?, operating_region = ?, tax_authority = ?, policy_profile_key = ?,
                fiscal_year_start_month = ?, updated_at = ?
            WHERE id = ? AND version = ?
            """, company.name, company.entityType, company.creditCode, company.industry, company.taxpayerType,
            company.currency, company.country, company.province, company.city, company.district,
            company.registeredAddress, company.operatingRegion, company.taxAuthority, company.policyProfileKey,
            company.fiscalYearStartMonth, OffsetDateTime.parse(company.updatedAt), company.id, company.version);
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Company was changed by another request: " + company.id);
        }
        company.version += 1;
    }

    private Company map(ResultSet result, int rowNumber) throws SQLException {
        Company company = new Company();
        company.id = result.getLong("id");
        company.version = result.getLong("version");
        company.name = result.getString("name");
        company.entityType = result.getString("entity_type");
        company.creditCode = result.getString("credit_code");
        company.industry = result.getString("industry");
        company.taxpayerType = result.getString("taxpayer_type");
        company.currency = result.getString("currency");
        company.country = result.getString("country");
        company.province = result.getString("province");
        company.city = result.getString("city");
        company.district = result.getString("district");
        company.registeredAddress = result.getString("registered_address");
        company.operatingRegion = result.getString("operating_region");
        company.taxAuthority = result.getString("tax_authority");
        company.policyProfileKey = result.getString("policy_profile_key");
        company.fiscalYearStartMonth = result.getInt("fiscal_year_start_month");
        company.ownerId = result.getLong("owner_id");
        company.createdAt = result.getObject("created_at", OffsetDateTime.class).toString();
        company.updatedAt = result.getObject("updated_at", OffsetDateTime.class).toString();
        return company;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
