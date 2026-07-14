# DMT Step 2 — Insert Corridor Config
# Different columns from same CSV row than Step 1
STEP_ID=2
TYPE=EXECUTE
ENGINE=SQL
FIELD_MAP=partner_code:partnerCode,settlement_cycle:settlementCycle,max_txn_limit:maxTxnLimit,callback_url:callbackUrl
QUERY=INSERT INTO dmt_corridor_config (partner_code, settlement_cycle, max_txn_limit, callback_url)
      VALUES (':partnerCode', ':settlementCycle', ':maxTxnLimit', ':callbackUrl')
COMPENSATES_STEP_ID=
