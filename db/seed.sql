-- =====================================================================
-- First-time setup. Run AFTER setup.sql.
-- Do ONE STEP AT A TIME: select the lines of a step, then press Run.
--
-- With XeoGo Station (the usual way) only STEP 1 is needed, and even
-- that can be done in the Station (Settings). The Station adds the printers
-- itself, reads what each can do (paper sizes, two-sided, stapling...) and
-- lets staff choose what students may pick.
-- =====================================================================

-- ---------------------------------------------------------------------
-- STEP 1 - Name and prices.  Prices are in PAISE:  200 = Rs 2.
-- A price is per PRINTED SIDE: a two-sided sheet is two sides.
-- Extra for bigger paper, special paper and finishing: shop_settings.pricing
-- (A3 = 200 % to start with), easiest to change in the Station.
-- ---------------------------------------------------------------------
update shop_settings
   set center_name       = 'Main Xerox Center',
       price_bw_paise    = 200,     -- Rs 2 per printed B/W side
       price_color_paise = 1000     -- Rs 10 per printed colour side
 where id = 1;


-- ---------------------------------------------------------------------
-- STEP 2 - Only WITHOUT the Station: add your printers by hand.
-- Printers added here offer plain A4 one-sided printing until a
-- Station 4.0 reads what they can really do.
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
-- What students may choose on each printer (what it can do, narrowed by staff):
-- select name, effective from printers;
-- Recent orders with their files:
-- select o.pickup_code, o.status, d.position, d.file_name, d.status, d.settings, d.amount_paise
--   from orders o join order_documents d on d.order_id = o.id order by o.created_at desc, d.position limit 40;
