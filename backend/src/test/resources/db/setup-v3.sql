-- =====================================================================
-- Campus Print (Xerox center) - database setup
--
-- HOW TO RUN: Supabase dashboard -> SQL Editor -> New query ->
-- paste this WHOLE file -> Run.  Safe to run again later.
--
-- If it says "run reset.sql first", you ran an older version before:
-- run reset.sql once, then this file again.
-- =====================================================================

do $$
begin
    if to_regclass('public.app_users') is not null or to_regclass('public.print_jobs') is not null then
        raise exception 'An older version of this project is in the database. Run db/reset.sql first, then run setup.sql again.';
    end if;
end;
$$;

create extension if not exists pgcrypto with schema extensions;

-- =====================================================================
-- PART 1 - TABLES
-- =====================================================================

-- Shop settings: exactly one row. Prices are in paise (100 paise = Rs 1).
create table if not exists shop_settings (
    id                int primary key default 1 check (id = 1),
    center_name       text not null default 'Xerox Center',
    price_bw_paise    int  not null default 200  check (price_bw_paise >= 0),
    price_color_paise int  not null default 1000 check (price_color_paise >= 0),
    currency          text not null default 'INR',
    stamp_code        boolean not null default true,   -- pickup code printed on the first page
    updated_at        timestamptz not null default now()
);
-- Added later (the counter's "Print pickup code on pages" switch). Safe to run again.
alter table shop_settings add column if not exists stamp_code boolean not null default true;
insert into shop_settings (id) values (1) on conflict (id) do nothing;

-- The Xerox center PC (the "agent"). One row per PC.
create table if not exists agents (
    id            uuid primary key default gen_random_uuid(),
    name          text        not null,
    secret_hash   text        not null,
    revoked       boolean     not null default false,
    last_seen_at  timestamptz,
    agent_version text,
    host_name     text,
    created_at    timestamptz not null default now()
);

-- The Canon printers connected to that PC.
-- windows_printer_name must match what Windows shows EXACTLY
-- (the agent's PrinterSmokeTest --list prints it for you).
create table if not exists printers (
    id                   uuid primary key default gen_random_uuid(),
    name                 text        not null,              -- shown to staff: "Printer 1"
    windows_printer_name text        not null,
    supports_color       boolean     not null default false,
    accepts_bw           boolean     not null default true, -- false = colour printer never takes B/W work
    enabled              boolean     not null default true,
    agent_id             uuid        references agents (id) on delete set null, -- null = any PC
    status               text        not null default 'UNKNOWN',                -- READY / MISSING / UNKNOWN
    status_detail        text,
    status_at            timestamptz,
    created_at           timestamptz not null default now()
);

-- Orders. The database is the single source of truth.
create table if not exists orders (
    id                uuid primary key,
    pickup_code       text        not null unique,
    access_key_hash   text        not null,       -- sha256 of the key only the student's device holds

    status            text        not null default 'AWAITING_UPLOAD'
                      check (status in ('AWAITING_UPLOAD', 'AWAITING_PAYMENT', 'QUEUED', 'CLAIMED',
                                        'DOWNLOADING', 'SUBMITTED', 'COMPLETED', 'FAILED',
                                        'CANCELLED', 'EXPIRED')),

    file_name         text        not null,
    file_type         text        not null check (file_type in ('PDF', 'PNG', 'JPEG')),
    storage_path      text        not null unique,
    file_size_bytes   bigint,
    page_count        int,                         -- pages in the file
    page_ranges       text,                        -- pages the student chose, e.g. '333-390'; null = all
    print_pages       int,                         -- pages printed per copy (the price is based on this)
    sha256            text,

    color             boolean     not null default false,
    copies            int         not null default 1 check (copies between 1 and 500),

    amount_paise      int,
    currency          text        not null default 'INR',
    payment_provider  text,                        -- 'razorpay' or 'demo'
    gateway_order_id  text unique,
    gateway_payment_id text,
    paid_at           timestamptz,
    payment_checked_at timestamptz,

    printer_id        uuid        references printers (id) on delete set null,
    agent_id          uuid        references agents (id) on delete set null,
    claim_token       uuid,
    claimed_at        timestamptz,
    lease_expires_at  timestamptz,
    attempts          int         not null default 0,
    max_attempts      int         not null default 3,

    submitted_at      timestamptz,
    completed_at      timestamptz,
    failed_at         timestamptz,
    collected_at      timestamptz,
    file_deleted      boolean     not null default false,
    error_code        text,
    error_message     text,

    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

-- Added later (choosing pages). Safe to run again: databases made before get the new columns.
alter table orders add column if not exists page_ranges text;
alter table orders add column if not exists print_pages int;

create index if not exists idx_orders_queue   on orders (paid_at) where status = 'QUEUED';
create index if not exists idx_orders_lease   on orders (lease_expires_at)
    where status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED');
create index if not exists idx_orders_created on orders (created_at desc);
create index if not exists idx_orders_status  on orders (status, created_at);

-- Every status change, for troubleshooting.
create table if not exists order_events (
    id          bigserial primary key,
    order_id    uuid        not null references orders (id) on delete cascade,
    from_status text,
    to_status   text        not null,
    note        text,
    created_at  timestamptz not null default now()
);
create index if not exists idx_order_events on order_events (order_id, id);

create or replace function touch_updated_at()
returns trigger language plpgsql set search_path = public as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

drop trigger if exists trg_orders_touch on orders;
create trigger trg_orders_touch before update on orders
    for each row execute function touch_updated_at();

-- =====================================================================
-- PART 2 - QUEUE FUNCTIONS (duplicate-print protection lives here)
-- =====================================================================

-- Gives ONE paid order to ONE printer. Two printers asking at the same
-- moment always get different orders (FOR UPDATE SKIP LOCKED).
-- Colour printers take colour work first; B/W printers never get colour work.
create or replace function claim_next_order(
    p_agent_id      uuid,
    p_printer_id    uuid,
    p_lease_seconds int default 300
)
returns setof orders
language plpgsql
set search_path = public
as $$
declare
    v_color boolean;
    v_bw    boolean;
    v_id    uuid;
begin
    select p.supports_color, p.accepts_bw into v_color, v_bw
    from printers p
    where p.id = p_printer_id
      and p.enabled
      and (p.agent_id is null or p.agent_id = p_agent_id);

    if not found then
        return;   -- printer disabled, unknown, or belongs to another PC
    end if;

    select o.id into v_id
    from orders o
    where o.status = 'QUEUED'
      and o.attempts < o.max_attempts
      and ((o.color and v_color) or (not o.color and v_bw))
    order by (o.color and v_color) desc, o.paid_at
    for update skip locked
    limit 1;

    if v_id is null then
        return;
    end if;

    return query
    update orders
       set status           = 'CLAIMED',
           printer_id       = p_printer_id,
           agent_id         = p_agent_id,
           claim_token      = gen_random_uuid(),
           claimed_at       = now(),
           lease_expires_at = now() + make_interval(secs => p_lease_seconds),
           attempts         = attempts + 1,
           error_code       = null,
           error_message    = null
     where id = v_id
    returning *;
end;
$$;

-- The PC gives an order back BEFORE anything reached a printer (for
-- example the download failed). Back on the queue, or FAILED once all
-- attempts are used. Returns the new status, or NULL if not the owner.
create or replace function release_order(
    p_order_id    uuid,
    p_claim_token uuid,
    p_code        text,
    p_message     text
)
returns text
language plpgsql
set search_path = public
as $$
declare
    v_status text;
begin
    update orders
       set status           = case when attempts >= max_attempts then 'FAILED' else 'QUEUED' end,
           printer_id       = case when attempts >= max_attempts then printer_id else null end,
           agent_id         = case when attempts >= max_attempts then agent_id else null end,
           claim_token      = case when attempts >= max_attempts then claim_token else null end,
           lease_expires_at = null,
           failed_at        = case when attempts >= max_attempts then now() else failed_at end,
           error_code       = case when attempts >= max_attempts then p_code else null end,
           error_message    = case when attempts >= max_attempts then p_message else null end
     where id = p_order_id
       and claim_token = p_claim_token
       and status in ('CLAIMED', 'DOWNLOADING')
    returning status into v_status;
    return v_status;
end;
$$;

-- When the PC stops answering. Deliberately asymmetric:
--   CLAIMED / DOWNLOADING -> nothing reached a printer -> back on the queue
--   SUBMITTED             -> paper may be out already  -> FAILED, never automatic reprint
create or replace function reap_expired_leases()
returns table (requeued int, orphaned int)
language plpgsql
set search_path = public
as $$
declare
    v_requeued int := 0;
    v_orphaned int := 0;
begin
    with r as (
        update orders
           set status = 'QUEUED', printer_id = null, agent_id = null,
               claim_token = null, claimed_at = null, lease_expires_at = null
         where status in ('CLAIMED', 'DOWNLOADING')
           and lease_expires_at < now()
           and attempts < max_attempts
        returning id
    )
    select count(*) into v_requeued from r;

    with f as (
        update orders
           set status = 'FAILED', failed_at = now(), error_code = 'LEASE_EXPIRED',
               error_message = 'The Xerox center PC stopped responding before printing.'
         where status in ('CLAIMED', 'DOWNLOADING')
           and lease_expires_at < now()
           and attempts >= max_attempts
        returning id
    )
    select count(*) into v_orphaned from f;

    -- claim_token is kept so the PC can still report COMPLETED later.
    with o as (
        update orders
           set status = 'FAILED', failed_at = now(), error_code = 'AGENT_LOST_AFTER_SUBMIT',
               error_message = 'Sent to the printer, but the PC never confirmed it finished. Staff: check the tray before printing again.'
         where status = 'SUBMITTED'
           and lease_expires_at < now()
        returning id
    )
    select v_orphaned + count(*) into v_orphaned from o;

    requeued := v_requeued;
    orphaned := v_orphaned;
    return next;
end;
$$;

-- State machine guard: no bug anywhere can move an order into an impossible state.
create or replace function guard_order_transition()
returns trigger
language plpgsql
set search_path = public
as $$
declare
    ok boolean;
begin
    if new.status = old.status then
        return new;
    end if;

    ok := case old.status
        when 'AWAITING_UPLOAD'  then new.status in ('AWAITING_PAYMENT', 'FAILED', 'CANCELLED', 'EXPIRED')
        when 'AWAITING_PAYMENT' then new.status in ('QUEUED', 'CANCELLED', 'EXPIRED')
        when 'QUEUED'           then new.status in ('CLAIMED', 'CANCELLED', 'FAILED')
        when 'CLAIMED'          then new.status in ('DOWNLOADING', 'QUEUED', 'FAILED')
        when 'DOWNLOADING'      then new.status in ('SUBMITTED', 'QUEUED', 'FAILED')
        when 'SUBMITTED'        then new.status in ('COMPLETED', 'FAILED')
        -- FAILED -> COMPLETED: the PC reconnects and proves it printed.
        -- FAILED -> QUEUED:    a staff member checked the tray and pressed "Print again".
        when 'FAILED'           then (new.status = 'COMPLETED' and old.error_code = 'AGENT_LOST_AFTER_SUBMIT')
                                     or (new.status = 'QUEUED' and old.paid_at is not null and not old.file_deleted)
        else false   -- COMPLETED, CANCELLED and EXPIRED are final
    end;

    if not ok then
        raise exception 'Illegal order transition % -> % (order %)', old.status, new.status, old.id
            using errcode = 'check_violation';
    end if;

    insert into order_events (order_id, from_status, to_status, note)
    values (old.id, old.status, new.status, new.error_code);
    return new;
end;
$$;

drop trigger if exists trg_orders_guard on orders;
create trigger trg_orders_guard before update of status on orders
    for each row execute function guard_order_transition();

-- Enrolls the Xerox center PC.   Run:  select * from enroll_agent('Xerox PC');
-- Copy agent_id and agent_secret from the result. The secret is shown only once.
create or replace function enroll_agent(p_name text default 'Xerox PC')
returns table (agent_id uuid, agent_secret text)
language plpgsql
set search_path = public, extensions
as $$
declare
    v_secret text := encode(extensions.gen_random_bytes(24), 'hex');
    v_id     uuid;
begin
    insert into agents (name, secret_hash)
    values (p_name, extensions.crypt(v_secret, extensions.gen_salt('bf', 10)))
    returning id into v_id;
    return query select v_id, v_secret;
end;
$$;

-- =====================================================================
-- PART 3 - SECURITY
-- There is no student login. Phones and browsers never talk to the
-- database directly: everything goes through the backend, which checks
-- each order's private key. So apps get NO access to these tables.
-- =====================================================================
alter table shop_settings enable row level security;
alter table agents        enable row level security;
alter table printers      enable row level security;
alter table orders        enable row level security;
alter table order_events  enable row level security;

revoke all on shop_settings, agents, printers, orders, order_events from anon, authenticated;

revoke execute on function claim_next_order(uuid, uuid, int)       from public, anon, authenticated;
revoke execute on function release_order(uuid, uuid, text, text)   from public, anon, authenticated;
revoke execute on function reap_expired_leases()                   from public, anon, authenticated;
revoke execute on function enroll_agent(text)                      from public, anon, authenticated;

-- =====================================================================
-- PART 4 - PRIVATE STORAGE BUCKET FOR THE FILES
-- Nothing is public. Uploads and downloads use 5-minute signed links
-- that only the backend can create.
-- =====================================================================
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('print-documents', 'print-documents', false, 52428800,       -- 50 MB
        array['application/pdf', 'image/png', 'image/jpeg'])
on conflict (id) do update
    set public             = false,
        file_size_limit    = excluded.file_size_limit,
        allowed_mime_types = excluded.allowed_mime_types;

-- Done. You should see "Success. No rows returned".
