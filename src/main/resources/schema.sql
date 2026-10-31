-- PROVEEDOR
create table if not exists subscriptions (
    id                         varchar(36)  primary key,
    url                        varchar(500) not null,
    description                varchar(200),
    secret                     varchar(120) not null,
    previous_secret            varchar(120),
    previous_secret_expires_at bigint       not null default 0,
    events                     varchar(300) not null,
    active                     boolean      not null,
    created_at                 bigint       not null
);

create table if not exists deliveries (
    id               varchar(36)  primary key,
    subscription_id  varchar(36)  not null,
    event_id         varchar(60)  not null,
    event_type       varchar(40)  not null,
    payload          clob         not null,
    status           varchar(12)  not null,
    attempts         int          not null default 0,
    next_attempt_at  bigint       not null,
    claimed_at       bigint,
    last_status_code int,
    last_error       varchar(500),
    created_at       bigint       not null,
    delivered_at     bigint
);
create index if not exists idx_deliveries_due on deliveries (status, next_attempt_at);
create index if not exists idx_deliveries_subscription on deliveries (subscription_id);

create table if not exists attempts (
    id          bigint auto_increment primary key,
    delivery_id varchar(36) not null,
    attempt_no  int         not null,
    status_code int,
    error       varchar(500),
    duration_ms bigint      not null,
    at          bigint      not null
);
create index if not exists idx_attempts_delivery on attempts (delivery_id);

-- NEGOCIO
create table if not exists orders (
    id         varchar(20)    primary key,
    customer   varchar(100)   not null,
    amount     decimal(12, 2) not null,
    status     varchar(12)    not null,
    created_at bigint         not null
);

-- RECEPTOR: la clave primaria es el id del evento (deduplicacion)
create table if not exists received_events (
    event_id    varchar(60) primary key,
    event_type  varchar(40) not null,
    payload     clob        not null,
    received_at bigint      not null
);
