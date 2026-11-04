CREATE TABLE sys_ai_model(
 id uuid PRIMARY KEY,provider varchar(24) NOT NULL CHECK(provider IN('OPENAI','DEEPSEEK','QWEN')),
 model varchar(100) NOT NULL CHECK(model ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$'),
 input_price_cents_per_million bigint NOT NULL CHECK(input_price_cents_per_million BETWEEN 1 AND 100000000),
 output_price_cents_per_million bigint NOT NULL CHECK(output_price_cents_per_million BETWEEN 1 AND 100000000),
 max_output_tokens int NOT NULL CHECK(max_output_tokens BETWEEN 128 AND 8192),active boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(provider,model)
);
CREATE TABLE crm_ai_policy(
 tenant_id uuid PRIMARY KEY REFERENCES sys_tenant(id),enabled boolean NOT NULL DEFAULT false,model_id uuid REFERENCES sys_ai_model(id),
 monthly_token_limit bigint NOT NULL DEFAULT 500000 CHECK(monthly_token_limit BETWEEN 0 AND 100000000),
 monthly_budget_cents bigint NOT NULL DEFAULT 0 CHECK(monthly_budget_cents BETWEEN 0 AND 100000000),
 max_input_chars int NOT NULL DEFAULT 8000 CHECK(max_input_chars BETWEEN 1000 AND 20000),
 max_output_tokens int NOT NULL DEFAULT 512 CHECK(max_output_tokens BETWEEN 128 AND 8192),
 requests_per_minute int NOT NULL DEFAULT 5 CHECK(requests_per_minute BETWEEN 1 AND 60),
 version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE crm_ai_usage(
 tenant_id uuid NOT NULL REFERENCES sys_tenant(id),usage_month date NOT NULL,
 used_tokens bigint NOT NULL DEFAULT 0 CHECK(used_tokens>=0),reserved_tokens bigint NOT NULL DEFAULT 0 CHECK(reserved_tokens>=0),
 estimated_cost_cents bigint NOT NULL DEFAULT 0 CHECK(estimated_cost_cents>=0),reserved_cost_cents bigint NOT NULL DEFAULT 0 CHECK(reserved_cost_cents>=0),
 PRIMARY KEY(tenant_id,usage_month)
);
ALTER TABLE sys_plan ADD COLUMN ai_monthly_token_limit bigint NOT NULL DEFAULT 1000000 CHECK(ai_monthly_token_limit BETWEEN 0 AND 100000000),ADD COLUMN ai_monthly_budget_cents bigint NOT NULL DEFAULT 10000 CHECK(ai_monthly_budget_cents BETWEEN 0 AND 100000000);
ALTER TABLE crm_ai_run DROP CONSTRAINT crm_ai_run_status_check;
ALTER TABLE crm_ai_run ADD CONSTRAINT crm_ai_run_status_check CHECK(status IN('PENDING','RUNNING','SUCCEEDED','FAILED','BLOCKED'));
ALTER TABLE crm_ai_run ADD COLUMN model_id uuid REFERENCES sys_ai_model(id),ADD COLUMN model_version bigint,ADD COLUMN policy_version bigint,
 ADD COLUMN input_price_cents_per_million bigint NOT NULL DEFAULT 0,ADD COLUMN output_price_cents_per_million bigint NOT NULL DEFAULT 0,
 ADD COLUMN reserved_tokens bigint NOT NULL DEFAULT 0,ADD COLUMN reserved_cost_cents bigint NOT NULL DEFAULT 0,
 ADD COLUMN estimated_cost_cents bigint NOT NULL DEFAULT 0,ADD COLUMN usage_estimated boolean NOT NULL DEFAULT false,
 ADD COLUMN started_at timestamptz;
ALTER TABLE crm_ai_policy ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_ai_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_ai_policy USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE crm_ai_usage ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_ai_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_ai_usage USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON sys_ai_model,crm_ai_policy,crm_ai_usage TO crm_runtime;
COMMENT ON TABLE sys_ai_model IS 'Platform-maintained reference CNY prices, not supplier invoices. API keys remain server environment only.';
COMMENT ON TABLE crm_ai_usage IS 'Conservative token and reference-cost reservations; ambiguous provider failures retain estimated usage.';
