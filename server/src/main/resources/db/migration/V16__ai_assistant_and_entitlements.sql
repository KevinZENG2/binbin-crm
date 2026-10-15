ALTER TABLE sys_role_policy
 ADD COLUMN ai_can_use boolean NOT NULL DEFAULT false,
 ADD COLUMN ai_can_review boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT ai_review_requires_use CHECK(NOT ai_can_review OR ai_can_use),
 ADD CONSTRAINT ai_viewer_cannot_use CHECK(role_code<>'VIEWER' OR NOT ai_can_use);

ALTER TABLE sys_plan
 ADD COLUMN configuration_enabled boolean NOT NULL DEFAULT true,
 ADD COLUMN portal_enabled boolean NOT NULL DEFAULT true,
 ADD COLUMN ai_enabled boolean NOT NULL DEFAULT true,
 ADD COLUMN ai_monthly_run_limit integer NOT NULL DEFAULT 100 CHECK(ai_monthly_run_limit BETWEEN 0 AND 1000000);

DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET
   ai_can_use=role_code IN('TENANT_ADMIN','SALES_MANAGER','SALES'),
   ai_can_review=role_code IN('TENANT_ADMIN','SALES_MANAGER'),version=version+1
  WHERE tenant_id=t;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;

CREATE TABLE crm_ai_run(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),member_id uuid NOT NULL,
 task_type varchar(32) NOT NULL CHECK(task_type IN('CUSTOMER_SUMMARY','OPPORTUNITY_COACH','TICKET_REPLY')),
 entity_type varchar(20) NOT NULL CHECK(entity_type IN('CUSTOMER','OPPORTUNITY','TICKET')),entity_id uuid NOT NULL,
 provider varchar(40) NOT NULL,model varchar(100) NOT NULL,status varchar(16) NOT NULL CHECK(status IN('PENDING','SUCCEEDED','FAILED','BLOCKED')),
 prompt_hash char(64) NOT NULL,context_preview varchar(1000) NOT NULL DEFAULT '',result_text varchar(12000),
 input_tokens integer NOT NULL DEFAULT 0 CHECK(input_tokens>=0),output_tokens integer NOT NULL DEFAULT 0 CHECK(output_tokens>=0),
 cost_micros bigint NOT NULL DEFAULT 0 CHECK(cost_micros>=0),error_code varchar(80),
 created_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX crm_ai_run_tenant_month ON crm_ai_run(tenant_id,created_at DESC,id);
CREATE INDEX crm_ai_run_entity ON crm_ai_run(tenant_id,entity_type,entity_id,created_at DESC);
CREATE TABLE crm_ai_feedback(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,run_id uuid NOT NULL,member_id uuid NOT NULL,
 rating smallint NOT NULL CHECK(rating IN(-1,1)),notes varchar(1000) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,run_id,member_id),
 FOREIGN KEY(tenant_id,run_id) REFERENCES crm_ai_run(tenant_id,id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);

ALTER TABLE crm_ai_run ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_ai_run FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_ai_run USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE crm_ai_feedback ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_ai_feedback FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_ai_feedback USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);

GRANT SELECT,INSERT,UPDATE ON crm_ai_run TO crm_runtime;
GRANT SELECT,INSERT,UPDATE ON crm_ai_feedback TO crm_runtime;

COMMENT ON TABLE crm_ai_run IS 'Audited AI execution metadata. Raw prompts and complete business records are never persisted.';
COMMENT ON COLUMN crm_ai_run.context_preview IS 'Redacted business context summary; excludes contact details, credentials and attachments.';
