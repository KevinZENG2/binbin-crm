ALTER TABLE crm_product
 ADD COLUMN category varchar(80) NOT NULL DEFAULT '',
 ADD COLUMN cost_cents bigint NOT NULL DEFAULT 0 CHECK(cost_cents BETWEEN 0 AND 100000000000),
 ADD COLUMN tax_rate_bps int NOT NULL DEFAULT 1300 CHECK(tax_rate_bps BETWEEN 0 AND 10000);

ALTER TABLE sys_tenant_member
 ADD COLUMN employee_code varchar(40) NOT NULL DEFAULT '',
 ADD COLUMN job_title varchar(100) NOT NULL DEFAULT '',
 ADD COLUMN sales_region varchar(100) NOT NULL DEFAULT '',
 ADD COLUMN service_status varchar(20) NOT NULL DEFAULT 'AVAILABLE' CHECK(service_status IN ('AVAILABLE','BUSY','OFF_DUTY'));
CREATE UNIQUE INDEX member_employee_code_unique ON sys_tenant_member(tenant_id,employee_code) WHERE employee_code<>'';

CREATE TABLE crm_sales_resource_policy (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),member_id uuid NOT NULL,
 lead_limit int NOT NULL DEFAULT 200 CHECK(lead_limit BETWEEN 1 AND 100000),
 customer_limit int NOT NULL DEFAULT 100 CHECK(customer_limit BETWEEN 1 AND 100000),
 overflow_action varchar(20) NOT NULL DEFAULT 'BLOCK' CHECK(overflow_action IN ('BLOCK','WARN')),
 lead_stale_days int NOT NULL DEFAULT 3 CHECK(lead_stale_days BETWEEN 1 AND 365),
 customer_stale_days int NOT NULL DEFAULT 30 CHECK(customer_stale_days BETWEEN 1 AND 365),
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,member_id),FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
INSERT INTO crm_sales_resource_policy(id,tenant_id,member_id)
 SELECT gen_random_uuid(),tenant_id,id FROM sys_tenant_member ON CONFLICT DO NOTHING;

CREATE TABLE crm_resource_alert (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),member_id uuid NOT NULL,
 resource_type varchar(20) NOT NULL CHECK(resource_type IN ('LEAD','CUSTOMER')),
 current_count int NOT NULL CHECK(current_count>=0),limit_value int NOT NULL CHECK(limit_value>0),
 actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),read_at timestamptz,
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX resource_alert_page ON crm_resource_alert(tenant_id,read_at,created_at DESC);

ALTER TABLE crm_campaign
 ADD COLUMN source_channel varchar(40) NOT NULL DEFAULT 'OTHER' CHECK(source_channel IN ('WEB','SEARCH','SOCIAL','EVENT','REFERRAL','PARTNER','OTHER')),
 ADD COLUMN planned_start date,
 ADD COLUMN planned_end date,
 ADD CONSTRAINT campaign_period_valid CHECK(planned_end IS NULL OR planned_start IS NULL OR planned_end>=planned_start);

DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_sales_resource_policy','crm_resource_alert'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_sales_resource_policy,crm_resource_alert TO crm_runtime;
