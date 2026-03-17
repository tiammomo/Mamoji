package com.mamoji.controller;

import com.mamoji.common.api.ApiResponses;
import com.mamoji.common.exception.BadRequestException;
import com.mamoji.common.exception.ForbiddenOperationException;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.common.status.BudgetStatus;
import com.mamoji.entity.Budget;
import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.entity.User;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.security.AuthenticationUser;
import com.mamoji.service.BudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transaction management and transaction-risk controller.
 *
 * <p>This controller handles transaction CRUD, refund flow, input validation,
 * ownership checks, budget snapshot refresh, and structured risk assessment
 * returned to the frontend after write operations.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {
    private static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("10000000");
    private static final BigDecimal LARGE_EXPENSE_THRESHOLD = new BigDecimal("3000");
    private static final BigDecimal CRITICAL_EXPENSE_THRESHOLD = new BigDecimal("10000");
    private static final BigDecimal EXPENSE_INCOME_RATIO_WARNING = new BigDecimal("1.20");
    private static final BigDecimal CATEGORY_SPIKE_RATIO = new BigDecimal("2.00");
    private static final BigDecimal CATEGORY_SPIKE_DELTA_THRESHOLD = new BigDecimal("1000");
    private static final BigDecimal NEW_CATEGORY_LARGE_EXPENSE_THRESHOLD = new BigDecimal("3000");
    private static final int MAX_REMARK_LENGTH = 200;
    private static final int MAX_BACKDATED_YEARS = 20;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int HIGH_FREQUENCY_EXPENSE_THRESHOLD = 12;
    private static final int DUPLICATE_EXPENSE_COUNT_THRESHOLD = 1;
    private static final int MAX_QUERY_RANGE_DAYS = 3660;
    private static final String TX_NOT_FOUND = "Transaction not found.";
    private static final String TX_FORBIDDEN = "You do not have permission to access this transaction.";

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;
    private final BudgetService budgetService;

    /**
     * Returns a paginated transaction list with optional type and date-range filters.
     *
     * <p>Guardrails include page-size limits, supported-type validation and query-range validation.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getTransactions(
        @AuthenticationUser User user,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) Integer type,
        @RequestParam(required = false) String types,
        @RequestParam(required = false) String startDate,
        @RequestParam(required = false) String endDate
    ) {
        validatePaging(page, pageSize);
        if (type != null && !isSupportedType(type, true)) {
            throw new BadRequestException("Unsupported transaction type: " + type);
        }
        validateQueryDateRange(startDate, endDate);
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);
        Page<Transaction> transactionPage = queryTransactions(user.getId(), pageRequest, type, parseTypes(types), startDate, endDate);

        Map<String, Object> data = new HashMap<>();
        data.put("list", transactionPage.getContent().stream().map(this::toMap).toList());
        data.put("total", transactionPage.getTotalElements());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return ApiResponses.ok(data);
    }

    /**
     * Creates one transaction and returns the saved snapshot plus risk assessment.
     *
     * <p>Expense transactions try to bind to an active budget first, then refresh affected budget snapshots.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTransaction(
        @AuthenticationUser User user,
        @RequestBody Map<String, Object> request
    ) {
        int type = parseRequiredType(request.get("type"), false);
        BigDecimal amount = parseRequiredAmount(request.get("amount"));
        Long categoryId = parseRequiredLong(request.get("categoryId"), "categoryId");
        LocalDate transactionDate = parseRequiredDate(request.get("date"), "date");
        validateCategoryForType(categoryId, type, user);
        validateTransactionDate(transactionDate);
        validateRemarkLength(request.get("remark"));

        Transaction transaction = Transaction.builder()
            .userId(user.getId())
            .familyId(user.getFamilyId())
            .type(type)
            .amount(amount)
            .categoryId(categoryId)
            .date(transactionDate)
            .remark(request.get("remark") != null ? request.get("remark").toString() : null)
            .build();

        if (type == 2) {
            budgetService.matchActiveBudgetForExpense(user.getId(), categoryId, transactionDate)
                .ifPresent(budget -> transaction.setBudgetId(budget.getId()));
        }

        Transaction saved = transactionRepository.save(transaction);
        refreshAffectedBudgets(user.getId(), null, saved);

        Map<String, Object> data = toMap(saved);
        data.put("risk", buildTransactionRisk(user.getId(), saved));
        return ApiResponses.ok(data);
    }

    /**
     * Updates one transaction.
     *
     * <p>Refund transactions are immutable so the refund audit chain cannot be broken by later edits.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTransaction(
        @AuthenticationUser User user,
        @PathVariable Long id,
        @RequestBody Map<String, Object> request
    ) {
        Transaction transaction = findOwnedTransaction(id, user.getId());
        if (transaction.getType() != null && transaction.getType() == 3 && !request.isEmpty()) {
            throw new BadRequestException("Refund transactions are immutable.");
        }
        Transaction before = snapshot(transaction);

        if (request.get("type") != null) {
            transaction.setType(parseRequiredType(request.get("type"), false));
        }
        if (request.get("amount") != null) {
            transaction.setAmount(parseRequiredAmount(request.get("amount")));
        }
        if (request.get("categoryId") != null) {
            transaction.setCategoryId(parseRequiredLong(request.get("categoryId"), "categoryId"));
        }
        if (request.get("date") != null) {
            transaction.setDate(parseRequiredDate(request.get("date"), "date"));
        }
        if (request.get("remark") != null) {
            transaction.setRemark(request.get("remark").toString());
        }

        if (request.get("type") != null || request.get("categoryId") != null) {
            if (transaction.getCategoryId() == null) {
                throw new BadRequestException("categoryId is required.");
            }
            validateCategoryForType(transaction.getCategoryId(), transaction.getType(), user);
        }

        validateTransactionDate(transaction.getDate());
        validateRemarkLength(transaction.getRemark());

        if (transaction.getType() == 2) {
            budgetService.matchActiveBudgetForExpense(user.getId(), transaction.getCategoryId(), transaction.getDate())
                .ifPresentOrElse(
                    budget -> transaction.setBudgetId(budget.getId()),
                    () -> transaction.setBudgetId(null)
                );
        } else {
            transaction.setBudgetId(null);
        }

        Transaction saved = transactionRepository.save(transaction);
        refreshAffectedBudgets(user.getId(), before, saved);

        Map<String, Object> data = toMap(saved);
        data.put("risk", buildTransactionRisk(user.getId(), saved));
        return ApiResponses.ok(data);
    }

    /**
     * Deletes one transaction after audit-safety checks.
     *
     * <p>Refund records and expenses that already have refund history cannot be deleted.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTransaction(
        @AuthenticationUser User user,
        @PathVariable Long id
    ) {
        Transaction existing = findOwnedTransaction(id, user.getId());
        validateDeleteAllowed(existing);
        transactionRepository.delete(existing);
        refreshAffectedBudgets(user.getId(), existing, null);
        return ApiResponses.ok(null);
    }

    /**
     * Returns paginated expense transactions that may still be refunded.
     */
    @GetMapping("/refundable")
    public ResponseEntity<Map<String, Object>> getRefundableTransactions(
        @AuthenticationUser User user,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        validatePaging(page, pageSize);
        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);
        Page<Transaction> transactionPage = transactionRepository.findByUserIdAndTypeOrderByDateDesc(user.getId(), 2, pageRequest);

        Map<String, Object> data = new HashMap<>();
        data.put("list", transactionPage.getContent().stream().map(this::toRefundableMap).toList());
        data.put("total", transactionPage.getTotalElements());
        data.put("page", page);
        data.put("pageSize", pageSize);
        return ApiResponses.ok(data);
    }

    /**
     * Creates a refund transaction.
     *
     * <p>Validates refund amount, refundable remainder and refund date,
     * then updates the original expense and refreshes related budget snapshots.
     */
    @PostMapping("/{id}/refund")
    public ResponseEntity<Map<String, Object>> refundTransaction(
        @AuthenticationUser User user,
        @PathVariable Long id,
        @RequestBody Map<String, Object> request
    ) {
        BigDecimal refundAmount = parseRequiredAmount(request.get("amount"));
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Refund amount must be greater than 0.");
        }

        Transaction originalTransaction = findOwnedTransaction(id, user.getId());
        if (originalTransaction.getType() != 2) {
            throw new BadRequestException("Only expense transactions can be refunded.");
        }

        BigDecimal alreadyRefunded = originalTransaction.getRefundedAmount() != null
            ? originalTransaction.getRefundedAmount()
            : BigDecimal.ZERO;
        BigDecimal refundableAmount = originalTransaction.getAmount().subtract(alreadyRefunded);
        if (refundAmount.compareTo(refundableAmount) > 0) {
            throw new BadRequestException("Refund amount exceeds refundable amount.");
        }

        LocalDate refundDate = parseRequiredDate(request.get("date"), "date");
        validateTransactionDate(refundDate);
        if (originalTransaction.getDate() != null && refundDate.isBefore(originalTransaction.getDate())) {
            throw new BadRequestException("Refund date cannot be before original transaction date.");
        }

        originalTransaction.setRefundedAmount(alreadyRefunded.add(refundAmount));
        transactionRepository.save(originalTransaction);

        Transaction refundTransaction = Transaction.builder()
            .userId(user.getId())
            .familyId(user.getFamilyId())
            .type(3)
            .amount(refundAmount)
            .categoryId(originalTransaction.getCategoryId())
            .accountId(originalTransaction.getAccountId())
            .date(refundDate)
            .remark("Refund: " + (originalTransaction.getRemark() == null ? "" : originalTransaction.getRemark()))
            .originalTransactionId(originalTransaction.getId())
            .build();

        Transaction savedRefund = transactionRepository.save(refundTransaction);
        refreshAffectedBudgets(user.getId(), originalTransaction, savedRefund);

        Map<String, Object> data = toMap(savedRefund);
        data.put("risk", buildTransactionRisk(user.getId(), originalTransaction));
        return ApiResponses.ok(data);
    }

    /**
     * Dispatches transaction list queries to the appropriate repository method by filter combination.
     */
    private Page<Transaction> queryTransactions(
        Long userId,
        PageRequest pageRequest,
        Integer type,
        List<Integer> typeList,
        String startDate,
        String endDate
    ) {
        if (hasDateRange(startDate, endDate)) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            if (typeList != null && !typeList.isEmpty()) {
                return transactionRepository.findByUserIdAndTypeInAndDateBetweenOrderByDateDesc(userId, typeList, start, end, pageRequest);
            }
            if (type != null) {
                return transactionRepository.findByUserIdAndTypeAndDateBetweenOrderByDateDesc(userId, type, start, end, pageRequest);
            }
            return transactionRepository.findByUserIdAndDateBetweenOrderByDateDesc(userId, start, end, pageRequest);
        }
        if (typeList != null && !typeList.isEmpty()) {
            return transactionRepository.findByUserIdAndTypeInOrderByDateDesc(userId, typeList, pageRequest);
        }
        if (type != null) {
            return transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, type, pageRequest);
        }
        return transactionRepository.findByUserIdOrderByDateDesc(userId, pageRequest);
    }

    private boolean hasDateRange(String startDate, String endDate) {
        return startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty();
    }

    /**
     * Validates date-range filter rules.
     *
     * <p>Both ends must exist together, start cannot be after end, and the range is capped.
     */
    private void validateQueryDateRange(String startDate, String endDate) {
        if ((startDate == null || startDate.isBlank()) && (endDate == null || endDate.isBlank())) {
            return;
        }
        if (startDate == null || startDate.isBlank() || endDate == null || endDate.isBlank()) {
            throw new BadRequestException("startDate and endDate must be provided together.");
        }
        LocalDate start = parseRequiredDate(startDate, "startDate");
        LocalDate end = parseRequiredDate(endDate, "endDate");
        if (start.isAfter(end)) {
            throw new BadRequestException("startDate cannot be after endDate.");
        }
        long rangeDays = end.toEpochDay() - start.toEpochDay();
        if (rangeDays > MAX_QUERY_RANGE_DAYS) {
            throw new BadRequestException("Date range is too large.");
        }
    }

    /**
     * Loads one transaction and validates that it belongs to the current user.
     */
    private Transaction findOwnedTransaction(Long id, Long userId) {
        Transaction transaction = transactionRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(TX_NOT_FOUND));
        if (!transaction.getUserId().equals(userId)) {
            throw new ForbiddenOperationException(TX_FORBIDDEN);
        }
        return transaction;
    }

    /**
     * Enforces delete-time audit protection.
     *
     * <p>Refund rows are immutable, and any expense with refund history is also protected from deletion.
     */
    private void validateDeleteAllowed(Transaction transaction) {
        if (transaction.getType() != null && transaction.getType() == 3) {
            throw new BadRequestException("Refund transactions cannot be deleted.");
        }
        if (transaction.getType() != null && transaction.getType() == 2) {
            BigDecimal refundedAmount = defaultAmount(transaction.getRefundedAmount());
            if (refundedAmount.compareTo(BigDecimal.ZERO) > 0
                || transactionRepository.existsByOriginalTransactionId(transaction.getId())) {
                throw new BadRequestException("Expense transaction with refund history cannot be deleted.");
            }
        }
    }

    /**
     * Converts nullable amount to zero.
     */
    private BigDecimal defaultAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Adds refundable metadata used by the refund list page.
     */
    private Map<String, Object> toRefundableMap(Transaction transaction) {
        Map<String, Object> map = toMap(transaction);
        BigDecimal refundedAmount = transaction.getRefundedAmount() != null ? transaction.getRefundedAmount() : BigDecimal.ZERO;
        BigDecimal refundableAmount = transaction.getAmount().subtract(refundedAmount);
        map.put("refundableAmount", refundableAmount);
        map.put("refundedAmount", refundedAmount);
        map.put("canRefund", refundableAmount.compareTo(BigDecimal.ZERO) > 0);
        return map;
    }

    /**
     * Parses comma-separated transaction types and validates each item.
     */
    private List<Integer> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return null;
        }
        try {
            return Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Integer::parseInt)
                .peek(value -> {
                    if (!isSupportedType(value, true)) {
                        throw new BadRequestException("Unsupported transaction type: " + value);
                    }
                })
                .toList();
        } catch (NumberFormatException ex) {
            throw new BadRequestException("Invalid transaction type list.");
        }
    }

    /**
     * Maps transaction entity to API response shape consumed by the frontend.
     */
    private Map<String, Object> toMap(Transaction transaction) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", transaction.getId());
        map.put("type", transaction.getType());
        map.put("amount", transaction.getAmount());
        map.put("date", transaction.getDate());
        map.put("remark", transaction.getRemark());
        map.put("budgetId", transaction.getBudgetId());

        String categoryName = "Uncategorized";
        String categoryIcon = "category";
        if (transaction.getCategoryId() != null) {
            Optional<Category> category = categoryRepository.findById(transaction.getCategoryId());
            if (category.isPresent()) {
                categoryName = category.get().getName();
                categoryIcon = category.get().getIcon() != null ? category.get().getIcon() : "category";
            }
        }

        map.put("category", Map.of(
            "id", transaction.getCategoryId(),
            "name", categoryName,
            "icon", categoryIcon
        ));
        map.put("account", Map.of("id", 1, "name", "Cash"));
        map.put("user", Map.of("id", transaction.getUserId(), "nickname", "User"));

        if (transaction.getType() == 2) {
            BigDecimal refundedAmount = transaction.getRefundedAmount() != null ? transaction.getRefundedAmount() : BigDecimal.ZERO;
            map.put("refundedAmount", refundedAmount);
            map.put("refundableAmount", transaction.getAmount().subtract(refundedAmount));
            map.put("canRefund", transaction.getAmount().subtract(refundedAmount).compareTo(BigDecimal.ZERO) > 0);
        }
        if (transaction.getType() == 3 && transaction.getOriginalTransactionId() != null) {
            map.put("originalTransactionId", transaction.getOriginalTransactionId());
        }

        return map;
    }

    /**
     * Parses required transaction type and optionally allows refund type.
     */
    private int parseRequiredType(Object rawValue, boolean allowRefundType) {
        if (rawValue == null) {
            throw new BadRequestException("type is required.");
        }
        int type;
        try {
            type = rawValue instanceof Number
                ? ((Number) rawValue).intValue()
                : Integer.parseInt(rawValue.toString().trim());
        } catch (NumberFormatException ex) {
            throw new BadRequestException("type must be a valid integer.");
        }
        if (!isSupportedType(type, allowRefundType)) {
            throw new BadRequestException("Unsupported transaction type: " + type);
        }
        return type;
    }

    /**
     * Checks whether type value is supported in the current context.
     */
    private boolean isSupportedType(Integer type, boolean allowRefundType) {
        if (type == null) {
            return false;
        }
        return allowRefundType
            ? (type == 1 || type == 2 || type == 3)
            : (type == 1 || type == 2);
    }

    /**
     * Parses and validates amount precision and upper bound.
     */
    private BigDecimal parseRequiredAmount(Object rawValue) {
        if (rawValue == null) {
            throw new BadRequestException("amount is required.");
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(rawValue.toString().trim());
        } catch (NumberFormatException ex) {
            throw new BadRequestException("amount must be a valid number.");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("amount must be greater than 0.");
        }
        if (amount.compareTo(MAX_TRANSACTION_AMOUNT) > 0) {
            throw new BadRequestException("amount exceeds the allowed maximum.");
        }
        if (amount.scale() > 2) {
            throw new BadRequestException("amount supports at most 2 decimal places.");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Parses a required positive long value from request payload.
     */
    private Long parseRequiredLong(Object rawValue, String fieldName) {
        if (rawValue == null) {
            throw new BadRequestException(fieldName + " is required.");
        }

        long value;
        try {
            value = rawValue instanceof Number
                ? ((Number) rawValue).longValue()
                : Long.parseLong(rawValue.toString().trim());
        } catch (NumberFormatException ex) {
            throw new BadRequestException(fieldName + " must be a valid integer.");
        }

        if (value <= 0) {
            throw new BadRequestException(fieldName + " must be greater than 0.");
        }
        return value;
    }

    /**
     * Parses a required ISO local date.
     */
    private LocalDate parseRequiredDate(Object rawValue, String fieldName) {
        if (rawValue == null) {
            throw new BadRequestException(fieldName + " is required.");
        }
        if (rawValue instanceof LocalDate date) {
            return date;
        }
        try {
            return LocalDate.parse(rawValue.toString().trim());
        } catch (DateTimeParseException ex) {
            throw new BadRequestException(fieldName + " must be in YYYY-MM-DD format.");
        }
    }

    /**
     * Rejects future dates and dates that are too far in the past.
     */
    private void validateTransactionDate(LocalDate date) {
        if (date == null) {
            throw new BadRequestException("date is required.");
        }
        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) {
            throw new BadRequestException("Future transaction dates are not allowed.");
        }
        if (date.isBefore(today.minusYears(MAX_BACKDATED_YEARS))) {
            throw new BadRequestException("Transaction date is too old.");
        }
    }

    /**
     * Enforces maximum remark length.
     */
    private void validateRemarkLength(Object remarkValue) {
        if (remarkValue == null) {
            return;
        }
        String remark = remarkValue.toString();
        if (remark.length() > MAX_REMARK_LENGTH) {
            throw new BadRequestException("remark must be at most " + MAX_REMARK_LENGTH + " characters.");
        }
    }

    /**
     * Verifies category ownership and category type match transaction type.
     */
    private void validateCategoryForType(Long categoryId, int type, User user) {
        Category category = categoryRepository.findById(categoryId)
            .orElseThrow(() -> new BadRequestException("categoryId is invalid."));

        if (category.getFamilyId() != null && !category.getFamilyId().equals(user.getFamilyId())) {
            throw new BadRequestException("category does not belong to your family.");
        }
        if (category.getType() == null || !category.getType().equals(type)) {
            throw new BadRequestException("category type does not match transaction type.");
        }
    }

    /**
     * Creates a minimal transaction snapshot before mutation so budget refresh can compare old/new state.
     */
    private Transaction snapshot(Transaction source) {
        if (source == null) {
            return null;
        }
        return Transaction.builder()
            .id(source.getId())
            .userId(source.getUserId())
            .type(source.getType())
            .amount(source.getAmount())
            .categoryId(source.getCategoryId())
            .date(source.getDate())
            .budgetId(source.getBudgetId())
            .refundedAmount(source.getRefundedAmount())
            .build();
    }

    /**
     * Re-syncs all budgets that may have been affected by the write operation.
     */
    private void refreshAffectedBudgets(Long userId, Transaction before, Transaction after) {
        Set<Long> budgetIds = new HashSet<>();
        collectBudgetIdsForSync(userId, before, budgetIds);
        collectBudgetIdsForSync(userId, after, budgetIds);
        budgetIds.forEach(budgetId -> {
            if (budgetRepository.findByIdAndUserId(budgetId, userId).isPresent()) {
                budgetService.syncBudgetSnapshot(budgetId, userId);
            }
        });
    }

    /**
     * Collects direct and inferred budget ids that need snapshot recalculation.
     */
    private void collectBudgetIdsForSync(Long userId, Transaction transaction, Set<Long> budgetIds) {
        if (transaction == null || transaction.getType() == null || transaction.getType() != 2 || transaction.getDate() == null) {
            return;
        }
        if (transaction.getBudgetId() != null) {
            budgetIds.add(transaction.getBudgetId());
        }
        budgetService.matchActiveBudgetForExpense(userId, transaction.getCategoryId(), transaction.getDate())
            .map(Budget::getId)
            .ifPresent(budgetIds::add);
    }

    /**
     * Builds a structured risk portrait for expense transactions.
     *
     * <p>Covered dimensions include large single expense, income-expense imbalance,
     * high-frequency spending, suspected duplicates, category spikes and budget pressure.
     */
    private Map<String, Object> buildTransactionRisk(Long userId, Transaction transaction) {
        Map<String, Object> risk = new HashMap<>();
        List<String> flags = new ArrayList<>();
        String level = "low";

        if (transaction != null && transaction.getType() != null && transaction.getType() == 2) {
            BigDecimal amount = defaultAmount(transaction.getAmount());
            if (amount.compareTo(CRITICAL_EXPENSE_THRESHOLD) >= 0) {
                addRiskFlag(flags, "critical_expense");
                level = escalateRiskLevel(level, "critical");
            } else if (amount.compareTo(LARGE_EXPENSE_THRESHOLD) >= 0) {
                addRiskFlag(flags, "large_expense");
                level = escalateRiskLevel(level, "high");
            }

            if (transaction.getDate() != null) {
                YearMonth yearMonth = YearMonth.from(transaction.getDate());
                BigDecimal monthExpense = defaultAmount(transactionRepository.sumEffectiveExpenseByUserIdAndDateBetween(
                    userId,
                    yearMonth.atDay(1),
                    yearMonth.atEndOfMonth()
                ));
                BigDecimal monthIncome = defaultAmount(transactionRepository.sumByUserIdAndTypeAndDateBetween(
                    userId,
                    1,
                    yearMonth.atDay(1),
                    yearMonth.atEndOfMonth()
                ));
                risk.put("monthlyEffectiveExpense", monthExpense);
                risk.put("monthlyIncome", monthIncome);

                if (monthIncome.compareTo(BigDecimal.ZERO) <= 0 && monthExpense.compareTo(BigDecimal.ZERO) > 0) {
                    addRiskFlag(flags, "expense_without_income");
                    level = escalateRiskLevel(level, "critical");
                } else if (monthIncome.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal expenseIncomeRatio = monthExpense.divide(monthIncome, 2, RoundingMode.HALF_UP);
                    risk.put("expenseIncomeRatio", expenseIncomeRatio);
                    if (expenseIncomeRatio.compareTo(EXPENSE_INCOME_RATIO_WARNING) >= 0) {
                        addRiskFlag(flags, "expense_income_ratio_high");
                        level = escalateRiskLevel(level, "high");
                    }
                }

                long dayExpenseCount = transactionRepository.countByUserIdAndTypeAndDate(userId, 2, transaction.getDate());
                risk.put("dailyExpenseCount", dayExpenseCount);
                if (dayExpenseCount >= HIGH_FREQUENCY_EXPENSE_THRESHOLD) {
                    addRiskFlag(flags, "high_frequency_expense");
                    level = escalateRiskLevel(level, "medium");
                }

                if (transaction.getCategoryId() != null) {
                    long duplicateCount = transactionRepository.countDuplicateTransactions(
                        userId,
                        2,
                        transaction.getCategoryId(),
                        amount,
                        transaction.getDate(),
                        transaction.getId()
                    );
                    risk.put("sameDayDuplicateCount", duplicateCount);
                    if (duplicateCount >= DUPLICATE_EXPENSE_COUNT_THRESHOLD) {
                        addRiskFlag(flags, "possible_duplicate_expense");
                        level = escalateRiskLevel(level, "medium");
                    }

                    BigDecimal currentCategoryExpense = defaultAmount(transactionRepository
                        .sumByUserIdAndTypeAndCategoryIdAndDateBetween(
                            userId,
                            2,
                            transaction.getCategoryId(),
                            yearMonth.atDay(1),
                            yearMonth.atEndOfMonth()
                        ));
                    YearMonth previousMonth = yearMonth.minusMonths(1);
                    BigDecimal previousCategoryExpense = defaultAmount(transactionRepository
                        .sumByUserIdAndTypeAndCategoryIdAndDateBetween(
                            userId,
                            2,
                            transaction.getCategoryId(),
                            previousMonth.atDay(1),
                            previousMonth.atEndOfMonth()
                        ));
                    risk.put("currentCategoryExpense", currentCategoryExpense);
                    risk.put("previousCategoryExpense", previousCategoryExpense);
                    if (previousCategoryExpense.compareTo(BigDecimal.ZERO) > 0) {
                        risk.put(
                            "categoryExpenseRatio",
                            currentCategoryExpense.divide(previousCategoryExpense, 2, RoundingMode.HALF_UP)
                        );
                    }

                    if (isCategoryExpenseSpike(currentCategoryExpense, previousCategoryExpense)) {
                        addRiskFlag(flags, "category_expense_spike");
                        level = escalateRiskLevel(level, "high");
                    }
                }
            }

            if (transaction.getBudgetId() != null) {
                Optional<Budget> budgetOptional = budgetRepository.findByIdAndUserId(transaction.getBudgetId(), userId);
                if (budgetOptional.isPresent()) {
                    Budget budget = budgetOptional.get();
                    BigDecimal amountLimit = defaultAmount(budget.getAmount());
                    BigDecimal spent = defaultAmount(budget.getSpent());
                    BigDecimal usageRate = amountLimit.compareTo(BigDecimal.ZERO) > 0
                        ? spent.multiply(BigDecimal.valueOf(100)).divide(amountLimit, 1, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                    BigDecimal remaining = amountLimit.subtract(spent);
                    int warningThreshold = budget.getWarningThreshold() == null ? 85 : budget.getWarningThreshold();

                    String budgetRisk = "normal";
                    if ((budget.getStatus() != null && budget.getStatus() == BudgetStatus.OVERRUN)
                        || usageRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
                        budgetRisk = "overrun";
                        addRiskFlag(flags, "budget_overrun");
                        level = escalateRiskLevel(level, "critical");
                    } else if (usageRate.compareTo(BigDecimal.valueOf(warningThreshold)) >= 0) {
                        budgetRisk = "warning";
                        addRiskFlag(flags, "budget_warning");
                        level = escalateRiskLevel(level, "high");
                    } else if (usageRate.compareTo(BigDecimal.valueOf(Math.max(0, warningThreshold - 10))) >= 0) {
                        budgetRisk = "watch";
                        addRiskFlag(flags, "budget_watch");
                        level = escalateRiskLevel(level, "medium");
                    }

                    Map<String, Object> budgetRiskData = new HashMap<>();
                    budgetRiskData.put("budgetId", budget.getId());
                    budgetRiskData.put("budgetName", budget.getName());
                    budgetRiskData.put("amount", amountLimit);
                    budgetRiskData.put("spent", spent);
                    budgetRiskData.put("remaining", remaining);
                    budgetRiskData.put("usageRate", usageRate);
                    budgetRiskData.put("warningThreshold", warningThreshold);
                    budgetRiskData.put("status", budgetRisk);
                    risk.put("budget", budgetRiskData);
                }
            }
        }

        risk.put("level", level);
        risk.put("flags", flags);
        risk.put("message", resolveRiskMessage(level));
        return risk;
    }

    /**
     * Detects category-expense spikes.
     *
     * <p>With history, both ratio and delta must exceed threshold.
     * Without history, the new-category amount threshold is used instead.
     */
    private boolean isCategoryExpenseSpike(BigDecimal currentExpense, BigDecimal previousExpense) {
        if (currentExpense.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (previousExpense.compareTo(BigDecimal.ZERO) <= 0) {
            return currentExpense.compareTo(NEW_CATEGORY_LARGE_EXPENSE_THRESHOLD) >= 0;
        }
        BigDecimal expenseRatio = currentExpense.divide(previousExpense, 2, RoundingMode.HALF_UP);
        BigDecimal delta = currentExpense.subtract(previousExpense);
        return expenseRatio.compareTo(CATEGORY_SPIKE_RATIO) >= 0
            && delta.compareTo(CATEGORY_SPIKE_DELTA_THRESHOLD) >= 0;
    }

    /**
     * Adds one risk flag only once to avoid duplicate UI markers.
     */
    private void addRiskFlag(List<String> flags, String flag) {
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
    }

    /**
     * Validates pagination boundaries.
     */
    private void validatePaging(int page, int pageSize) {
        if (page <= 0) {
            throw new BadRequestException("page must be greater than 0.");
        }
        if (pageSize <= 0 || pageSize > MAX_PAGE_SIZE) {
            throw new BadRequestException("pageSize must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
    }

    /**
     * Escalates risk level only when the candidate is more severe than the current one.
     */
    private String escalateRiskLevel(String current, String candidate) {
        if (riskRank(candidate) > riskRank(current)) {
            return candidate;
        }
        return current;
    }

    /**
     * Maps risk levels to comparable ranks.
     */
    private int riskRank(String level) {
        return switch (level) {
            case "critical" -> 4;
            case "high" -> 3;
            case "medium" -> 2;
            default -> 1;
        };
    }

    /**
     * Human-readable summary paired with computed risk level.
     */
    private String resolveRiskMessage(String level) {
        return switch (level) {
            case "critical" -> "High risk detected: immediate review is recommended.";
            case "high" -> "Potential budget pressure detected: monitor this transaction closely.";
            case "medium" -> "Transaction should be monitored for budget trend changes.";
            default -> "Risk is currently under control.";
        };
    }
}
