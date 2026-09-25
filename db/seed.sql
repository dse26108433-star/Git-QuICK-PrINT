-- =====================================================================
-- First-time setup. Run AFTER setup.sql.
-- Do ONE STEP AT A TIME: select the lines of a step, then press Run.
-- =====================================================================

-- ---------------------------------------------------------------------
-- STEP 1 - Name and prices.  Prices are in PAISE:  200 = Rs 2.
-- ---------------------------------------------------------------------
update shop_settings
   set center_name       = 'Main Xerox Center',
       price_bw_paise    = 200,     -- Rs 2 per B/W page
       price_color_paise = 1000     -- Rs 10 per colour page
 where id = 1;


-- ---------------------------------------------------------------------
-- STEP 2 - Add your printers. One line per printer.
-- The last-but-one value is the name Windows uses: copy it EXACTLY
-- from   PrinterSmokeTest --list   on the Xerox PC.
-- supports_color: true for a colour Canon, false for B/W only.
-- Delete lines you do not need. Runs only once (skips if printers exist).
-- ---------------------------------------------------------------------
insert into printers (name, windows_printer_name, supports_color)
select * from (values
    ('Printer 1 (B/W)',    'Canon iR2625',          false),
    ('Printer 2 (B/W)',    'Canon iR2625 (2)',      false),
    ('Printer 3 (B/W)',    'Canon iR2625 (3)',      false),
    ('Printer 4 (Colour)', 'Canon iR-ADV C3530',    true)
) as p(name, windows_printer_name, supports_color)
where not exists (select 1 from printers);

-- Typo in a Windows name? Fix it like this:
-- update printers set windows_printer_name = 'Canon iR2625 UFR II' where name = 'Printer 1 (B/W)';
-- Remove a printer you do not have:
-- delete from printers where name = 'Printer 3 (B/W)';
-- Stop the colour printer taking B/W work (saves colour toner time):
-- update printers set accepts_bw = false where supports_color;


-- ---------------------------------------------------------------------
-- STEP 3 - Enroll the Xerox center PC.
-- The result shows agent_id and agent_secret. COPY BOTH NOW.
-- The secret is shown only once. Lost it? Run this again for a new one.
-- ---------------------------------------------------------------------
select * from enroll_agent('Xerox PC');


-- ---------------------------------------------------------------------
-- Handy checks
-- ---------------------------------------------------------------------
-- Is the PC online? (last_seen_at within the last minute)
-- select name, host_name, last_seen_at from agents;
-- Printer status as the PC sees it:
-- select name, windows_printer_name, status, status_detail, status_at from printers;
-- Recent orders:
-- select pickup_code, status, file_name, copies, color, amount_paise, error_message, created_at
--   from orders order by created_at desc limit 20;
