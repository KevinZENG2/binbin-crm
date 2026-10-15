ALTER TABLE sys_role_policy
 ADD COLUMN opportunity_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN opportunity_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN task_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN task_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN activity_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN activity_can_write boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT opportunity_write_requires_read CHECK(NOT opportunity_can_write OR opportunity_can_read),
 ADD CONSTRAINT task_write_requires_read CHECK(NOT task_can_write OR task_can_read),
 ADD CONSTRAINT activity_write_requires_read CHECK(NOT activity_can_write OR activity_can_read),
 ADD CONSTRAINT viewer_sales_readonly CHECK(role_code<>'VIEWER' OR (NOT opportunity_can_write AND NOT task_can_write AND NOT activity_can_write));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET opportunity_can_read=can_read,opportunity_can_write=can_write,
    task_can_read=can_read,task_can_write=can_write,activity_can_read=can_read,activity_can_write=can_write,version=version+1 WHERE tenant_id=t;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
ALTER TABLE crm_lead ADD CONSTRAINT lead_converted_customer_id_unique UNIQUE(tenant_id,converted_customer_id,id);
CREATE TABLE crm_opportunity (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),customer_id uuid NOT NULL,source_lead_id uuid,
 title varchar(200) NOT NULL CHECK(length(trim(title))>0),owner_member_id uuid NOT NULL,
 stage varchar(20) NOT NULL DEFAULT 'CONTACT' CHECK(stage IN ('CONTACT','NEEDS','SOLUTION','NEGOTIATION','READY','WON','LOST')),
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 0 AND 100000000000),actual_amount_cents bigint CHECK(actual_amount_cents BETWEEN 1 AND 100000000000),
 close_date date NOT NULL,next_action varchar(1000) NOT NULL DEFAULT '',description text NOT NULL DEFAULT '',
 closed_at timestamptz,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,source_lead_id) REFERENCES crm_lead(tenant_id,converted_customer_id,id),
 CHECK((stage='WON' AND actual_amount_cents IS NOT NULL AND closed_at IS NOT NULL)
   OR (stage='LOST' AND actual_amount_cents IS NULL AND closed_at IS NOT NULL)
   OR (stage NOT IN ('WON','LOST') AND actual_amount_cents IS NULL AND closed_at IS NULL AND length(trim(next_action))>0))
);
CREATE INDEX opportunity_stage_page_idx ON crm_opportunity(tenant_id,stage,updated_at DESC,id);
CREATE INDEX opportunity_customer_idx ON crm_opportunity(tenant_id,customer_id,owner_member_id);
CREATE TABLE crm_opportunity_history (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,opportunity_id uuid NOT NULL,from_stage varchar(20),to_stage varchar(20) NOT NULL,
 reason varchar(1000) NOT NULL CHECK(length(trim(reason))>0),next_action varchar(1000) NOT NULL DEFAULT '',
 amount_cents bigint NOT NULL,actual_amount_cents bigint,actor_member_id uuid NOT NULL,business_version bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,opportunity_id,business_version),
 FOREIGN KEY(tenant_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_activity (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,customer_id uuid NOT NULL,opportunity_id uuid,
 activity_type varchar(20) NOT NULL CHECK(activity_type IN ('CALL','VISIT','MEETING','EMAIL','OTHER')),
 content varchar(4000) NOT NULL CHECK(length(trim(content))>0),occurred_at timestamptz NOT NULL,
 actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX activity_customer_page_idx ON crm_activity(tenant_id,customer_id,occurred_at DESC,id);
CREATE TABLE crm_task (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,customer_id uuid,opportunity_id uuid,
 title varchar(200) NOT NULL CHECK(length(trim(title))>0),description varchar(4000) NOT NULL DEFAULT '',
 assignee_member_id uuid NOT NULL,created_by_member_id uuid NOT NULL,due_date date NOT NULL,
 priority varchar(10) NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH')),
 status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DONE')),completed_at timestamptz,
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,assignee_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 CHECK(opportunity_id IS NULL OR customer_id IS NOT NULL),
 CHECK((status='DONE' AND completed_at IS NOT NULL) OR (status='PENDING' AND completed_at IS NULL))
);
CREATE INDEX task_assignee_due_idx ON crm_task(tenant_id,assignee_member_id,status,due_date,id);
DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['crm_opportunity','crm_opportunity_history','crm_activity','crm_task'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',name);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',name);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',name);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_opportunity,crm_task TO crm_runtime;
GRANT SELECT,INSERT ON crm_opportunity_history,crm_activity TO crm_runtime;
