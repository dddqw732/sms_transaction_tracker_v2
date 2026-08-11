-- Run this SQL in your Supabase SQL editor before starting the server.

create table if not exists companies (
    id bigserial primary key,
    company_name text not null,
    company_code text not null unique,
    city text not null,
    telegram_api_id text,
    telegram_api_hash text,
    subscription_plan text not null,
    password_hash text not null,
    status text not null check (status in ('active', 'suspended')),
    created_at timestamptz not null default now()
);

create table if not exists transactions (
    id bigserial primary key,
    company_id bigint not null references companies(id) on delete cascade,
    amount double precision not null,
    currency text not null,
    sender text not null,
    sender_number text,
    receiver text not null,
    receiver_number text,
    provider text not null,
    transaction_id text,
    timestamp timestamptz not null,
    balance double precision,
    type text not null,
    raw_sms text not null,
    created_at timestamptz not null default now()
);

create unique index if not exists ux_transactions_company_txn_id
    on transactions (company_id, transaction_id)
    where transaction_id is not null;

create index if not exists ix_transactions_company_timestamp
    on transactions (company_id, timestamp desc);

create table if not exists invoices (
    id bigserial primary key,
    company_id bigint not null references companies(id) on delete cascade,
    invoice_number text not null,
    customer_phone text not null,
    amount double precision not null,
    currency text not null,
    created_at timestamptz not null default now(),
    paid_at timestamptz,
    status text not null default 'pending',
    description text,
    paid_transaction_id bigint,
    unique (company_id, invoice_number)
);

create index if not exists ix_invoices_company_created
    on invoices (company_id, created_at desc);

create table if not exists notifications (
    id bigserial primary key,
    company_id bigint not null references companies(id) on delete cascade,
    transaction_id bigint not null references transactions(id) on delete cascade,
    created_at timestamptz not null default now(),
    read boolean not null default false,
    unique (company_id, transaction_id)
);

create index if not exists ix_notifications_company_unread
    on notifications (company_id, read, created_at desc);
