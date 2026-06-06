package com.mamoji.service.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class EnterprisePermissionCatalog {
    public Map<String, Object> matrix() {
        List<Map<String, Object>> roles = List.of(
            row("key", "founder", "name", "创始人/CEO", "description", "公司所有者，负责最终经营与权限控制"),
            row("key", "finance_admin", "name", "财务管理员", "description", "管理经营流水、资金账户、税费、预算和经营报表"),
            row("key", "hr_admin", "name", "人事管理员", "description", "管理员工信息、入职、离职、人力成本和人员权限"),
            row("key", "department_manager", "name", "部门负责人", "description", "管理本部门人员、预算、成本和审批"),
            row("key", "employee", "name", "普通员工", "description", "维护本人信息，提交报销和查看个人审批"),
            row("key", "viewer", "name", "只读观察者", "description", "用于投资人、顾问、审计或离职留档的只读访问")
        );
        List<Map<String, Object>> scopes = List.of(
            row("key", "group", "name", "多公司集团", "description", "可访问同一经营主体下的多家公司汇总数据"),
            row("key", "company", "name", "全公司", "description", "可访问公司级数据"),
            row("key", "company_set", "name", "指定公司集", "description", "仅访问被授权的多个公司主体"),
            row("key", "department", "name", "本部门", "description", "仅访问员工所属部门或负责部门数据"),
            row("key", "self", "name", "本人", "description", "仅访问本人信息、单据和审批"),
            row("key", "readonly", "name", "只读", "description", "可查看但不可编辑")
        );
        List<Map<String, Object>> permissions = List.of(
            row("key", "company.switch", "name", "切换公司主体"),
            row("key", "company.create", "name", "新增公司主体"),
            row("key", "company.manage", "name", "公司资料管理"),
            row("key", "policy.read", "name", "查看地区政策画像"),
            row("key", "policy.manage", "name", "维护政策配置"),
            row("key", "people.read", "name", "查看人员信息"),
            row("key", "people.write", "name", "维护人员信息"),
            row("key", "people.offboard", "name", "办理离职"),
            row("key", "operations.read", "name", "查看经营数据"),
            row("key", "operations.write", "name", "维护经营流水"),
            row("key", "finance.read", "name", "查看资金与凭证"),
            row("key", "finance.write", "name", "维护财务单据"),
            row("key", "budget.manage", "name", "预算管理"),
            row("key", "tax.manage", "name", "税费管理"),
            row("key", "approval.manage", "name", "审批处理"),
            row("key", "reports.read", "name", "查看经营报表"),
            row("key", "admin.permissions", "name", "权限分配")
        );
        List<Map<String, Object>> matrix = List.of(
            row("role", "founder", "defaultScope", "company", "permissions", List.of(
                "company.switch", "company.create", "company.manage", "policy.read", "policy.manage",
                "people.read", "people.write", "people.offboard", "operations.read", "operations.write", "finance.read", "finance.write",
                "budget.manage", "tax.manage", "approval.manage", "reports.read", "admin.permissions"
            )),
            row("role", "finance_admin", "defaultScope", "company", "permissions", List.of(
                "company.switch", "policy.read", "operations.read", "operations.write", "finance.read", "finance.write",
                "budget.manage", "tax.manage", "approval.manage", "reports.read"
            )),
            row("role", "hr_admin", "defaultScope", "company", "permissions", List.of(
                "company.switch", "policy.read", "people.read", "people.write", "people.offboard", "approval.manage", "reports.read"
            )),
            row("role", "department_manager", "defaultScope", "department", "permissions", List.of(
                "people.read", "operations.read", "budget.manage", "approval.manage", "reports.read"
            )),
            row("role", "employee", "defaultScope", "self", "permissions", List.of(
                "people.read", "approval.manage"
            )),
            row("role", "viewer", "defaultScope", "readonly", "permissions", List.of(
                "people.read", "operations.read", "finance.read", "reports.read"
            ))
        );
        return Map.of("roles", roles, "scopes", scopes, "permissions", permissions, "matrix", matrix);
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }
}
