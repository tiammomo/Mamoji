DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM receipt_vouchers
        WHERE NOT pg_input_is_valid(COALESCE(NULLIF(BTRIM(amount), ''), '0'), 'numeric')
           OR NOT pg_input_is_valid(COALESCE(NULLIF(BTRIM(tax_amount), ''), '0'), 'numeric')
           OR NOT pg_input_is_valid(COALESCE(NULLIF(BTRIM(tax_rate), ''), '0'), 'numeric')
    ) THEN
        RAISE EXCEPTION 'receipt_vouchers contains an invalid amount, tax amount, or tax rate';
    END IF;
END $$;

WITH stored AS (
    SELECT voucher.*,
           COALESCE(NULLIF(BTRIM(voucher.amount), ''), '0')::NUMERIC AS amount_value,
           COALESCE(NULLIF(BTRIM(voucher.tax_amount), ''), '0')::NUMERIC AS tax_amount_value,
           CASE
               WHEN NULLIF(BTRIM(voucher.tax_period), '') IS NULL THEN
                   CASE
                       WHEN voucher.issue_date IS NULL OR LENGTH(voucher.issue_date) < 7
                           THEN TO_CHAR(CURRENT_DATE, 'YYYY-MM')
                       ELSE SUBSTRING(voucher.issue_date FROM 1 FOR 7)
                   END
               ELSE voucher.tax_period
           END AS hydrated_tax_period,
           CASE
               WHEN NULLIF(BTRIM(voucher.invoice_check_status), '') IS NULL
                    OR (voucher.invoice_check_status = 'not_required'
                        AND voucher.voucher_type IN ('sales_invoice', 'purchase_invoice')) THEN
                   CASE
                       WHEN voucher.status IN ('verified', 'linked', 'archived') THEN 'verified'
                       WHEN voucher.voucher_type IN ('sales_invoice', 'purchase_invoice') THEN 'pending'
                       ELSE 'not_required'
                   END
               ELSE voucher.invoice_check_status
           END AS hydrated_invoice_check_status,
           CASE
               WHEN NULLIF(BTRIM(voucher.deduction_status), '') IS NULL
                    OR (voucher.deduction_status = 'not_applicable'
                        AND voucher.voucher_type = 'purchase_invoice') THEN
                   CASE
                       WHEN voucher.status IN ('verified', 'linked', 'archived') THEN 'deductible'
                       WHEN voucher.voucher_type = 'purchase_invoice' THEN 'pending'
                       ELSE 'not_applicable'
                   END
               ELSE voucher.deduction_status
           END AS hydrated_deduction_status,
           CASE
               WHEN NULLIF(BTRIM(voucher.reimbursement_status), '') IS NULL
                    OR (voucher.reimbursement_status = 'not_applicable'
                        AND voucher.voucher_type = 'reimbursement') THEN
                   CASE
                       WHEN voucher.status = 'archived' THEN 'archived'
                       WHEN voucher.voucher_type = 'reimbursement' THEN 'submitted'
                       ELSE 'not_applicable'
                   END
               ELSE voucher.reimbursement_status
           END AS hydrated_reimbursement_status
    FROM receipt_vouchers voucher
), approval_defaults AS (
    SELECT stored.*,
           CASE
               WHEN NULLIF(BTRIM(stored.approval_status), '') IS NULL
                    OR (stored.approval_status = 'not_required'
                        AND (stored.voucher_type IN ('reimbursement', 'contract')
                             OR stored.amount_value >= 5000)) THEN
                   CASE
                       WHEN stored.voucher_type IN ('reimbursement', 'contract')
                            OR stored.amount_value >= 5000 THEN 'not_submitted'
                       ELSE 'not_required'
                   END
               ELSE stored.approval_status
           END AS hydrated_approval_status
    FROM stored
), accounting_defaults AS (
    SELECT approval_defaults.*,
           CASE
               WHEN NULLIF(BTRIM(approval_defaults.accounting_status), '') IS NULL THEN
                   CASE
                       WHEN approval_defaults.status = 'rejected' THEN 'not_started'
                       WHEN approval_defaults.status IN ('archived', 'linked') THEN 'posted'
                       WHEN approval_defaults.status = 'verified'
                            OR approval_defaults.hydrated_approval_status = 'approved' THEN 'draft'
                       ELSE 'not_started'
                   END
               ELSE approval_defaults.accounting_status
           END AS hydrated_accounting_status,
           CASE
               WHEN NULLIF(BTRIM(approval_defaults.accounting_entry), '') IS NULL THEN
                   CASE
                       WHEN approval_defaults.direction = 'income' THEN
                           '借：' || CASE WHEN approval_defaults.transaction_id IS NULL THEN '应收账款' ELSE '银行存款' END
                           || ' ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                           || '；贷：主营业务收入 ' || CASE
                               WHEN GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0) = 0 THEN '0'
                               WHEN STRPOS(GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0)::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0)::TEXT, '0'), '.')
                               ELSE GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0)::TEXT
                           END
                           || CASE
                               WHEN approval_defaults.tax_amount_value > 0 THEN
                                   '，应交税费-销项税额 ' || CASE
                                       WHEN STRPOS(approval_defaults.tax_amount_value::TEXT, '.') > 0
                                           THEN RTRIM(RTRIM(approval_defaults.tax_amount_value::TEXT, '0'), '.')
                                       ELSE approval_defaults.tax_amount_value::TEXT
                                   END
                               ELSE ''
                           END
                       WHEN approval_defaults.voucher_type = 'purchase_invoice' THEN
                           '借：管理费用 ' || CASE
                               WHEN GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0) = 0 THEN '0'
                               WHEN STRPOS(GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0)::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0)::TEXT, '0'), '.')
                               ELSE GREATEST(approval_defaults.amount_value - approval_defaults.tax_amount_value, 0)::TEXT
                           END
                           || CASE
                               WHEN approval_defaults.tax_amount_value > 0 THEN
                                   '，应交税费-进项税额 ' || CASE
                                       WHEN STRPOS(approval_defaults.tax_amount_value::TEXT, '.') > 0
                                           THEN RTRIM(RTRIM(approval_defaults.tax_amount_value::TEXT, '0'), '.')
                                       ELSE approval_defaults.tax_amount_value::TEXT
                                   END
                               ELSE ''
                           END
                           || '；贷：' || CASE WHEN approval_defaults.transaction_id IS NULL THEN '应付账款' ELSE '银行存款' END
                           || ' ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                       WHEN approval_defaults.voucher_type = 'reimbursement' THEN
                           '借：管理费用 ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                           || '；贷：其他应付款-' || COALESCE(NULLIF(BTRIM(approval_defaults.expense_owner), ''), '员工')
                           || ' ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                       WHEN approval_defaults.voucher_type = 'tax_receipt' THEN
                           '借：应交税费 ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                           || '；贷：银行存款 ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                       ELSE
                           '借：管理费用 ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                           || '；贷：' || CASE WHEN approval_defaults.transaction_id IS NULL THEN '应付账款' ELSE '银行存款' END
                           || ' ' || CASE
                               WHEN approval_defaults.amount_value = 0 THEN '0'
                               WHEN STRPOS(approval_defaults.amount_value::TEXT, '.') > 0
                                   THEN RTRIM(RTRIM(approval_defaults.amount_value::TEXT, '0'), '.')
                               ELSE approval_defaults.amount_value::TEXT
                           END
                   END
               ELSE approval_defaults.accounting_entry
           END AS hydrated_accounting_entry
    FROM approval_defaults
), hydrated AS (
    SELECT accounting_defaults.*,
           CASE
               WHEN NULLIF(BTRIM(accounting_defaults.accounting_voucher_no), '') IS NULL
                    AND accounting_defaults.hydrated_accounting_status IN ('draft', 'posted')
                    AND accounting_defaults.id > 0 THEN
                   'JV-' || REPLACE(
                       CASE
                           WHEN accounting_defaults.issue_date IS NULL
                                OR LENGTH(accounting_defaults.issue_date) < 7
                               THEN TO_CHAR(CURRENT_DATE, 'YYYY-MM')
                           ELSE SUBSTRING(accounting_defaults.issue_date FROM 1 FOR 7)
                       END,
                       '-',
                       ''
                   ) || '-'
                   || LPAD(
                       GREATEST(accounting_defaults.id, 1)::TEXT,
                       GREATEST(4, LENGTH(GREATEST(accounting_defaults.id, 1)::TEXT)),
                       '0'
                   )
               ELSE accounting_defaults.accounting_voucher_no
           END AS hydrated_accounting_voucher_no,
           CASE
               WHEN accounting_defaults.hydrated_accounting_status = 'posted'
                    AND NULLIF(BTRIM(accounting_defaults.accounted_at), '') IS NULL
                   THEN COALESCE(accounting_defaults.updated_at, accounting_defaults.created_at)
               ELSE accounting_defaults.accounted_at
           END AS hydrated_accounted_at,
           CASE
               WHEN NULLIF(BTRIM(accounting_defaults.file_storage_provider), '') IS NULL THEN
                   CASE
                       WHEN NULLIF(BTRIM(accounting_defaults.file_name), '') IS NULL THEN 'none'
                       ELSE 'metadata_only'
                   END
               ELSE accounting_defaults.file_storage_provider
           END AS hydrated_file_storage_provider
    FROM accounting_defaults
)
UPDATE receipt_vouchers voucher
SET tax_period = hydrated.hydrated_tax_period,
    invoice_check_status = hydrated.hydrated_invoice_check_status,
    deduction_status = hydrated.hydrated_deduction_status,
    reimbursement_status = hydrated.hydrated_reimbursement_status,
    approval_status = hydrated.hydrated_approval_status,
    accounting_status = hydrated.hydrated_accounting_status,
    accounting_voucher_no = hydrated.hydrated_accounting_voucher_no,
    accounting_entry = hydrated.hydrated_accounting_entry,
    accounted_at = hydrated.hydrated_accounted_at,
    file_storage_provider = hydrated.hydrated_file_storage_provider,
    updated_at = CURRENT_TIMESTAMP::TEXT,
    version = voucher.version + 1
FROM hydrated
WHERE voucher.id = hydrated.id
  AND ROW(
      voucher.tax_period,
      voucher.invoice_check_status,
      voucher.deduction_status,
      voucher.reimbursement_status,
      voucher.approval_status,
      voucher.accounting_status,
      voucher.accounting_voucher_no,
      voucher.accounting_entry,
      voucher.accounted_at,
      voucher.file_storage_provider
  ) IS DISTINCT FROM ROW(
      hydrated.hydrated_tax_period,
      hydrated.hydrated_invoice_check_status,
      hydrated.hydrated_deduction_status,
      hydrated.hydrated_reimbursement_status,
      hydrated.hydrated_approval_status,
      hydrated.hydrated_accounting_status,
      hydrated.hydrated_accounting_voucher_no,
      hydrated.hydrated_accounting_entry,
      hydrated.hydrated_accounted_at,
      hydrated.hydrated_file_storage_provider
  );

COMMENT ON TABLE receipt_vouchers IS
    'Evidence records whose legacy derived defaults were hydrated once by Flyway V27.';
