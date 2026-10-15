ALTER TABLE sys_role_policy ADD COLUMN receipt_can_read boolean NOT NULL DEFAULT false,ADD COLUMN receipt_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN receipt_can_confirm boolean NOT NULL DEFAULT false,ADD COLUMN receipt_can_reverse boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT receipt_requires_contract_read CHECK(NOT receipt_can_read OR contract_can_read),
 ADD CONSTRAINT receipt_write_requires_read CHECK(NOT receipt_can_write OR receipt_can_read),
 ADD CONSTRAINT receipt_decision_manager CHECK(NOT (receipt_can_confirm OR receipt_can_reverse) OR(role_code='SALES_MANAGER' AND receipt_can_read)),
 ADD CONSTRAINT viewer_receipt_readonly CHECK(role_code<>'VIEWER' OR NOT receipt_can_write);
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET receipt_can_read=contract_can_read,receipt_can_write=contract_can_write,
   receipt_can_confirm=contract_can_read AND role_code='SALES_MANAGER',receipt_can_reverse=contract_can_read AND role_code='SALES_MANAGER',version=version+1 WHERE tenant_id=t;
 END LOOP;PERFORM set_config('crm.tenant_id','',true);
END $$;
CREATE TABLE crm_receipt_counter(tenant_id uuid NOT NULL REFERENCES sys_tenant(id),receipt_year int NOT NULL,kind varchar(2) NOT NULL CHECK(kind IN('RC','RV')),next_value bigint NOT NULL CHECK(next_value BETWEEN 1 AND 999999999),PRIMARY KEY(tenant_id,receipt_year,kind));
CREATE TABLE crm_receipt(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_id uuid NOT NULL,customer_id uuid NOT NULL,plan_id uuid NOT NULL,
 receipt_number varchar(40) NOT NULL,amount_cents bigint NOT NULL CHECK(amount_cents>0 AND amount_cents<=100000000000),received_date date NOT NULL,
 payment_reference varchar(120) NOT NULL,reference_norm varchar(120) NOT NULL CHECK(length(reference_norm)>0),payer_name varchar(120) NOT NULL DEFAULT '',bank_name varchar(120) NOT NULL DEFAULT '',notes varchar(2000) NOT NULL DEFAULT '',
 plan_title_snapshot varchar(100) NOT NULL,contract_number_snapshot varchar(40) NOT NULL,customer_name_snapshot varchar(200) NOT NULL,
 created_by_member_id uuid NOT NULL,creator_name_snapshot varchar(100) NOT NULL,status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','CONFIRMED','REVERSED','VOID')),
 proof_attachment_id uuid,correction_of_receipt_id uuid,confirmed_by_member_id uuid,confirmer_name_snapshot varchar(100),confirmed_at timestamptz,
 void_reason varchar(500),version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,contract_id,id),UNIQUE(tenant_id,receipt_number),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES crm_contract(tenant_id,id),FOREIGN KEY(tenant_id,customer_id,contract_id) REFERENCES crm_contract(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,contract_id,plan_id) REFERENCES crm_collection_plan(tenant_id,contract_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,confirmed_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,proof_attachment_id) REFERENCES crm_attachment(tenant_id,id),FOREIGN KEY(tenant_id,correction_of_receipt_id) REFERENCES crm_receipt(tenant_id,id),
 CHECK(correction_of_receipt_id IS NULL OR correction_of_receipt_id<>id),
 CHECK(status NOT IN('CONFIRMED','REVERSED') OR(confirmed_by_member_id IS NOT NULL AND confirmed_at IS NOT NULL))
);
CREATE UNIQUE INDEX receipt_reference_live_unique ON crm_receipt(tenant_id,reference_norm) WHERE status IN('DRAFT','CONFIRMED');
CREATE UNIQUE INDEX receipt_correction_chain_unique ON crm_receipt(tenant_id,correction_of_receipt_id) WHERE correction_of_receipt_id IS NOT NULL AND status<>'VOID';
CREATE INDEX receipt_contract_page ON crm_receipt(tenant_id,contract_id,status,received_date DESC,id);
CREATE TABLE crm_receipt_reversal(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,receipt_id uuid NOT NULL,contract_id uuid NOT NULL,plan_id uuid NOT NULL,
 reversal_number varchar(40) NOT NULL,amount_cents bigint NOT NULL CHECK(amount_cents>0 AND amount_cents<=100000000000),reversal_date date NOT NULL,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,proof_attachment_id uuid,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,reversal_number),
 FOREIGN KEY(tenant_id,contract_id,receipt_id) REFERENCES crm_receipt(tenant_id,contract_id,id),FOREIGN KEY(tenant_id,contract_id,plan_id) REFERENCES crm_collection_plan(tenant_id,contract_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,proof_attachment_id) REFERENCES crm_attachment(tenant_id,id)
);
CREATE TABLE crm_receipt_revision(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,receipt_id uuid NOT NULL,business_version bigint NOT NULL,action varchar(30) NOT NULL,
 snapshot jsonb NOT NULL,actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,reason varchar(500) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,receipt_id,business_version),FOREIGN KEY(tenant_id,receipt_id) REFERENCES crm_receipt(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
ALTER TABLE crm_attachment ADD COLUMN receipt_id uuid,
 ADD CONSTRAINT receipt_attachment_contract_required CHECK(receipt_id IS NULL OR contract_id IS NOT NULL),
 ADD CONSTRAINT attachment_receipt_contract FOREIGN KEY(tenant_id,contract_id,receipt_id) REFERENCES crm_receipt(tenant_id,contract_id,id);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_receipt_counter','crm_receipt','crm_receipt_reversal','crm_receipt_revision'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_receipt_counter,crm_receipt TO crm_runtime;
GRANT SELECT,INSERT ON crm_receipt_reversal,crm_receipt_revision TO crm_runtime;
