create table refund_operation (
    operation_id uuid primary key,
    customer_id text not null,
    payment_id text not null,
    amount_minor bigint not null,
    currency varchar(3) not null,
    status varchar(32) not null,
    stripe_refund_id varchar(512),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint refund_operation_amount_ck check (amount_minor > 0),
    constraint refund_operation_currency_ck check (char_length(currency) = 3),
    constraint refund_operation_status_ck check (status in ('PENDING', 'RUNNING', 'SUCCEEDED')),
    constraint refund_operation_customer_payment_uk unique (customer_id, payment_id)
);
