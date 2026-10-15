ALTER TABLE sys_role_policy
 ADD COLUMN ticket_can_read boolean NOT NULL DEFAULT false,ADD COLUMN ticket_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN sla_can_manage boolean NOT NULL DEFAULT false,ADD COLUMN knowledge_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN knowledge_can_write boolean NOT NULL DEFAULT false,ADD COLUMN knowledge_can_publish boolean NOT NULL DEFAULT false,
 ADD COLUMN campaign_can_read boolean NOT NULL DEFAULT false,ADD COLUMN campaign_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN campaign_can_launch boolean NOT NULL DEFAULT false,ADD COLUMN integration_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN integration_can_manage boolean NOT NULL DEFAULT false,ADD COLUMN p3_can_export boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT ticket_write_read CHECK(NOT ticket_can_write OR ticket_can_read),
 ADD CONSTRAINT sla_manager CHECK(NOT sla_can_manage OR(role_code='SALES_MANAGER' AND ticket_can_read)),
 ADD CONSTRAINT knowledge_write_read CHECK(NOT knowledge_can_write OR knowledge_can_read),
 ADD CONSTRAINT knowledge_publish_manager CHECK(NOT knowledge_can_publish OR(role_code='SALES_MANAGER' AND knowledge_can_write)),
 ADD CONSTRAINT campaign_write_read CHECK(NOT campaign_can_write OR campaign_can_read),
 ADD CONSTRAINT campaign_launch_manager CHECK(NOT campaign_can_launch OR(role_code='SALES_MANAGER' AND campaign_can_write)),
 ADD CONSTRAINT integration_manage_admin CHECK(NOT integration_can_manage OR(role_code='TENANT_ADMIN' AND integration_can_read)),
 ADD CONSTRAINT viewer_p3_readonly CHECK(role_code<>'VIEWER' OR NOT(ticket_can_write OR sla_can_manage OR knowledge_can_write OR knowledge_can_publish OR campaign_can_write OR campaign_can_launch OR integration_can_manage OR p3_can_export));
DO $$DECLARE t uuid;BEGIN FOR t IN SELECT id FROM sys_tenant LOOP PERFORM set_config('crm.tenant_id',t::text,true);
 UPDATE sys_role_policy SET ticket_can_read=can_read,ticket_can_write=can_write,sla_can_manage=can_read AND role_code='SALES_MANAGER',p3_can_export=export_can_read AND role_code='SALES_MANAGER',version=version+1 WHERE tenant_id=t;
 END LOOP;PERFORM set_config('crm.tenant_id','',true);END$$;
CREATE TABLE crm_ticket_counter(tenant_id uuid NOT NULL REFERENCES sys_tenant(id),ticket_year int NOT NULL,next_value bigint NOT NULL CHECK(next_value BETWEEN 1 AND 999999999),PRIMARY KEY(tenant_id,ticket_year));
CREATE TABLE crm_sla_policy(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,name varchar(100) NOT NULL,targets jsonb NOT NULL,pause_waiting boolean NOT NULL,escalate boolean NOT NULL,active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id));
CREATE TABLE crm_ticket(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_number varchar(40) NOT NULL,title varchar(200) NOT NULL,description varchar(10000) NOT NULL DEFAULT '',
 customer_id uuid,owner_member_id uuid NOT NULL,assignee_member_id uuid NOT NULL,requester_name varchar(100) NOT NULL DEFAULT '',requester_email varchar(254) NOT NULL DEFAULT '',
 priority varchar(10) NOT NULL CHECK(priority IN('URGENT','HIGH','NORMAL','LOW')),status varchar(30) NOT NULL DEFAULT 'NEW' CHECK(status IN('NEW','IN_PROGRESS','WAITING_CUSTOMER','RESOLVED','CLOSED','CANCELLED')),
 sla_policy_id uuid,sla_snapshot jsonb NOT NULL,cycle_no int NOT NULL DEFAULT 1 CHECK(cycle_no BETWEEN 1 AND 1000),resolution varchar(4000) NOT NULL DEFAULT '',version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,ticket_number),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,assignee_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,sla_policy_id) REFERENCES crm_sla_policy(tenant_id,id));
