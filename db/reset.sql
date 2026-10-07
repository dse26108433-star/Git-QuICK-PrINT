-- =====================================================================
-- RESET - deletes EVERYTHING this project ever created in the database,
-- including old versions (with logins). All orders are lost.
--
-- Run this ONLY if:
--   * you ran an older version of setup.sql before, or
--   * you want to start completely fresh.
-- Then run setup.sql again.
-- =====================================================================
drop trigger if exists on_auth_user_created on auth.users;

drop table if exists payment_alerts cascade;
drop table if exists payment_verifiers cascade;
drop table if exists payment_senders cascade;
drop table if exists document_previews cascade;
drop table if exists order_events  cascade;
drop table if exists order_documents cascade;
drop table if exists orders        cascade;
drop table if exists staff_accounts cascade;
drop table if exists schema_version cascade;
drop table if exists shop_settings cascade;
drop table if exists printers      cascade;
drop table if exists agents        cascade;

-- tables from the old login-based version
drop table if exists job_events    cascade;
drop table if exists print_jobs    cascade;
drop table if exists print_agents  cascade;
drop table if exists app_users     cascade;

drop function if exists handle_new_auth_user() cascade;
drop function if exists claim_next_job(uuid, uuid, int) cascade;
drop function if exists release_job(uuid, uuid, text, text) cascade;
drop function if exists guard_job_transition() cascade;
drop function if exists is_admin() cascade;
drop function if exists enroll_agent(text, uuid) cascade;
drop function if exists enroll_agent(text) cascade;
drop function if exists claim_next_order(uuid, uuid, int) cascade;
drop function if exists release_order(uuid, uuid, text, text) cascade;
drop function if exists guard_order_transition() cascade;
drop function if exists reap_expired_leases() cascade;
drop function if exists claim_next_job(uuid, uuid, int, boolean) cascade;
drop function if exists claim_next_conversion(uuid, int) cascade;
drop function if exists release_job(uuid, uuid, text, text) cascade;
drop function if exists mark_order_paid(uuid, text, text) cascade;
drop function if exists printer_can_do(jsonb, boolean, boolean, jsonb) cascade;
drop function if exists guard_document_transition() cascade;
drop function if exists sync_order_status() cascade;
drop function if exists touch_updated_at() cascade;
drop function if exists upi_start_payment(uuid, text, int) cascade;
drop function if exists upi_pay_order(uuid, uuid, text, text, text) cascade;
drop function if exists upi_match_alert(uuid, int) cascade;
drop function if exists upi_match_order(uuid) cascade;
drop function if exists upi_claim_payment(uuid, text) cascade;
drop function if exists upi_approve_payment(uuid, text) cascade;
drop function if exists staff_pages_used(uuid, timestamptz) cascade;
drop function if exists staff_print(uuid, uuid, timestamptz) cascade;

drop policy if exists docs_insert_own on storage.objects;
drop policy if exists docs_read_own   on storage.objects;
-- The storage bucket is kept. Empty it from Storage in the dashboard if you like.
