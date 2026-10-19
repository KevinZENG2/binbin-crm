ALTER TABLE crm_ticket ADD COLUMN assigned_at timestamptz NOT NULL DEFAULT now(),ADD COLUMN accepted_at timestamptz;
UPDATE crm_ticket SET assigned_at=created_at,accepted_at=CASE WHEN status<>'NEW' THEN created_at ELSE NULL END;
ALTER TABLE crm_ticket_sla_episode ADD COLUMN paused_total_ms bigint NOT NULL DEFAULT 0 CHECK(paused_total_ms>=0),ADD COLUMN first_response_work_ms bigint;
UPDATE crm_ticket_sla_episode SET first_response_work_ms=greatest((extract(epoch FROM(first_response_at-created_at))*1000)::bigint,0) WHERE first_response_at IS NOT NULL;
CREATE TABLE crm_service_feedback(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_id uuid NOT NULL,cycle_no int NOT NULL,assignee_member_id uuid NOT NULL,
 token_hash varchar(64) NOT NULL UNIQUE,expires_at timestamptz NOT NULL,revoked_at timestamptz,submitted_at timestamptz,
 score int CHECK(score BETWEEN 1 AND 5),comment varchar(2000) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),
 CHECK((submitted_at IS NULL AND score IS NULL) OR(submitted_at IS NOT NULL AND score IS NOT NULL)),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES crm_ticket(tenant_id,id),FOREIGN KEY(tenant_id,assignee_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE UNIQUE INDEX service_feedback_cycle ON crm_service_feedback(tenant_id,ticket_id,cycle_no) WHERE revoked_at IS NULL;
ALTER TABLE crm_service_feedback ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_service_feedback FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_service_feedback USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON crm_service_feedback TO crm_runtime;

DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  INSERT INTO crm_dictionary_item(id,tenant_id,dictionary_type,item_code,label,position) VALUES(gen_random_uuid(),t,'TICKET_TYPE','COMPLAINT','客诉工单',5),(gen_random_uuid(),t,'TICKET_TYPE','SERVICE','服务工单',6) ON CONFLICT DO NOTHING;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