CREATE INDEX ticket_workspace ON crm_ticket(tenant_id,status,assignee_member_id,updated_at);
CREATE TABLE crm_ticket_sla_episode(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_id uuid NOT NULL,cycle_no int NOT NULL,
 first_response_due_at timestamptz,resolution_due_at timestamptz,first_response_at timestamptz,resolved_at timestamptz,paused_at timestamptz,
 response_remaining_ms bigint,resolution_remaining_ms bigint,response_breached_at timestamptz,resolution_breached_at timestamptz,
 state varchar(20) NOT NULL CHECK(state IN('ACTIVE','PAUSED','RESOLVED','CANCELLED')),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,ticket_id,cycle_no),FOREIGN KEY(tenant_id,ticket_id) REFERENCES crm_ticket(tenant_id,id));
CREATE INDEX sla_pending ON crm_ticket_sla_episode(tenant_id,state,resolution_due_at);
CREATE TABLE crm_ticket_comment(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_id uuid NOT NULL,body varchar(4000) NOT NULL CHECK(length(btrim(body))>0),
 kind varchar(20) NOT NULL CHECK(kind IN('PUBLIC_REPLY','INTERNAL_NOTE')),actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,ticket_id) REFERENCES crm_ticket(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id));
CREATE TABLE crm_ticket_revision(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_id uuid NOT NULL,business_version bigint NOT NULL,action varchar(40) NOT NULL,snapshot jsonb NOT NULL,reason varchar(1000) NOT NULL DEFAULT '',actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,ticket_id,business_version),FOREIGN KEY(tenant_id,ticket_id) REFERENCES crm_ticket(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id));
CREATE TABLE crm_sla_breach(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_id uuid NOT NULL,episode_id uuid NOT NULL,kind varchar(20) NOT NULL CHECK(kind IN('RESPONSE','RESOLUTION')),due_at timestamptz NOT NULL,detected_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,episode_id,kind),FOREIGN KEY(tenant_id,ticket_id) REFERENCES crm_ticket(tenant_id,id),FOREIGN KEY(tenant_id,episode_id) REFERENCES crm_ticket_sla_episode(tenant_id,id));
CREATE TABLE crm_service_notification(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,ticket_id uuid NOT NULL,member_id uuid NOT NULL,topic varchar(40) NOT NULL,event_key varchar(100) NOT NULL,read_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,member_id,event_key),FOREIGN KEY(tenant_id,ticket_id) REFERENCES crm_ticket(tenant_id,id),FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id));
DO $$DECLARE n text;BEGIN FOREACH n IN ARRAY ARRAY['crm_ticket_counter','crm_sla_policy','crm_ticket','crm_ticket_sla_episode','crm_ticket_comment','crm_ticket_revision','crm_sla_breach','crm_service_notification'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
 EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);END LOOP;END$$;
GRANT SELECT,INSERT,UPDATE ON crm_ticket_counter,crm_sla_policy,crm_ticket,crm_ticket_sla_episode,crm_service_notification TO crm_runtime;
GRANT SELECT,INSERT ON crm_ticket_comment,crm_ticket_revision,crm_sla_breach TO crm_runtime;

ALTER TABLE crm_ticket ADD CONSTRAINT ticket_customer_identity UNIQUE(tenant_id,customer_id,id);
ALTER TABLE crm_attachment ADD COLUMN ticket_id uuid,
 ADD CONSTRAINT attachment_ticket_identity FOREIGN KEY(tenant_id,customer_id,ticket_id) REFERENCES crm_ticket(tenant_id,customer_id,id),
 ADD CONSTRAINT attachment_ticket_not_commercial CHECK(ticket_id IS NULL OR(contract_id IS NULL AND receipt_id IS NULL AND opportunity_id IS NULL));
CREATE INDEX attachment_ticket ON crm_attachment(tenant_id,ticket_id) WHERE ticket_id IS NOT NULL;
