ALTER TABLE sys_role_policy ADD COLUMN quote_can_approve boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT quote_approval_requires_manager_read CHECK(NOT quote_can_approve OR (role_code='SALES_MANAGER' AND quote_can_read));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET quote_can_approve=role_code='SALES_MANAGER' AND quote_can_read,version=version+1 WHERE tenant_id=t;
 END LOOP;PERFORM set_config('crm.tenant_id','',true);
END $$;
ALTER TABLE crm_quote DROP CONSTRAINT crm_quote_status_check,
 ADD CONSTRAINT crm_quote_status_check CHECK(status IN('DRAFT','SUBMITTED','APPROVED','REJECTED','VOID'));
CREATE TABLE crm_quote_approval (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,quote_id uuid NOT NULL,submitted_version bigint NOT NULL,
 submitted_member_id uuid NOT NULL,submitted_user_id uuid NOT NULL REFERENCES iam_user(id),approver_member_id uuid NOT NULL,
 submitted_name_snapshot varchar(100) NOT NULL,approver_name_snapshot varchar(100) NOT NULL,
 amount_cents bigint NOT NULL CHECK(amount_cents>0 AND amount_cents<=100000000000),valid_until date NOT NULL,
 quote_snapshot jsonb NOT NULL,snapshot_sha256 varchar(64) NOT NULL CHECK(snapshot_sha256~'^[a-f0-9]{64}$'),rule_snapshot jsonb NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','APPROVED','REJECTED','WITHDRAWN','VOID')),
 created_at timestamptz NOT NULL DEFAULT now(),decided_at timestamptz,decided_member_id uuid,decision_version bigint,reason varchar(500),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,quote_id,submitted_version),
 FOREIGN KEY(tenant_id,quote_id,submitted_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,quote_id,decision_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,submitted_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,approver_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,decided_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 CHECK(submitted_member_id<>approver_member_id),
 CHECK((status='PENDING' AND decided_at IS NULL AND decided_member_id IS NULL AND decision_version IS NULL AND reason IS NULL)
  OR (status<>'PENDING' AND decided_at IS NOT NULL AND decided_member_id IS NOT NULL AND decision_version>submitted_version AND reason IS NOT NULL)),
 CHECK(status<>'REJECTED' OR length(btrim(reason))>0)
);
CREATE UNIQUE INDEX quote_one_pending_approval ON crm_quote_approval(tenant_id,quote_id) WHERE status='PENDING';
CREATE INDEX quote_approval_inbox ON crm_quote_approval(tenant_id,approver_member_id,status,created_at DESC,id);
CREATE TABLE crm_quote_approval_event(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,approval_id uuid NOT NULL,quote_id uuid NOT NULL,business_version bigint NOT NULL,
 action varchar(20) NOT NULL CHECK(action IN('SUBMITTED','APPROVED','REJECTED','WITHDRAWN','VOID')),
 actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,reason varchar(500) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,approval_id) REFERENCES crm_quote_approval(tenant_id,id),
 FOREIGN KEY(tenant_id,quote_id,business_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id),UNIQUE(tenant_id,approval_id,action)
);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_quote_approval','crm_quote_approval_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT ON crm_quote_approval,crm_quote_approval_event TO crm_runtime;
GRANT UPDATE(status,decided_at,decided_member_id,decision_version,reason) ON crm_quote_approval TO crm_runtime;
