# DMT Step 3 — Post Validate
STEP_ID=3
TYPE=POST_VALIDATE
ENGINE=SQL
FIELD_MAP=partner_code:partnerCode
EXPECTED_RESULT=1
QUERY=SELECT COUNT(*) FROM dmt_partner_master WHERE partner_code = ':partnerCode' AND status = 'PENDING'
COMPENSATES_STEP_ID=
