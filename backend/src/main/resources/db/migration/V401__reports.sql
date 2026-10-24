-- Report support: subscriptions, purchase reviews, devices to replace, per-budget report settings.

-- A recurring payment (streaming plan, gym, rent). Its charges are the spending of its payees;
-- last charge, monthly cost and next renewal are derived from them (see list_subscriptions).
-- kind: SUBSCRIPTION = a plan you could cancel; FIXED = a contract like rent, utilities or insurance.
-- variable: plan plus one-off purchases (app stores), so the monthly cost is a 3-month average.
create table subscription (
    id           uuid primary key,
    budget_id    uuid        not null references budget (id) on delete cascade,
    name         text        not null,
    kind         text        not null default 'SUBSCRIPTION' check (kind in ('SUBSCRIPTION', 'FIXED')),
    cadence      text        not null default 'MONTHLY' check (cadence in ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY')),
    group_name   text,
    note         text,
    variable     boolean     not null default false,
    cancelled_on date,
    created_at   timestamptz not null default now()
);
create index subscription_budget_idx on subscription (budget_id);

-- A payee belongs to at most one subscription.
alter table payee add column subscription_id uuid references subscription (id) on delete set null;
create index payee_subscription_idx on payee (subscription_id);

-- The user's verdict on a purchase (a transaction, or one split line of it): rating 1 = shouldn't
-- have bought it .. 5 = glad I did, or a reason why it was a sensible buy that needs no rating.
create table purchase_review (
    budget_id       uuid        not null references budget (id) on delete cascade,
    transaction_id  uuid        not null references txn (id) on delete cascade,
    split_id        uuid references txn_split (id) on delete cascade,
    rating          smallint check (rating between 1 and 5),
    sensible_reason text,
    updated_at      timestamptz not null default now(),
    check (rating is not null or sensible_reason is not null),
    unique nulls not distinct (transaction_id, split_id)
);
create index purchase_review_budget_idx on purchase_review (budget_id);

-- Devices you own, for the replacement budget: price spread over the expected lifespan.
create table device (
    id             uuid primary key,
    budget_id      uuid        not null references budget (id) on delete cascade,
    name           text        not null,
    price          bigint      not null check (price >= 0),
    purchased_on   date        not null,
    lifespan_years int         not null check (lifespan_years between 1 and 50),
    active         boolean     not null default true,
    note           text,
    created_at     timestamptz not null default now()
);
create index device_budget_idx on device (budget_id);

-- Report choices shared by everyone on the budget (which categories are "things", the deep-dive
-- category and payee, the purchase review threshold, salary assumptions), as a JSON object.
create table report_settings (
    budget_id  uuid primary key references budget (id) on delete cascade,
    settings   jsonb       not null default '{}',
    updated_at timestamptz not null default now()
);
