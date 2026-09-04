package com.mamoji.bootstrap;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Invokes the single transactional production bootstrap command during startup. */
@Component("enterpriseDataInitializer")
@ConditionalOnProperty(name = "mamoji.bootstrap.mode", havingValue = "bootstrap")
@DependsOn("productionReadinessGuard")
public class EnterpriseDataInitializer {
    private final ProductionBootstrapCommand bootstrap;
    private final ProductionBootstrapCommand.Request request;

    public EnterpriseDataInitializer(
        ProductionBootstrapCommand bootstrap,
        @Value("${mamoji.bootstrap.admin-email:test@mamoji.com}") String adminEmail,
        @Value("${mamoji.bootstrap.admin-password:123456}") String adminPassword,
        @Value("${mamoji.bootstrap.admin-nickname:Mamoji 公司管理员}") String adminNickname,
        @Value("${mamoji.security.password.min-length:12}") int passwordMinLength,
        @Value("${mamoji.security.password.require-complexity:false}") boolean passwordRequireComplexity,
        @Value("${mamoji.bootstrap.company-name:我的公司}") String companyName,
        @Value("${mamoji.bootstrap.company-credit-code:}") String companyCreditCode,
        @Value("${mamoji.bootstrap.company-industry:未设置}") String companyIndustry,
        @Value("${mamoji.bootstrap.company-taxpayer-type:未设置}") String companyTaxpayerType,
        @Value("${mamoji.bootstrap.company-currency:CNY}") String companyCurrency
    ) {
        this.bootstrap = bootstrap;
        this.request = new ProductionBootstrapCommand.Request(
            adminEmail,
            adminPassword,
            adminNickname,
            passwordMinLength,
            passwordRequireComplexity,
            companyName,
            companyCreditCode,
            companyIndustry,
            companyTaxpayerType,
            companyCurrency
        );
    }

    @PostConstruct
    void initialize() {
        bootstrap.execute(request);
    }
}
