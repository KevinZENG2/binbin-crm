CREATE TABLE sys_subscription_order(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),plan_id uuid NOT NULL REFERENCES sys_plan(id),
 order_number varchar(40) NOT NULL UNIQUE,period_months integer NOT NULL CHECK(period_months IN(1,3,6,12,24,36)),
 amount_cents bigint NOT NULL CHECK(amount_cents>=0),status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN('DRAFT','PENDING','PAID','CANCELLED')),
 due_date date,external_reference varchar(100),notes varchar(500) NOT NULL DEFAULT '',created_by_user_id uuid NOT NULL REFERENCES iam_user(id),
 paid_at timestamptz,cancelled_at timestamptz,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sys_subscription_order_tenant ON sys_subscription_order(tenant_id,created_at DESC,id);
CREATE INDEX sys_subscription_order_status ON sys_subscription_order(status,due_date,created_at DESC);

CREATE TABLE sys_usage_monthly(
 tenant_id uuid NOT NULL REFERENCES sys_tenant(id),usage_month date NOT NULL CHECK(usage_month=date_trunc('month',usage_month)::date),
 ai_runs integer NOT NULL DEFAULT 0 CHECK(ai_runs>=0),portal_logins integer NOT NULL DEFAULT 0 CHECK(portal_logins>=0),
 updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(tenant_id,usage_month)
);

GRANT SELECT,INSERT,UPDATE ON sys_subscription_order,sys_usage_monthly TO crm_runtime;

COMMENT ON TABLE sys_subscription_order IS 'Manual subscription order ledger. It does not charge cards or call a payment processor.';
COMMENT ON TABLE sys_usage_monthly IS 'Commercial feature usage counters; amounts and entitlements remain authoritative in plan and order tables.';
