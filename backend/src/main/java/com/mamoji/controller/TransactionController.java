package com.mamoji.controller;

import com.mamoji.entity.Budget;
import com.mamoji.entity.Category;
import com.mamoji.entity.Transaction;
import com.mamoji.entity.User;
import com.mamoji.repository.BudgetRepository;
import com.mamoji.repository.CategoryRepository;
import com.mamoji.repository.TransactionRepository;
import com.mamoji.security.AuthenticationUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final BudgetRepository budgetRepository;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getTransactions(
            @AuthenticationUser User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Integer type,
            @RequestParam(required = false) String types,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {

        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);
        Page<Transaction> transactionPage;
        List<Integer> typeList = parseTypes(types);

        // 根据条件查询
        if (startDate != null && !startDate.isEmpty() && endDate != null && !endDate.isEmpty()) {
            LocalDate start = LocalDate.parse(startDate);
            LocalDate end = LocalDate.parse(endDate);
            if (typeList != null && !typeList.isEmpty()) {
                transactionPage = transactionRepository.findByUserIdAndTypeInAndDateBetweenOrderByDateDesc(
                    user.getId(),
                    typeList,
                    start,
                    end,
                    pageRequest
                );
            } else if (type != null) {
                transactionPage = transactionRepository.findByUserIdAndTypeAndDateBetweenOrderByDateDesc(user.getId(), type, start, end, pageRequest);
            } else {
                transactionPage = transactionRepository.findByUserIdAndDateBetweenOrderByDateDesc(user.getId(), start, end, pageRequest);
            }
        } else if (typeList != null && !typeList.isEmpty()) {
            transactionPage = transactionRepository.findByUserIdAndTypeInOrderByDateDesc(user.getId(), typeList, pageRequest);
        } else if (type != null) {
            transactionPage = transactionRepository.findByUserIdAndTypeOrderByDateDesc(user.getId(), type, pageRequest);
        } else {
            transactionPage = transactionRepository.findByUserIdOrderByDateDesc(user.getId(), pageRequest);
        }

        List<Map<String, Object>> list = transactionPage.getContent().stream()
            .map(this::toMap)
            .toList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", transactionPage.getTotalElements());
        data.put("page", page);
        data.put("pageSize", pageSize);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);

        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTransaction(
            @AuthenticationUser User user,
            @RequestBody Map<String, Object> request) {

        int type = Integer.parseInt(request.get("type").toString());
        LocalDate transactionDate = LocalDate.parse(request.get("date").toString());

        Transaction transaction = Transaction.builder()
            .userId(user.getId())
            .familyId(user.getFamilyId())
            .type(type)
            .amount(new BigDecimal(request.get("amount").toString()))
            .categoryId(Long.parseLong(request.get("categoryId").toString()))
            .date(transactionDate)
            .remark(request.get("remark") != null ? request.get("remark").toString() : null)
            .build();

        // 如果是支出，自动关联当月预算
        if (type == 2) {
            Optional<Budget> activeBudget = budgetRepository.findActiveBudgetWithoutCategory(user.getId(), transactionDate);
            if (activeBudget.isPresent()) {
                transaction.setBudgetId(activeBudget.get().getId());
            }
        }

        transaction = transactionRepository.save(transaction);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", toMap(transaction));

        return ResponseEntity.ok(result);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateTransaction(
            @AuthenticationUser User user,
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        Transaction transaction = transactionRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("交易不存在"));

        if (!transaction.getUserId().equals(user.getId())) {
            throw new RuntimeException("无权限");
        }

        if (request.get("type") != null) {
            transaction.setType(Integer.parseInt(request.get("type").toString()));
        }
        if (request.get("amount") != null) {
            transaction.setAmount(new BigDecimal(request.get("amount").toString()));
        }
        if (request.get("categoryId") != null) {
            transaction.setCategoryId(Long.parseLong(request.get("categoryId").toString()));
        }
        if (request.get("date") != null) {
            transaction.setDate(LocalDate.parse(request.get("date").toString()));
        }
        if (request.get("remark") != null) {
            transaction.setRemark(request.get("remark").toString());
        }

        transaction = transactionRepository.save(transaction);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", toMap(transaction));

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteTransaction(
            @AuthenticationUser User user,
            @PathVariable Long id) {

        Transaction transaction = transactionRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("交易不存在"));

        if (!transaction.getUserId().equals(user.getId())) {
            throw new RuntimeException("无权限");
        }

        transactionRepository.delete(transaction);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", null);

        return ResponseEntity.ok(result);
    }

    // 获取可退款的支出列表
    @GetMapping("/refundable")
    public ResponseEntity<Map<String, Object>> getRefundableTransactions(
            @AuthenticationUser User user,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        PageRequest pageRequest = PageRequest.of(page - 1, pageSize);
        Page<Transaction> transactionPage = transactionRepository
            .findByUserIdAndTypeOrderByDateDesc(user.getId(), 2, pageRequest);

        List<Map<String, Object>> list = transactionPage.getContent().stream()
            .map(this::toRefundableMap)
            .toList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", list);
        data.put("total", transactionPage.getTotalElements());
        data.put("page", page);
        data.put("pageSize", pageSize);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", data);

        return ResponseEntity.ok(result);
    }

    // 发起退款
    @PostMapping("/{id}/refund")
    public ResponseEntity<Map<String, Object>> refundTransaction(
            @AuthenticationUser User user,
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {

        BigDecimal refundAmount = new BigDecimal(request.get("amount").toString());

        Transaction originalTx = transactionRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("交易不存在"));

        if (!originalTx.getUserId().equals(user.getId())) {
            throw new RuntimeException("无权限");
        }

        if (originalTx.getType() != 2) {
            throw new RuntimeException("只有支出可以退款");
        }

        // 计算已退款金额
        BigDecimal alreadyRefunded = originalTx.getRefundedAmount() != null
            ? originalTx.getRefundedAmount()
            : BigDecimal.ZERO;

        // 检查退款金额是否超过可退款金额
        BigDecimal refundable = originalTx.getAmount().subtract(alreadyRefunded);
        if (refundAmount.compareTo(refundable) > 0) {
            throw new RuntimeException("退款金额超过可退款金额");
        }

        // 更新原交易的已退款金额
        originalTx.setRefundedAmount(alreadyRefunded.add(refundAmount));
        transactionRepository.save(originalTx);

        // 创建退款记录（作为收入类型）
        Transaction refundTx = Transaction.builder()
            .userId(user.getId())
            .familyId(user.getFamilyId())
            .type(3) // 3 = 退款类型
            .amount(refundAmount)
            .categoryId(originalTx.getCategoryId())
            .accountId(originalTx.getAccountId())
            .date(LocalDate.parse(request.get("date").toString()))
            .remark("退款: " + originalTx.getRemark())
            .originalTransactionId(originalTx.getId()) // 关联原交易
            .build();

        refundTx = transactionRepository.save(refundTx);

        Map<String, Object> result = new HashMap<>();
        result.put("code", 0);
        result.put("message", "success");
        result.put("data", toMap(refundTx));

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> toRefundableMap(Transaction transaction) {
        Map<String, Object> map = toMap(transaction);
        BigDecimal refunded = transaction.getRefundedAmount() != null
            ? transaction.getRefundedAmount()
            : BigDecimal.ZERO;
        BigDecimal refundable = transaction.getAmount().subtract(refunded);
        map.put("refundableAmount", refundable);
        map.put("refundedAmount", refunded);
        map.put("canRefund", refundable.compareTo(BigDecimal.ZERO) > 0);
        return map;
    }

    private List<Integer> parseTypes(String types) {
        if (types == null || types.isBlank()) {
            return null;
        }
        return Arrays.stream(types.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(Integer::parseInt)
            .toList();
    }

    private Map<String, Object> toMap(Transaction transaction) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", transaction.getId());
        map.put("type", transaction.getType());
        map.put("amount", transaction.getAmount());
        map.put("date", transaction.getDate());
        map.put("remark", transaction.getRemark());

        // 获取分类信息
        String categoryName = "分类";
        String categoryIcon = "category";
        if (transaction.getCategoryId() != null) {
            Optional<Category> categoryOpt = categoryRepository.findById(transaction.getCategoryId());
            if (categoryOpt.isPresent()) {
                Category category = categoryOpt.get();
                categoryName = category.getName();
                categoryIcon = category.getIcon() != null ? category.getIcon() : "category";
            }
        }

        map.put("category", Map.of(
            "id", transaction.getCategoryId(),
            "name", categoryName,
            "icon", categoryIcon
        ));
        map.put("account", Map.of(
            "id", 1,
            "name", "现金"
        ));
        map.put("user", Map.of(
            "id", transaction.getUserId(),
            "nickname", "用户"
        ));
        // 退款相关信息
        if (transaction.getType() == 2) {
            BigDecimal refunded = transaction.getRefundedAmount() != null
                ? transaction.getRefundedAmount()
                : BigDecimal.ZERO;
            map.put("refundedAmount", refunded);
            map.put("refundableAmount", transaction.getAmount().subtract(refunded));
            map.put("canRefund", transaction.getAmount().subtract(refunded).compareTo(BigDecimal.ZERO) > 0);
        }
        // 如果是退款类型，显示关联的原交易
        if (transaction.getType() == 3 && transaction.getOriginalTransactionId() != null) {
            map.put("originalTransactionId", transaction.getOriginalTransactionId());
        }
        return map;
    }
}
