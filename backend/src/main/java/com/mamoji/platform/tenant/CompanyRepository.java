package com.mamoji.platform.tenant;

import java.util.List;
import java.util.Optional;

/** Authoritative persistence port for tenant company profiles. */
public interface CompanyRepository {
    List<Company> findAll();

    Optional<Company> findById(long id);

    boolean existsAny();

    Company insert(Company company);

    void update(Company company);
}
