package com.mamoji.platform.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcCompanyRepositoryTest {
    @Test
    void insertNormalizesAndBindsTypedTenantProfile() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcCompanyRepository repository = new JdbcCompanyRepository(jdbc);
        Company company = company();

        Company saved = repository.insert(company);

        assertEquals(41, saved.id);
        assertEquals(0, saved.version);
        assertEquals("Acme", saved.name);
        assertEquals("company", saved.entityType);
        assertEquals("CN-001", saved.creditCode);
        assertEquals("CNY", saved.currency);
        assertNotNull(saved.createdAt);
        assertInstanceOf(OffsetDateTime.class, jdbc.arguments[17]);
        assertInstanceOf(OffsetDateTime.class, jdbc.arguments[18]);
    }

    @Test
    void updateUsesOptimisticVersionAndAdvancesReturnedProfile() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcCompanyRepository repository = new JdbcCompanyRepository(jdbc);
        Company company = company();
        company.id = 41;
        company.version = 3;

        repository.update(company);

        assertEquals(4, company.version);
        assertEquals(41L, jdbc.arguments[16]);
        assertEquals(3L, jdbc.arguments[17]);
    }

    @Test
    void staleUpdateIsRejected() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        jdbc.updateResult = 0;
        JdbcCompanyRepository repository = new JdbcCompanyRepository(jdbc);
        Company company = company();
        company.id = 41;
        company.version = 3;

        assertThrows(OptimisticLockingFailureException.class, () -> repository.update(company));
        assertEquals(3, company.version);
    }

    @Test
    void allCompaniesUseOneAuthoritativeQuery() {
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        JdbcCompanyRepository repository = new JdbcCompanyRepository(jdbc);

        List<Company> companies = repository.findAll();

        assertEquals(1, companies.size());
        assertEquals("SELECT", jdbc.sql.substring(0, 6));
    }

    private Company company() {
        Company company = new Company();
        company.ownerId = 7;
        company.name = " Acme ";
        company.entityType = " COMPANY ";
        company.creditCode = " cn-001 ";
        company.industry = " Software ";
        company.taxpayerType = " General ";
        company.currency = " cny ";
        company.country = " China ";
        company.province = " Guangdong ";
        company.city = " Shenzhen ";
        company.district = " Nanshan ";
        company.registeredAddress = " 1 Road ";
        company.operatingRegion = " China/Guangdong/Shenzhen ";
        company.taxAuthority = " Shenzhen Tax ";
        company.policyProfileKey = " CN-GD-SZ ";
        company.fiscalYearStartMonth = 1;
        return company;
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private Object[] arguments;
        private String sql;
        private int updateResult = 1;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql = sql;
            this.arguments = args;
            if (requiredType == Boolean.class) return (T) Boolean.TRUE;
            return (T) Long.valueOf(41);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            this.sql = sql;
            this.arguments = new Object[0];
            return (List<T>) List.of(new Company());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return (List<T>) List.of(new Company());
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.arguments = args;
            return updateResult;
        }
    }
}
