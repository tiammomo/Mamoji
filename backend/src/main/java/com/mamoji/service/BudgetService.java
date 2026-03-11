package com.mamoji.service;

import com.mamoji.common.exception.BadRequestException;
import com.mamoji.common.exception.ResourceNotFoundException;
import com.mamoji.common.status.BudgetStatus;
import com.mamoji.dto.BudgetDTO;
import com.mamoji.entity.Budget;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
/**
 * 预算服务层。
 *
 * <p>核心职责：
 * 1) 提供预算的增删改查；
 * 2) 维护预算 spent 与状态（active/completed/overrun）一致性；
 * 3) 提供交易到预算的匹配能力；
 * 4) 输出预算风险信息（usageRate、riskLevel、riskMessage）。
 */
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    /**
     * 查询用户当前激活预算并转换为 DTO。
     */
    public List<BudgetDTO> getBudgets(Long userId) {
        return budgetRepository.findByUserIdAndStatus(userId, BudgetStatus.ACTIVE)
            .stream()
            .map(this::toDto)
            .toList();
    }

    /**
     * 按时间范围筛选预算，返回与区间有重叠的激活预算。
     */
    public List<BudgetDTO> getBudgetsByDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return budgetRepository.findByUserIdAndStatus(userId, BudgetStatus.ACTIVE)
            .stream()
            .filter(budget -> isOverlapping(budget, startDate, endDate))
            .map(this::toDto)
            .toList();
    }

    /**
     * 查询当前日期下生效的预算列表。
     */
    public List<BudgetDTO> getActiveBudgets(Long userId) {
        return budgetRepository.findActiveBudgets(userId, LocalDate.now())
            .stream()
            .map(this::toDto)
            .toList();
    }

    /**
     * 查询单个预算详情并校验归属权限。
     */
    public BudgetDTO getBudget(Long id, Long userId) {
        return toDto(findOwnedBudget(id, userId));
    }

    @Transactional
    /**
     * 创建预算。
     *
     * <p>关键校验：
     * 1) 名称、金额、预算周期、预警阈值合法；
     * 2) 同分类同时间段预算不可重叠。
     */
    public BudgetDTO createBudget(BudgetDTO dto, Long userId) {
        validateBudgetName(dto.getName());
        validateBudgetAmount(dto.getAmount());
        validateBudgetPeriod(dto.getStartDate(), dto.getEndDate());
        int warningThreshold = dto.getWarningThreshold() != null ? dto.getWarningThreshold() : 85;
        validateWarningThreshold(warningThreshold);
        validateNoOverlap(userId, dto.getCategoryId(), dto.getStartDate(), dto.getEndDate(), null);

        Budget budget = new Budget();
        budget.setName(dto.getName().trim());
        budget.setAmount(dto.getAmount());
        budget.setStartDate(dto.getStartDate());
        budget.setEndDate(dto.getEndDate());
        budget.setWarningThreshold(warningThreshold);
        budget.setUserId(userId);
        budget.setLedgerId(dto.getLedgerId());
        budget.setCategoryId(dto.getCategoryId());
        budget.setSpent(BigDecimal.ZERO);
        budget.setStatus(BudgetStatus.ACTIVE);

        return toDto(budgetRepository.save(budget));
    }

    @Transactional
    /**
     * 局部更新预算并重算 spent 与状态。
     */
    public BudgetDTO updateBudget(Long id, BudgetDTO dto, Long userId) {
        Budget budget = findOwnedBudget(id, userId);

        String nextName = budget.getName();
        BigDecimal nextAmount = budget.getAmount();
        LocalDate nextStartDate = budget.getStartDate();
        LocalDate nextEndDate = budget.getEndDate();
        Integer nextWarningThreshold = budget.getWarningThreshold();
        Long nextCategoryId = budget.getCategoryId();

        if (dto.getName() != null) {
            validateBudgetName(dto.getName());
            nextName = dto.getName().trim();
        }
        if (dto.getAmount() != null) {
            validateBudgetAmount(dto.getAmount());
            nextAmount = dto.getAmount();
        }
        if (dto.getStartDate() != null) {
            nextStartDate = dto.getStartDate();
        }
        if (dto.getEndDate() != null) {
            nextEndDate = dto.getEndDate();
        }
        if (dto.getWarningThreshold() != null) {
            validateWarningThreshold(dto.getWarningThreshold());
            nextWarningThreshold = dto.getWarningThreshold();
        }
        if (dto.getCategoryId() != null) {
            nextCategoryId = dto.getCategoryId();
        }

        validateBudgetPeriod(nextStartDate, nextEndDate);
        validateNoOverlap(userId, nextCategoryId, nextStartDate, nextEndDate, budget.getId());

        budget.setName(nextName);
        budget.setAmount(nextAmount);
        budget.setStartDate(nextStartDate);
        budget.setEndDate(nextEndDate);
        budget.setWarningThreshold(nextWarningThreshold);
        budget.setCategoryId(nextCategoryId);

        budget.setSpent(calculateBudgetSpent(budget));
        updateBudgetStatus(budget);
        return toDto(budgetRepository.save(budget));
    }

    @Transactional
    /**
     * 软删除预算（置为 INACTIVE）。
     */
    public void deleteBudget(Long id, Long userId) {
        Budget budget = findOwnedBudget(id, userId);
        budget.setStatus(BudgetStatus.INACTIVE);
        budgetRepository.save(budget);
    }

    @Transactional
    /**
     * 增量更新预算 spent，适用于已明确增量金额的场景。
     */
    public void updateBudgetSpent(Long budgetId, BigDecimal amount) {
        Budget budget = budgetRepository.findById(budgetId)
            .orElseThrow(() -> new ResourceNotFoundException("Budget not found."));
        budget.setSpent(budget.getSpent().add(amount));
        updateBudgetStatus(budget);
        budgetRepository.save(budget);
    }

    @Transactional
    /**
     * 基于交易快照全量重算预算 spent，修正可能的累积误差。
     */
    public void syncBudgetSnapshot(Long budgetId, Long userId) {
        Budget budget = findOwnedBudget(budgetId, userId);
        budget.setSpent(calculateBudgetSpent(budget));
        updateBudgetStatus(budget);
        budgetRepository.save(budget);
    }

    /**
     * 匹配支出交易可关联预算：
     * 1) 优先匹配分类预算；
     * 2) 再匹配不分分类的兜底预算。
     */
    public Optional<Budget> matchActiveBudgetForExpense(Long userId, Long categoryId, LocalDate date) {
        if (categoryId != null) {
            Optional<Budget> categoryBudget = budgetRepository.findActiveBudgetByCategory(userId, categoryId, date);
            if (categoryBudget.isPresent()) {
                return categoryBudget;
            }
        }
        return budgetRepository.findActiveBudgetWithoutCategory(userId, date);
    }

    private Budget findOwnedBudget(Long id, Long userId) {
        return budgetRepository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Budget not found."));
    }

    private boolean isOverlapping(Budget budget, LocalDate startDate, LocalDate endDate) {
        return !(budget.getEndDate().isBefore(startDate) || budget.getStartDate().isAfter(endDate));
    }

    private void validateBudgetAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Budget amount cannot be negative.");
        }
    }

    private void validateBudgetName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new BadRequestException("Budget name cannot be empty.");
        }
        if (name.trim().length() > 64) {
            throw new BadRequestException("Budget name must be at most 64 characters.");
        }
    }

    private void validateBudgetPeriod(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BadRequestException("Budget period is required.");
        }
        if (startDate.isAfter(endDate)) {
            throw new BadRequestException("Budget start date cannot be after end date.");
        }
    }

    private void validateWarningThreshold(Integer warningThreshold) {
        if (warningThreshold < 0 || warningThreshold > 100) {
            throw new BadRequestException("Warning threshold must be between 0 and 100.");
        }
    }

    /**
     * 校验预算时间段是否与现有激活预算冲突。
     */
    private void validateNoOverlap(Long userId, Long categoryId, LocalDate startDate, LocalDate endDate, Long excludeBudgetId) {
        long overlapCount = budgetRepository.countOverlappingActiveBudgets(
            userId,
            categoryId,
            startDate,
            endDate,
            excludeBudgetId
        );
        if (overlapCount > 0) {
            throw new BadRequestException("An overlapping active budget already exists for this category and period.");
        }
    }

    /**
     * 以“有效支出”（支出-已退款）计算预算 spent。
     */
    private BigDecimal calculateBudgetSpent(Budget budget) {
        if (budget.getCategoryId() == null) {
            return defaultAmount(transactionRepository.sumEffectiveExpenseByUserIdAndDateBetween(
                budget.getUserId(),
                budget.getStartDate(),
                budget.getEndDate()
            ));
        }
        return defaultAmount(transactionRepository.sumEffectiveExpenseByUserIdAndCategoryIdAndDateBetween(
            budget.getUserId(),
            budget.getCategoryId(),
            budget.getStartDate(),
            budget.getEndDate()
        ));
    }

    private BigDecimal defaultAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * 按当前日期与执行进度更新预算状态。
     */
    private void updateBudgetStatus(Budget budget) {
        LocalDate today = LocalDate.now();
        if (today.isAfter(budget.getEndDate())) {
            budget.setStatus(
                budget.getSpent().compareTo(budget.getAmount()) > 0
                    ? BudgetStatus.OVERRUN
                    : BudgetStatus.COMPLETED
            );
            return;
        }

        budget.setStatus(
            budget.getSpent().compareTo(budget.getAmount()) > 0
                ? BudgetStatus.OVERRUN
                : BudgetStatus.ACTIVE
        );
    }

    /**
     * 转换为 BudgetDTO，并补充风险衍生字段。
     */
    private BudgetDTO toDto(Budget budget) {
        BudgetDTO dto = new BudgetDTO();
        dto.setId(budget.getId());
        dto.setName(budget.getName());
        dto.setAmount(budget.getAmount());
        dto.setStartDate(budget.getStartDate());
        dto.setEndDate(budget.getEndDate());
        dto.setWarningThreshold(budget.getWarningThreshold());
        dto.setStatus(budget.getStatus());
        dto.setSpent(budget.getSpent());
        dto.setUserId(budget.getUserId());
        dto.setLedgerId(budget.getLedgerId());
        dto.setCategoryId(budget.getCategoryId());

        if (budget.getAmount() != null && budget.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal usageRate = budget.getSpent()
                .divide(budget.getAmount(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
            dto.setUsageRate(usageRate);
        }
        BigDecimal amount = budget.getAmount() != null ? budget.getAmount() : BigDecimal.ZERO;
        BigDecimal spent = budget.getSpent() != null ? budget.getSpent() : BigDecimal.ZERO;
        BigDecimal remainingAmount = amount.subtract(spent);
        dto.setRemainingAmount(remainingAmount);

        BigDecimal usageRate = dto.getUsageRate() != null ? dto.getUsageRate() : BigDecimal.ZERO;
        int warningThreshold = budget.getWarningThreshold() != null ? budget.getWarningThreshold() : 85;
        boolean warningReached = usageRate.compareTo(BigDecimal.valueOf(warningThreshold)) >= 0;
        dto.setWarningReached(warningReached);
        dto.setRiskLevel(resolveRiskLevel(usageRate, warningThreshold));
        dto.setRiskMessage(resolveRiskMessage(dto.getRiskLevel()));

        return dto;
    }

    private String resolveRiskLevel(BigDecimal usageRate, int warningThreshold) {
        if (usageRate.compareTo(BigDecimal.valueOf(100)) >= 0) {
            return "critical";
        }
        if (usageRate.compareTo(BigDecimal.valueOf(warningThreshold)) >= 0) {
            return "high";
        }
        if (usageRate.compareTo(BigDecimal.valueOf(Math.max(0, warningThreshold - 10))) >= 0) {
            return "medium";
        }
        return "low";
    }

    private String resolveRiskMessage(String riskLevel) {
        return switch (riskLevel) {
            case "critical" -> "Budget exceeded. Immediate control is recommended.";
            case "high" -> "Budget is close to the warning threshold.";
            case "medium" -> "Budget usage is rising quickly. Keep monitoring.";
            default -> "Budget risk is currently under control.";
        };
    }
}
