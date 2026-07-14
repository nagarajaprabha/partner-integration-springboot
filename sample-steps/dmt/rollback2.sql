# DMT Rollback Step 5 — Rollback Partner Master (compensates Step 1)
STEP_ID=5
TYPE=ROLLBACK
ENGINE=SQL
FIELD_MAP=partner_code:partnerCode
QUERY=DELETE FROM dmt_partner_master WHERE partner_code = ':partnerCode'
COMPENSATES_STEP_ID=1
