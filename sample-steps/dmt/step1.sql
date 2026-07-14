# ═══════════════════════════════════════════════════════════════════
# DMT Step 1 — Insert Partner Master
# FIELD_MAP maps CSV column names → :placeholder names in QUERY.
# CSV columns on the left, placeholder names on the right.
# ═══════════════════════════════════════════════════════════════════
STEP_ID=1
TYPE=EXECUTE
ENGINE=SQL
FIELD_MAP=partner_code:partnerCode,partner_name:partnerName,bank_code:bankCode,corridor_type:corridorType
QUERY=INSERT INTO dmt_partner_master (partner_code, partner_name, bank_code, corridor_type, status, created_at)
      VALUES (':partnerCode', ':partnerName', ':bankCode', ':corridorType', 'PENDING', SYSDATE)
COMPENSATES_STEP_ID=
