ALTER TABLE sys_role_policy ADD COLUMN contract_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN contract_can_write boolean NOT NULL DEFAULT false,ADD COLUMN contract_can_amend boolean NOT NULL DEFAULT false,
 ADD COLUMN trade_can_export boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT contract_write_requires_read CHECK(NOT contract_can_write OR contract_can_read),
 ADD CONSTRAINT contract_amend_manager CHECK(NOT contract_can_amend OR (role_code='SALES_MANAGER' AND contract_can_write)),
 ADD CONSTRAINT trade_export_requires_read CHECK(NOT trade_can_export OR contract_can_read),
 ADD CONSTRAINT viewer_contract_readonly CHECK(role_code<>'VIEWER' OR (NOT contract_can_write AND NOT trade_can_export));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET contract_can_read=quote_can_read,contract_can_write=quote_can_write,
   contract_can_amend=quote_can_write AND role_code='SALES_MANAGER',trade_can_export=quote_can_read AND export_can_read AND role_code='SALES_MANAGER',version=version+1 WHERE tenant_id=t;
 END LOOP;PERFORM set_config('crm.tenant_id','',true);
END $$;
CREATE TABLE crm_contract_counter(tenant_id uuid NOT NULL REFERENCES sys_tenant(id),contract_year int NOT NULL,next_value bigint NOT NULL CHECK(next_value BETWEEN 1 AND 999999999),PRIMARY KEY(tenant_id,contract_year));
CREATE TABLE crm_contract(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_number varchar(40) NOT NULL,title varchar(200) NOT NULL,
 customer_id uuid NOT NULL,opportunity_id uuid,owner_member_id uuid NOT NULL,created_by_member_id uuid NOT NULL,
 original_quote_id uuid NOT NULL,original_quote_version bigint NOT NULL,current_quote_id uuid NOT NULL,current_quote_version bigint NOT NULL,
 company_name_snapshot varchar(200) NOT NULL,customer_name_snapshot varchar(200) NOT NULL,opportunity_title_snapshot varchar(200),owner_name_snapshot varchar(100) NOT NULL,
 quote_snapshot jsonb NOT NULL,currency varchar(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),total_cents bigint NOT NULL CHECK(total_cents>0 AND total_cents<=100000000000),
 start_date date NOT NULL,end_date date NOT NULL CHECK(end_date>=start_date),notes varchar(4000) NOT NULL DEFAULT '',
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SIGNED','ACTIVE','TERMINATED','VOID')),
 signed_date date,effective_date date,termination_reason varchar(500),void_reason varchar(500),
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),terminated_at timestamptz,voided_at timestamptz,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,customer_id,id),UNIQUE(tenant_id,contract_number),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,original_quote_id,original_quote_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,current_quote_id,current_quote_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 CHECK(status NOT IN('SIGNED','ACTIVE','TERMINATED') OR signed_date IS NOT NULL),
 CHECK(status<>'ACTIVE' OR effective_date IS NOT NULL)
);
CREATE INDEX contract_page ON crm_contract(tenant_id,status,updated_at DESC,id);
CREATE TABLE crm_contract_quote_link(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_id uuid NOT NULL,quote_id uuid NOT NULL,quote_version bigint NOT NULL,approval_id uuid NOT NULL,
 actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,quote_id,quote_version),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES crm_contract(tenant_id,id),
 FOREIGN KEY(tenant_id,quote_id,quote_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,approval_id) REFERENCES crm_quote_approval(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_collection_plan(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_id uuid NOT NULL,line_number int NOT NULL CHECK(line_number BETWEEN 1 AND 24),
 title varchar(100) NOT NULL,amount_cents bigint NOT NULL CHECK(amount_cents>0 AND amount_cents<=100000000000),due_date date NOT NULL,
 active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,contract_id,id),FOREIGN KEY(tenant_id,contract_id) REFERENCES crm_contract(tenant_id,id)
);
CREATE UNIQUE INDEX collection_plan_active_order ON crm_collection_plan(tenant_id,contract_id,line_number) WHERE active;
CREATE TABLE crm_contract_revision(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_id uuid NOT NULL,business_version bigint NOT NULL,action varchar(30) NOT NULL,
 snapshot jsonb NOT NULL,actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,reason varchar(500) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,contract_id,business_version),FOREIGN KEY(tenant_id,contract_id) REFERENCES crm_contract(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_contract_change(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_id uuid NOT NULL,from_version bigint NOT NULL,to_version bigint NOT NULL,
 from_quote_id uuid NOT NULL,from_quote_version bigint NOT NULL,to_quote_id uuid NOT NULL,to_quote_version bigint NOT NULL,
 old_amount_cents bigint NOT NULL,new_amount_cents bigint NOT NULL,reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),
 actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,contract_id,to_version),CHECK(to_version=from_version+1),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES crm_contract(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,from_quote_id,from_quote_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,to_quote_id,to_quote_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version),
 FOREIGN KEY(tenant_id,contract_id,to_version) REFERENCES crm_contract_revision(tenant_id,contract_id,business_version)
);
ALTER TABLE crm_attachment ADD COLUMN contract_id uuid,
 ADD CONSTRAINT attachment_contract_customer FOREIGN KEY(tenant_id,customer_id,contract_id) REFERENCES crm_contract(tenant_id,customer_id,id);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_contract_counter','crm_contract','crm_contract_quote_link','crm_collection_plan','crm_contract_revision','crm_contract_change'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_contract_counter,crm_contract,crm_collection_plan TO crm_runtime;
GRANT SELECT,INSERT ON crm_contract_quote_link,crm_contract_revision,crm_contract_change TO crm_runtime;
