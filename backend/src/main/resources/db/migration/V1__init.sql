-- YNAY core schema. Amounts are stored as BIGINT milliunits (1.00 = 1000), like the YNAB API.
-- Months are stored as the DATE of the first day of the month.

create table app_user (
    id            uuid primary key,
    oidc_issuer   text        not null,
    oidc_subject  text        not null,
    username      text        not null,
    display_name  text,
    email         text,
    created_at    timestamptz not null default now(),
    last_login_at timestamptz,
    unique (oidc_issuer, oidc_subject)
);
create index app_user_username_idx on app_user (lower(username));

-- Personal access tokens for MCP clients. Only a SHA-256 hash of the secret is stored.
create table api_token (
    id           uuid primary key,
    user_id      uuid        not null references app_user (id) on delete cascade,
    name         text        not null,
    token_hash   text        not null unique,
    token_prefix text        not null,
    created_at   timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at   timestamptz,
    revoked_at   timestamptz
);
create index api_token_user_idx on api_token (user_id);

create table budget (
    id            uuid primary key,
    name          text        not null,
    currency_code char(3)     not null default 'USD',
    created_at    timestamptz not null default now()
);

create table budget_member (
    budget_id  uuid        not null references budget (id) on delete cascade,
    user_id    uuid        not null references app_user (id) on delete cascade,
    role       text        not null check (role in ('OWNER', 'EDITOR')),
    created_at timestamptz not null default now(),
    primary key (budget_id, user_id)
);
create index budget_member_user_idx on budget_member (user_id);

create table account (
    id         uuid primary key,
    budget_id  uuid        not null references budget (id) on delete cascade,
    name       text        not null,
    type       text        not null check (type in ('CHECKING', 'SAVINGS', 'CASH', 'CREDIT_CARD', 'LINE_OF_CREDIT',
                                                    'ASSET', 'LIABILITY', 'MORTGAGE', 'LOAN', 'OTHER')),
    on_budget  boolean     not null,
    closed     boolean     not null default false,
    note       text,
    sort_order int         not null default 0,
    created_at timestamptz not null default now()
);
create index account_budget_idx on account (budget_id);

-- system_kind: INTERNAL = hidden group holding "Inflow: Ready to Assign";
--              CREDIT_CARD_PAYMENTS = group holding one payment category per on-budget credit account.
create table category_group (
    id          uuid primary key,
    budget_id   uuid    not null references budget (id) on delete cascade,
    name        text    not null,
    hidden      boolean not null default false,
    sort_order  int     not null default 0,
    system_kind text check (system_kind in ('INTERNAL', 'CREDIT_CARD_PAYMENTS'))
);
create index category_group_budget_idx on category_group (budget_id);

-- system_kind: READY_TO_ASSIGN = income category; CREDIT_CARD_PAYMENT = linked to linked_account_id.
create table category (
    id                uuid primary key,
    budget_id         uuid    not null references budget (id) on delete cascade,
    group_id          uuid    not null references category_group (id) on delete cascade,
    name              text    not null,
    hidden            boolean not null default false,
    note              text,
    sort_order        int     not null default 0,
    system_kind       text check (system_kind in ('READY_TO_ASSIGN', 'CREDIT_CARD_PAYMENT')),
    linked_account_id uuid references account (id) on delete cascade,
    target_type       text check (target_type in ('MONTHLY_SAVINGS', 'MONTHLY_SPENDING', 'TARGET_BALANCE',
                                                  'TARGET_BALANCE_BY_DATE')),
    target_amount     bigint,
    target_date       date
);
create index category_budget_idx on category (budget_id);
create index category_group_idx on category (group_id);

create table payee (
    id                  uuid primary key,
    budget_id           uuid not null references budget (id) on delete cascade,
    name                text not null,
    transfer_account_id uuid references account (id) on delete cascade,
    unique (budget_id, name)
);

create table txn (
    id                      uuid primary key,
    budget_id               uuid        not null references budget (id) on delete cascade,
    account_id              uuid        not null references account (id) on delete cascade,
    date                    date        not null,
    amount                  bigint      not null,
    payee_id                uuid references payee (id) on delete set null,
    -- null for split parents (see txn_split), transfers between on-budget accounts and uncategorized txns
    category_id             uuid references category (id) on delete set null,
    memo                    text,
    cleared                 text        not null default 'UNCLEARED'
        check (cleared in ('UNCLEARED', 'CLEARED', 'RECONCILED')),
    approved                boolean     not null default true,
    flag_color              text check (flag_color in ('RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE', 'PURPLE')),
    transfer_account_id     uuid references account (id) on delete cascade,
    transfer_transaction_id uuid,
    import_id               text,
    is_split                boolean     not null default false,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    unique (account_id, import_id)
);
alter table txn
    add constraint txn_transfer_fk foreign key (transfer_transaction_id) references txn (id)
        on delete set null deferrable initially deferred;
create index txn_budget_date_idx on txn (budget_id, date);
create index txn_account_idx on txn (account_id, date);
create index txn_category_idx on txn (category_id);

create table txn_split (
    id             uuid primary key,
    transaction_id uuid   not null references txn (id) on delete cascade,
    amount         bigint not null,
    category_id    uuid references category (id) on delete set null,
    payee_id       uuid references payee (id) on delete set null,
    memo           text,
    sort_order     int    not null default 0
);
create index txn_split_txn_idx on txn_split (transaction_id);
create index txn_split_category_idx on txn_split (category_id);

create table month_assignment (
    budget_id   uuid   not null references budget (id) on delete cascade,
    category_id uuid   not null references category (id) on delete cascade,
    month       date   not null check (extract(day from month) = 1),
    assigned    bigint not null default 0,
    primary key (category_id, month)
);
create index month_assignment_budget_idx on month_assignment (budget_id, month);
