-- =====================================================================
-- Campus Print (Xerox center) - database setup
--
-- HOW TO RUN: Supabase dashboard -> SQL Editor -> New query ->
-- paste this WHOLE file -> Run.  Safe to run again later.
--
-- Upgrading from an earlier Campus Print? Just run this file again: every
-- order you already have is kept (it becomes an order with one document).
-- Best done when nothing is printing.
--
-- If it says "run reset.sql first", you ran a much older version before:
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
    price_bw_paise    int  not null default 200  check (price_bw_paise >= 0),   -- per printed side
    price_color_paise int  not null default 1000 check (price_color_paise >= 0),
    currency          text not null default 'INR',
    stamp_code        boolean not null default true,   -- pickup code printed on the first page
    -- Extra price rules, edited on the counter:
    --   paperSizePercent  {"A3": 200}      A3 costs twice the A4 price per side
    --   mediaTypePercent  {"<paper type>": 300}
    --   finishingPaise    {"STAPLE": 0, "PUNCH": 0, "BIND": 0}   per copy
    pricing           jsonb not null default '{"paperSizePercent": {"A3": 200}, "mediaTypePercent": {}, "finishingPaise": {}}',
    updated_at        timestamptz not null default now()
);
-- Added later. Safe to run again.
alter table shop_settings add column if not exists stamp_code boolean not null default true;
alter table shop_settings add column if not exists pricing jsonb not null
    default '{"paperSizePercent": {"A3": 200}, "mediaTypePercent": {}, "finishingPaise": {}}';
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

-- The printers connected to that PC.
-- windows_printer_name must match what Windows shows EXACTLY.
create table if not exists printers (
    id                   uuid primary key default gen_random_uuid(),
    name                 text        not null,              -- shown to staff: "Printer 1"
    windows_printer_name text        not null,
    supports_color       boolean     not null default false,
    accepts_bw           boolean     not null default true, -- false = colour printer never takes B/W work
    enabled              boolean     not null default true,
    agent_id             uuid        references agents (id) on delete set null, -- null = any PC
    status               text        not null default 'UNKNOWN',                -- READY / MISSING / ERROR / UNKNOWN
    status_detail        text,
    status_at            timestamptz,
    -- What the printer can do, as Windows reports it (found by the Station:
    -- paper sizes, two-sided, stapling, paper types...). Refreshed by itself.
    capabilities         jsonb,
    capabilities_hash    text,
    capabilities_at      timestamptz,
    -- What staff let students choose on this printer (null = sensible defaults).
    offered              jsonb,
    -- Worked out by the server from the two above: what students can really
    -- get from this printer. The print queue sends a document only to a
    -- printer whose "effective" features include everything it needs.
    effective            jsonb,
    rescan_requested     boolean     not null default false,
    created_at           timestamptz not null default now()
);
alter table printers add column if not exists capabilities      jsonb;
alter table printers add column if not exists capabilities_hash text;
alter table printers add column if not exists capabilities_at   timestamptz;
alter table printers add column if not exists offered           jsonb;
alter table printers add column if not exists effective         jsonb;
alter table printers add column if not exists rescan_requested  boolean not null default false;

