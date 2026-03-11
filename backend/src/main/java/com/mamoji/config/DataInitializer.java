package com.mamoji.config;

import com.mamoji.entity.Account;
import com.mamoji.entity.Budget;
import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.entity.User;
import com.mamoji.repository.AccountRepository;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Initializes default seed data for local/dev environments.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer {

    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Creates default categories, test user, default accounts/budget, and sample transactions.
     */
    @PostConstruct
    public void initDefaultData() {
        // 初始化分类
        if (categoryRepository.count() == 0) {
            // 收入分类 - PRD预置
            List<Category> incomeCategories = List.of(
                Category.builder().name("工资").type(1).icon("salary").color("#27AE60").isSystem(1).build(),
                Category.builder().name("奖金").type(1).icon("bonus").color("#E74C3C").isSystem(1).build(),
                Category.builder().name("投资").type(1).icon("investment").color("#3498DB").isSystem(1).build(),
                Category.builder().name("其他收入").type(1).icon("other_income").color("#9B59B6").isSystem(1).build()
            );

            // 支出分类 - PRD预置
            List<Category> expenseCategories = List.of(
                Category.builder().name("餐饮").type(2).icon("food").color("#FF6B6B").isSystem(1).build(),
                Category.builder().name("交通").type(2).icon("transport").color("#4ECDC4").isSystem(1).build(),
                Category.builder().name("购物").type(2).icon("shopping").color("#45B7D1").isSystem(1).build(),
                Category.builder().name("娱乐").type(2).icon("entertainment").color("#F7DC6F").isSystem(1).build(),
                Category.builder().name("居住").type(2).icon("housing").color("#BB8FCE").isSystem(1).build(),
                Category.builder().name("生活").type(2).icon("living").color("#85C1E9").isSystem(1).build(),
                Category.builder().name("医疗").type(2).icon("medical").color("#58D68D").isSystem(1).build(),
                Category.builder().name("教育").type(2).icon("education").color("#E67E22").isSystem(1).build(),
                Category.builder().name("人情").type(2).icon("social").color("#E91E63").isSystem(1).build(),
                Category.builder().name("其他支出").type(2).icon("other_expense").color("#ABB2B9").isSystem(1).build()
            );

            categoryRepository.saveAll(incomeCategories);
            categoryRepository.saveAll(expenseCategories);
            log.info("Default categories initialized");
        }

        // 初始化测试用户
        if (!userRepository.existsByEmail("test@mamoji.com")) {
            User testUser = User.builder()
                .email("test@mamoji.com")
                .passwordHash(passwordEncoder.encode("123456"))
                .nickname("测试用户")
                .role(1)
                .permissions(15)
                .build();
            testUser = userRepository.save(testUser);
            log.info("Test user created: test@mamoji.com / 123456");

            // 初始化默认账户
            List<Account> defaultAccounts = List.of(
                Account.builder()
                    .name("现金")
                    .type("cash")
                    .balance(BigDecimal.ZERO)
                    .userId(testUser.getId())
                    .includeInNetWorth(true)
                    .status(1)
                    .build(),
                Account.builder()
                    .name("银行卡")
                    .type("bank")
                    .balance(BigDecimal.ZERO)
                    .userId(testUser.getId())
                    .includeInNetWorth(true)
                    .status(1)
                    .build(),
                Account.builder()
                    .name("支付宝")
                    .type("digital")
                    .balance(BigDecimal.ZERO)
                    .userId(testUser.getId())
                    .includeInNetWorth(true)
                    .status(1)
                    .build(),
                Account.builder()
                    .name("微信支付")
                    .type("digital")
                    .balance(BigDecimal.ZERO)
                    .userId(testUser.getId())
                    .includeInNetWorth(true)
                    .status(1)
                    .build()
            );
            accountRepository.saveAll(defaultAccounts);
            log.info("Default accounts initialized");

            // 初始化默认预算 - 本月总预算
            LocalDate now = LocalDate.now();
            LocalDate startOfMonth = now.withDayOfMonth(1);
            LocalDate endOfMonth = now.withDayOfMonth(now.lengthOfMonth());

            Budget defaultBudget = Budget.builder()
                .name(now.getYear() + "年" + now.getMonthValue() + "月预算")
                .amount(new BigDecimal("5000"))
                .startDate(startOfMonth)
                .endDate(endOfMonth)
                .warningThreshold(85)
                .spent(BigDecimal.ZERO)
                .userId(testUser.getId())
                .status(1)
                .build();
            budgetRepository.save(defaultBudget);
            log.info("Default budget initialized: " + defaultBudget.getName());

            // 生成测试交易数据
            generateTestTransactions(testUser.getId());
        }
    }

    /**
     * Generates deterministic sample transactions for dashboard/demo views.
     */
    private void generateTestTransactions(Long userId) {
        if (transactionRepository.count() > 0) {
            return; // 已有数据，跳过
        }

        LocalDate today = LocalDate.now();
        List<Transaction> transactions = new ArrayList<>();
        Random random = new Random(42); // 固定种子，保证每次生成相同数据

        // 获取分类
        List<Category> incomeCategories = categoryRepository.findByTypeOrderByIdAsc(1);
        List<Category> expenseCategories = categoryRepository.findByTypeOrderByIdAsc(2);

        // 本月交易 - 收入
        for (int day = 1; day <= today.getDayOfMonth(); day++) {
            LocalDate date = today.withDayOfMonth(day);

            // 工资 - 每月1号
            if (day == 1) {
                transactions.add(createTransaction(userId, date, incomeCategories.get(0), 1,
                    new BigDecimal("15000"), "月工资"));
            }

            // 奖金 - 每月15号
            if (day == 15) {
                transactions.add(createTransaction(userId, date, incomeCategories.get(1), 1,
                    new BigDecimal("3000"), "项目奖金"));
            }

            // 投资收入 - 随机
            if (random.nextInt(10) < 2) {
                transactions.add(createTransaction(userId, date, incomeCategories.get(2), 1,
                    new BigDecimal(String.format("%.2f", random.nextDouble() * 500 + 100)),
                    "投资收益"));
            }
        }

        // 本月交易 - 支出
        // 餐饮 - 每天
        for (int day = 1; day <= today.getDayOfMonth(); day++) {
            LocalDate date = today.withDayOfMonth(day);
            transactions.add(createTransaction(userId, date, expenseCategories.get(0), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 80 + 20)),
                "午餐"));
            transactions.add(createTransaction(userId, date, expenseCategories.get(0), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 60 + 15)),
                "晚餐"));
        }

        // 交通 - 每周
        for (int day = 1; day <= today.getDayOfMonth(); day += 7) {
            LocalDate date = today.withDayOfMonth(Math.min(day, today.getDayOfMonth()));
            transactions.add(createTransaction(userId, date, expenseCategories.get(1), 2,
                new BigDecimal("4"), "地铁"));
            transactions.add(createTransaction(userId, date, expenseCategories.get(1), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 30 + 10)),
                "打车"));
        }

        // 购物 - 每周
        for (int day = 5; day <= today.getDayOfMonth(); day += 7) {
            LocalDate date = today.withDayOfMonth(Math.min(day, today.getDayOfMonth()));
            transactions.add(createTransaction(userId, date, expenseCategories.get(2), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 200 + 50)),
                "网购"));
        }

        // 娱乐 - 每周末
        for (int day = 6; day <= today.getDayOfMonth(); day += 7) {
            LocalDate date = today.withDayOfMonth(Math.min(day, today.getDayOfMonth()));
            transactions.add(createTransaction(userId, date, expenseCategories.get(3), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 150 + 50)),
                "电影/娱乐"));
        }

        // 居住 - 每月1号
        transactions.add(createTransaction(userId, today.withDayOfMonth(1), expenseCategories.get(4), 2,
            new BigDecimal("3000"), "房租"));

        // 生活 - 每周
        for (int day = 3; day <= today.getDayOfMonth(); day += 7) {
            LocalDate date = today.withDayOfMonth(Math.min(day, today.getDayOfMonth()));
            transactions.add(createTransaction(userId, date, expenseCategories.get(5), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 100 + 80)),
                "日用品"));
        }

        // 人情 - 偶尔
        if (random.nextInt(5) < 2) {
            transactions.add(createTransaction(userId, today.minusDays(random.nextInt(15)), expenseCategories.get(8), 2,
                new BigDecimal(String.format("%.2f", random.nextDouble() * 500 + 200)),
                "红包/人情"));
        }

        // 电费水费
        transactions.add(createTransaction(userId, today.withDayOfMonth(10), expenseCategories.get(5), 2,
            new BigDecimal("150"), "电费"));
        transactions.add(createTransaction(userId, today.withDayOfMonth(10), expenseCategories.get(5), 2,
            new BigDecimal("80"), "水费"));

        transactionRepository.saveAll(transactions);
        log.info("Generated {} test transactions", transactions.size());
    }

    /**
     * Helper for building one transaction record.
     */
    private Transaction createTransaction(Long userId, LocalDate date, Category category, Integer type,
                                         BigDecimal amount, String remark) {
        return Transaction.builder()
            .userId(userId)
            .categoryId(category.getId())
            .accountId(1L)
            .type(type)
            .amount(amount)
            .date(date)
            .remark(remark)
            .build();
    }
}
