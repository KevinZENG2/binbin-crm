CREATE TABLE crm_sales_target(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,member_id uuid,department_id uuid,period_start date NOT NULL,period_months int NOT NULL CHECK(period_months IN(1,12)),
 target_cents bigint NOT NULL CHECK(target_cents BETWEEN 1 AND 100000000000),version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((member_id IS NULL)<>(department_id IS NULL)),CHECK(extract(day FROM period_start)=1),CHECK(period_months=1 OR extract(month FROM period_start)=1),
 UNIQUE(tenant_id,id),UNIQUE NULLS NOT DISTINCT(tenant_id,member_id,department_id,period_start,period_months),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,department_id) REFERENCES org_department(tenant_id,id)
);
CREATE TABLE crm_commission_rule(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,name varchar(100) NOT NULL,tiers jsonb NOT NULL,active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id)
);
CREATE TABLE crm_commission_run(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,member_id uuid NOT NULL,period_start date NOT NULL,rule_id uuid NOT NULL,rule_version bigint NOT NULL,
 base_cents bigint NOT NULL,commission_cents bigint NOT NULL CHECK(commission_cents BETWEEN 0 AND 100000000000),rule_snapshot jsonb NOT NULL,source_hash varchar(64) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','REVIEWED','LOCKED')),created_by_member_id uuid NOT NULL,reviewed_by_member_id uuid,
 version bigint NOT NULL DEFAULT 0,locked_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,member_id,period_start),CHECK(extract(day FROM period_start)=1),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,rule_id) REFERENCES crm_commission_rule(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,reviewed_by_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_commission_adjustment(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,run_id uuid NOT NULL,delta_cents bigint NOT NULL CHECK(delta_cents<>0 AND delta_cents BETWEEN -100000000000 AND 100000000000),
 reason varchar(500) NOT NULL,actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),FOREIGN KEY(tenant_id,run_id) REFERENCES crm_commission_run(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_sales_weekly_report(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,member_id uuid NOT NULL,week_start date NOT NULL,
 progress varchar(4000) NOT NULL,achievements varchar(4000) NOT NULL,risks varchar(4000) NOT NULL,next_actions varchar(4000) NOT NULL,
 confirmed_cents bigint NOT NULL DEFAULT 0,status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED')),
 reviewed_by_member_id uuid,review_reason varchar(500) NOT NULL DEFAULT '',version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,member_id,week_start),CHECK(extract(isodow FROM week_start)=1),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,reviewed_by_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_performance_event(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,entity_type varchar(20) NOT NULL,entity_id uuid NOT NULL,business_version bigint NOT NULL,
 action varchar(30) NOT NULL,reason varchar(500) NOT NULL DEFAULT '',snapshot jsonb NOT NULL,actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,entity_type,entity_id,business_version),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_sales_target','crm_commission_rule','crm_commission_run','crm_commission_adjustment','crm_sales_weekly_report','crm_performance_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_sales_target,crm_commission_rule,crm_commission_run,crm_sales_weekly_report TO crm_runtime;
GRANT SELECT,INSERT ON crm_commission_adjustment,crm_performance_event TO crm_runtime;