-- Orders: one pickup code, one payment, one or more documents.
-- The database is the single source of truth.
create table if not exists orders (
    id                uuid primary key,
    pickup_code       text        not null unique,
    access_key_hash   text        not null,       -- sha256 of the key only the student's device holds

    status            text        not null default 'AWAITING_UPLOAD'
                      check (status in ('AWAITING_UPLOAD', 'AWAITING_PAYMENT', 'QUEUED', 'PRINTING',
                                        'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')),

    -- Before version 4 an order was exactly one file. These columns keep
    -- that history; new orders keep their files in order_documents.
    file_name         text,
    file_type         text check (file_type in ('PDF', 'PNG', 'JPEG')),
    storage_path      text unique,
    file_size_bytes   bigint,
    page_count        int,
    page_ranges       text,
    print_pages       int,
    sha256            text,
    color             boolean     not null default false,
    copies            int         not null default 1 check (copies between 1 and 500),

    document_count    int,                         -- set when the order is priced

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

-- Added later. Safe to run again: older databases get the new columns.
alter table orders add column if not exists page_ranges text;
alter table orders add column if not exists print_pages int;
alter table orders add column if not exists document_count int;
alter table orders alter column file_name    drop not null;
alter table orders alter column file_type    drop not null;
alter table orders alter column storage_path drop not null;
-- The allowed statuses change in version 4; the rule is put back at the end of PART 3.
alter table orders drop constraint if exists orders_status_check;

drop index if exists idx_orders_queue;
create index if not exists idx_orders_queue   on orders (paid_at) where status in ('QUEUED', 'PRINTING');
create index if not exists idx_orders_created on orders (created_at desc);
create index if not exists idx_orders_status  on orders (status, created_at);
drop index if exists idx_orders_lease;

-- The documents of an order: each file with its own print settings.
-- Each document is printed as its own job, on a printer that can do
-- everything its settings need (colour, paper size, two-sided, stapling...).
create table if not exists order_documents (
    id                uuid primary key,
    order_id          uuid        not null references orders (id) on delete cascade,
    position          int         not null,        -- 1, 2, 3... the order the student put them in

    status            text        not null default 'UPLOADING'
                      check (status in ('UPLOADING', 'READY', 'REJECTED', 'QUEUED', 'CLAIMED', 'DOWNLOADING',
                                        'SUBMITTED', 'COMPLETED', 'FAILED', 'CANCELLED')),

    file_name         text        not null,
    file_type         text        not null check (file_type in ('PDF', 'PNG', 'JPEG')),
    storage_path      text        not null unique,
    file_size_bytes   bigint,
    sha256            text,
    page_count        int,                         -- pages in the file (1 for a picture)
    image_info        jsonb,                       -- pictures: {widthPx, heightPx, dpi, exifOrientation}

    -- The student's choices, checked and tidied by the server. This is
    -- exactly what the Xerox PC prints: nothing is decided anywhere else.
    settings          jsonb,
    requirements      jsonb,                       -- what a printer must support (from settings)
    legacy_ok         boolean     not null default false,   -- an older Station can print it (A4, one-sided, plain)
    print_pages       int,                         -- chosen pages, per copy
    sides             int,                         -- printed sides per copy (after pages per sheet)
    sheets            int,                         -- sheets of paper per copy (after two-sided)
    amount_paise      int,

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
    file_deleted      boolean     not null default false,
    error_code        text,
    error_message     text,

    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index if not exists idx_documents_order on order_documents (order_id, position);
create index if not exists idx_documents_queue on order_documents (created_at) where status = 'QUEUED';
create index if not exists idx_documents_lease on order_documents (lease_expires_at)
    where status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED');

-- Every status change, for troubleshooting.
create table if not exists order_events (
    id          bigserial primary key,
    order_id    uuid        not null references orders (id) on delete cascade,
    document_id uuid,
    from_status text,
    to_status   text        not null,
    note        text,
    created_at  timestamptz not null default now()
);
alter table order_events add column if not exists document_id uuid;
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
drop trigger if exists trg_documents_touch on order_documents;
create trigger trg_documents_touch before update on order_documents
    for each row execute function touch_updated_at();

-- =====================================================================
-- PART 2 - RULES AND THE PRINT QUEUE (duplicate-print protection lives here)
-- =====================================================================

-- Can this printer print a document that needs these things?
-- p_effective: printers.effective (null = an A4, one-sided printer)
-- p_req:       order_documents.requirements
-- The server has the same rule (PrinterRules.java) and the website too
-- (print-core.js); spec/cases/printer-rules.json keeps all three the same.
create or replace function printer_can_do(p_effective jsonb, p_color boolean, p_bw boolean, p_req jsonb)
returns boolean
language sql
immutable
set search_path = public
as $$
    select
        (case when coalesce((p_req ->> 'color')::boolean, false) then p_color else p_bw end)
        and (p_req ->> 'paperSize' is null
             or coalesce(p_effective -> 'paperSizes', '["A4"]'::jsonb) ? (p_req ->> 'paperSize'))
        and (coalesce(p_req ->> 'duplex', 'ONE_SIDED') = 'ONE_SIDED'
             or coalesce((p_effective ->> 'duplex')::boolean, false))
        and (coalesce(jsonb_array_length(p_req -> 'finishing'), 0) = 0
             or coalesce(p_effective -> 'finishing', '[]'::jsonb) @> (p_req -> 'finishing'))
        and (p_req ->> 'mediaType' is null
             or coalesce(p_effective -> 'mediaTypes', '[]'::jsonb) ? (p_req ->> 'mediaType'))
        and (not coalesce((p_req ->> 'borderless')::boolean, false)
             or coalesce((p_effective ->> 'borderless')::boolean, false))
        and (coalesce(p_req ->> 'quality', 'STANDARD') = 'STANDARD'
             or coalesce((p_effective ->> 'highQuality')::boolean, false));
$$;

-- Order state machine: no bug anywhere can move an order into an impossible state.
--   AWAITING_UPLOAD  the student is adding documents and choosing settings
--   AWAITING_PAYMENT priced and checked by the server; can go back to editing until payment starts
--   QUEUED / PRINTING / COMPLETED / FAILED   follow the documents (see sync_order_status)
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
                                     or (new.status = 'AWAITING_UPLOAD' and old.gateway_order_id is null)
        when 'QUEUED'           then new.status in ('PRINTING', 'COMPLETED', 'FAILED', 'CANCELLED')
        when 'PRINTING'         then new.status in ('QUEUED', 'COMPLETED', 'FAILED', 'CANCELLED')
        when 'FAILED'           then old.paid_at is not null
                                     and new.status in ('QUEUED', 'PRINTING', 'COMPLETED', 'CANCELLED')
        -- one-file orders from before version 4, caught while printing
        when 'CLAIMED'          then new.status in ('PRINTING', 'COMPLETED', 'FAILED')
        when 'DOWNLOADING'      then new.status in ('PRINTING', 'COMPLETED', 'FAILED')
        when 'SUBMITTED'        then new.status in ('PRINTING', 'COMPLETED', 'FAILED')
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

-- Document state machine.
create or replace function guard_document_transition()
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
        when 'UPLOADING'   then new.status in ('READY', 'REJECTED', 'CANCELLED')
        when 'READY'       then new.status in ('QUEUED', 'REJECTED', 'CANCELLED')
        when 'REJECTED'    then new.status in ('CANCELLED')
        when 'QUEUED'      then new.status in ('CLAIMED', 'CANCELLED', 'FAILED')
        when 'CLAIMED'     then new.status in ('DOWNLOADING', 'QUEUED', 'FAILED')
        when 'DOWNLOADING' then new.status in ('SUBMITTED', 'QUEUED', 'FAILED')
        when 'SUBMITTED'   then new.status in ('COMPLETED', 'FAILED')
        -- FAILED -> COMPLETED: the PC reconnects and proves it printed.
        -- FAILED -> QUEUED:    staff checked the tray and pressed "Print again".
        -- FAILED -> CANCELLED: staff gave up on it (refund).
        when 'FAILED'      then (new.status = 'COMPLETED' and old.error_code = 'AGENT_LOST_AFTER_SUBMIT')
                                or (new.status = 'QUEUED' and not old.file_deleted)
                                or new.status = 'CANCELLED'
        else false   -- COMPLETED and CANCELLED are final
    end;

    if not ok then
        raise exception 'Illegal document transition % -> % (document %)', old.status, new.status, old.id
            using errcode = 'check_violation';
    end if;

    insert into order_events (order_id, document_id, from_status, to_status, note)
    values (old.order_id, old.id, old.status, new.status, new.error_code);
    return new;
end;
$$;

drop trigger if exists trg_documents_guard on order_documents;
create trigger trg_documents_guard before update of status on order_documents
    for each row execute function guard_document_transition();

-- Once paid, an order's status simply follows its documents:
--   all printed                         -> COMPLETED
--   one printing, or some done and some waiting -> PRINTING
--   all waiting                         -> QUEUED
--   nothing left to do, but one failed  -> FAILED (the counter sees it)
create or replace function sync_order_status()
returns trigger
language plpgsql
set search_path = public
as $$
declare
    v_status    text;
    v_paid      timestamptz;
    v_new       text;
    n_total     int;
    n_done      int;
    n_active    int;
    n_queued    int;
    n_failed    int;
    n_cancelled int;
    v_code      text;
    v_message   text;
begin
    select status, paid_at into v_status, v_paid from orders where id = new.order_id for update;
    if v_paid is null or v_status not in ('QUEUED', 'PRINTING', 'COMPLETED', 'FAILED') then
        return null;         -- not paid (yet): the order's own status stands
    end if;

    select count(*),
           count(*) filter (where status = 'COMPLETED'),
           count(*) filter (where status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED')),
           count(*) filter (where status = 'QUEUED'),
           count(*) filter (where status = 'FAILED'),
           count(*) filter (where status = 'CANCELLED')
      into n_total, n_done, n_active, n_queued, n_failed, n_cancelled
      from order_documents
     where order_id = new.order_id;

    v_new := case
        when n_total = n_cancelled                         then 'CANCELLED'
        when n_done = n_total - n_cancelled                then 'COMPLETED'
        when n_active > 0                                  then 'PRINTING'
        when n_queued > 0 and (n_done > 0 or n_failed > 0) then 'PRINTING'
        when n_queued > 0                                  then 'QUEUED'
        else                                                    'FAILED'
    end;

    if v_new = v_status then
        return null;
    end if;

    if v_new = 'FAILED' then
        select error_code, error_message into v_code, v_message
          from order_documents
         where order_id = new.order_id and status = 'FAILED'
         order by failed_at desc nulls last
         limit 1;
    end if;

    update orders
       set status        = v_new,
           completed_at  = case when v_new = 'COMPLETED' then coalesce(completed_at, now()) else completed_at end,
           failed_at     = case when v_new = 'FAILED' then now() else failed_at end,
           error_code    = case when v_new = 'FAILED' then v_code
                                when v_new = 'CANCELLED' then 'CANCELLED_AT_COUNTER'
                                else null end,
           error_message = case when v_new = 'FAILED' then v_message
                                when v_new = 'CANCELLED' then 'Cancelled at the counter. Refund is due.'
                                else null end
     where id = new.order_id;
    return null;
end;
$$;

drop trigger if exists trg_documents_sync on order_documents;
create trigger trg_documents_sync after update of status on order_documents
    for each row when (old.status is distinct from new.status)
    execute function sync_order_status();

-- Payment verified: the order and all its documents join the print queue
-- in one step. Returns 1, or 0 if the order was not waiting for payment.
create or replace function mark_order_paid(p_order_id uuid, p_provider text, p_payment_id text)
returns int
language plpgsql
set search_path = public
as $$
declare
    n int;
begin
    update orders
       set status = 'QUEUED', payment_provider = p_provider,
           gateway_payment_id = p_payment_id, paid_at = now()
     where id = p_order_id and status = 'AWAITING_PAYMENT';
    get diagnostics n = row_count;
    if n = 1 then
        update order_documents set status = 'QUEUED' where order_id = p_order_id and status = 'READY';
    end if;
    return n;
end;
$$;

-- Gives ONE paid document to ONE printer. Two printers asking at the same
-- moment always get different documents (FOR UPDATE SKIP LOCKED).
--   * only a printer that can do everything the document needs gets it
--   * a printer first finishes orders it already started, and leaves alone
--     orders another printer is busy with (so one order's pages come out of
--     one printer when possible), then takes the oldest paid order
--   * colour printers take colour work first
--   * p_legacy_only: an older Station asks; it only gets plain A4 one-sided work
create or replace function claim_next_job(
    p_agent_id      uuid,
    p_printer_id    uuid,
    p_lease_seconds int default 300,
    p_legacy_only   boolean default false
)
returns setof order_documents
language plpgsql
set search_path = public
as $$
declare
    v_color     boolean;
    v_bw        boolean;
    v_effective jsonb;
    v_id        uuid;
begin
    select p.supports_color, p.accepts_bw, p.effective into v_color, v_bw, v_effective
    from printers p
    where p.id = p_printer_id
      and p.enabled
      and (p.agent_id is null or p.agent_id = p_agent_id);

    if not found then
        return;   -- printer disabled, unknown, or belongs to another PC
    end if;

    select d.id into v_id
    from order_documents d
    join orders o on o.id = d.order_id
    where d.status = 'QUEUED'
      and d.attempts < d.max_attempts
      and o.status in ('QUEUED', 'PRINTING')
      and (not p_legacy_only or d.legacy_ok)
      and printer_can_do(v_effective, v_color, v_bw, d.requirements)
    order by
        exists (select 1 from order_documents x
                 where x.order_id = d.order_id and x.id <> d.id and x.printer_id = p_printer_id
                   and x.status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED', 'COMPLETED')) desc,
        exists (select 1 from order_documents x
                 where x.order_id = d.order_id and x.id <> d.id and x.printer_id <> p_printer_id
                   and x.status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED')) asc,
        (coalesce((d.requirements ->> 'color')::boolean, false) and v_color) desc,
        o.paid_at,
        d.position
    for update of d skip locked
    limit 1;

    if v_id is null then
        return;
    end if;

    return query
    update order_documents
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

-- The PC gives a document back BEFORE anything reached a printer (for
-- example the download failed). Back on the queue, or FAILED once all
-- attempts are used. Returns the new status, or NULL if not the owner.
create or replace function release_job(
    p_document_id uuid,
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
    update order_documents
       set status           = case when attempts >= max_attempts then 'FAILED' else 'QUEUED' end,
           printer_id       = case when attempts >= max_attempts then printer_id else null end,
           agent_id         = case when attempts >= max_attempts then agent_id else null end,
           claim_token      = case when attempts >= max_attempts then claim_token else null end,
           lease_expires_at = null,
           failed_at        = case when attempts >= max_attempts then now() else failed_at end,
           error_code       = case when attempts >= max_attempts then p_code else null end,
           error_message    = case when attempts >= max_attempts then p_message else null end
     where id = p_document_id
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
        update order_documents
           set status = 'QUEUED', printer_id = null, agent_id = null,
               claim_token = null, claimed_at = null, lease_expires_at = null
         where status in ('CLAIMED', 'DOWNLOADING')
           and lease_expires_at < now()
           and attempts < max_attempts
        returning id
    )
    select count(*) into v_requeued from r;

    with f as (
        update order_documents
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
        update order_documents
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

-- The queue functions of the one-file version are replaced by the ones above.
drop function if exists claim_next_order(uuid, uuid, int);
drop function if exists release_order(uuid, uuid, text, text);

-- =====================================================================
-- PART 3 - UPGRADE FROM THE ONE-FILE VERSION (runs once, then does nothing)
-- Every older order becomes an order with one document, with the same id,
-- so a Station that was printing it can still report back.
-- =====================================================================
insert into order_documents (
    id, order_id, position, status, file_name, file_type, storage_path, file_size_bytes, sha256,
    page_count, settings, requirements, legacy_ok, print_pages, sides, sheets, amount_paise,
    printer_id, agent_id, claim_token, claimed_at, lease_expires_at, attempts, max_attempts,
    submitted_at, completed_at, failed_at, file_deleted, error_code, error_message, created_at)
select
    o.id, o.id, 1,
    case o.status
        when 'AWAITING_UPLOAD'  then 'UPLOADING'
        when 'AWAITING_PAYMENT' then 'READY'
        when 'EXPIRED'          then 'CANCELLED'
        when 'FAILED'           then case when o.paid_at is null then 'REJECTED' else 'FAILED' end
        else o.status
    end,
    o.file_name, o.file_type, o.storage_path, o.file_size_bytes, o.sha256, o.page_count,
    jsonb_build_object('copies', o.copies, 'color', o.color, 'pages', o.page_ranges, 'paperSize', 'A4',
                       'duplex', 'ONE_SIDED', 'orientation', 'AUTO', 'scaling', 'FIT', 'scalePercent', 100,
                       'pagesPerSheet', 1, 'marginMm', 5, 'rotation', 0, 'center', true, 'collate', true,
                       'quality', 'STANDARD'),
    jsonb_build_object('color', o.color, 'paperSize', 'A4'),
    true,
    coalesce(o.print_pages, o.page_count), coalesce(o.print_pages, o.page_count),
    coalesce(o.print_pages, o.page_count), o.amount_paise,
    o.printer_id, o.agent_id, o.claim_token, o.claimed_at, o.lease_expires_at, o.attempts, o.max_attempts,
    o.submitted_at, o.completed_at, o.failed_at, o.file_deleted, o.error_code, o.error_message, o.created_at
from orders o
where o.storage_path is not null
  and not exists (select 1 from order_documents d where d.order_id = o.id);

update orders set document_count = 1 where document_count is null and storage_path is not null;
-- One-file orders that were printing when the database was upgraded.
update orders set status = 'PRINTING' where status in ('CLAIMED', 'DOWNLOADING', 'SUBMITTED');

-- The old order statuses are gone now: tighten the rule.
alter table orders drop constraint if exists orders_status_check;
alter table orders add constraint orders_status_check
    check (status in ('AWAITING_UPLOAD', 'AWAITING_PAYMENT', 'QUEUED', 'PRINTING',
                      'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'));

-- =====================================================================
-- PART 4 - SECURITY
-- There is no student login. Phones and browsers never talk to the
-- database directly: everything goes through the backend, which checks
-- each order's private key. So apps get NO access to these tables.
-- =====================================================================
alter table shop_settings   enable row level security;
alter table agents          enable row level security;
alter table printers        enable row level security;
alter table orders          enable row level security;
alter table order_documents enable row level security;
alter table order_events    enable row level security;

revoke all on shop_settings, agents, printers, orders, order_documents, order_events from anon, authenticated;

revoke execute on function printer_can_do(jsonb, boolean, boolean, jsonb)      from public, anon, authenticated;
revoke execute on function mark_order_paid(uuid, text, text)                   from public, anon, authenticated;
revoke execute on function claim_next_job(uuid, uuid, int, boolean)            from public, anon, authenticated;
revoke execute on function release_job(uuid, uuid, text, text)                 from public, anon, authenticated;
revoke execute on function reap_expired_leases()                               from public, anon, authenticated;
revoke execute on function enroll_agent(text)                                  from public, anon, authenticated;

-- =====================================================================
-- PART 5 - PRIVATE STORAGE BUCKET FOR THE FILES
-- Nothing is public. Uploads and downloads use short-lived signed links
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
