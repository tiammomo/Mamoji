INSERT INTO company_memberships (
    company_id,
    user_id,
    department_id,
    role,
    scope,
    status,
    created_at,
    updated_at
)
SELECT company.id,
       company.owner_id,
       NULL,
       'founder',
       'company',
       'active',
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP
FROM companies company
ON CONFLICT (company_id, user_id) DO UPDATE SET
    role = 'founder',
    scope = 'company',
    status = 'active',
    updated_at = CURRENT_TIMESTAMP
WHERE company_memberships.role IS DISTINCT FROM 'founder'
   OR company_memberships.scope IS DISTINCT FROM 'company'
   OR company_memberships.status IS DISTINCT FROM 'active';

INSERT INTO ledgers (
    name,
    description,
    currency,
    owner_id,
    is_default,
    status,
    created_at,
    updated_at,
    company_id
)
SELECT LEFT(company.name || '账本', 120),
       '主体默认经营账本',
       company.currency,
       company.owner_id,
       TRUE,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       company.id
FROM companies company
WHERE NOT EXISTS (
    SELECT 1
    FROM ledgers ledger
    WHERE ledger.company_id = company.id
);

WITH ranked_ledgers AS (
    SELECT ledger.id,
           ledger.company_id,
           ledger.owner_id,
           ROW_NUMBER() OVER (
               PARTITION BY ledger.company_id
               ORDER BY ledger.is_default DESC, ledger.id
           ) AS position
    FROM ledgers ledger
), selected_ledgers AS (
    SELECT ranked.id,
           ranked.company_id,
           ranked.owner_id
    FROM ranked_ledgers ranked
    WHERE ranked.position = 1
)
INSERT INTO ledger_members (
    company_id,
    ledger_id,
    user_id,
    role,
    nickname,
    avatar,
    joined_at
)
SELECT company.id,
       ledger.id,
       company.owner_id,
       CASE WHEN ledger.owner_id = company.owner_id THEN 'owner' ELSE 'admin' END,
       local_user.nickname,
       local_user.avatar,
       CURRENT_TIMESTAMP
FROM companies company
JOIN selected_ledgers ledger ON ledger.company_id = company.id
JOIN users local_user ON local_user.id = company.owner_id
ON CONFLICT (ledger_id, user_id) DO NOTHING;

INSERT INTO categories (
    name,
    icon,
    color,
    type,
    user_id,
    status,
    created_at,
    updated_at,
    company_id
)
SELECT '经营收入',
       '💼',
       '#22c55e',
       'income',
       company.owner_id,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       company.id
FROM companies company
WHERE NOT EXISTS (
    SELECT 1
    FROM categories category
    WHERE category.company_id = company.id
      AND category.user_id = company.owner_id
      AND category.type = 'income'
);

INSERT INTO categories (
    name,
    icon,
    color,
    type,
    user_id,
    status,
    created_at,
    updated_at,
    company_id
)
SELECT '经营支出',
       '🧾',
       '#ef4444',
       'expense',
       company.owner_id,
       1,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       company.id
FROM companies company
WHERE NOT EXISTS (
    SELECT 1
    FROM categories category
    WHERE category.company_id = company.id
      AND category.user_id = company.owner_id
      AND category.type = 'expense'
);

COMMENT ON TABLE ledgers IS
    'Authoritative company-scoped finance ledgers; legacy missing workspaces were provisioned once by Flyway V28.';

COMMENT ON TABLE categories IS
    'Authoritative company-scoped personal transaction classifications; legacy missing defaults were provisioned once by Flyway V28.';
