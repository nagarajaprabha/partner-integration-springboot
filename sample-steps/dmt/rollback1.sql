# DMT Rollback Step 4 — Rollback Corridor Config (compensates Step 2)
STEP_ID=4
TYPE=ROLLBACK
ENGINE=SQL
FIELD_MAP=partner_code:partnerCode
QUERY=DELETE FROM dmt_corridor_config WHERE partner_code = ':partnerCode'
COMPENSATES_STEP_ID=2
