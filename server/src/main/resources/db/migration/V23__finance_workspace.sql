CREATE TABLE crm_fund_account(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),name varchar(100) NOT NULL,
 account_reference varchar(120) NOT NULL DEFAULT '',active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,name)
);
ALTER TABLE crm_receipt ADD COLUMN fund_account_id uuid,
 ADD FOREIGN KEY(tenant_id,fund_account_id) REFERENCES crm_fund_account(tenant_id,id);
CREATE TABLE crm_fund_entry(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,account_id uuid NOT NULL,source_type varchar(30) NOT NULL CHECK(source_type IN('OPENING','RECEIPT','RECEIPT_REVERSAL','EXPENSE','EXPENSE_REVERSAL')),
 source_id uuid NOT NULL,delta_cents bigint NOT NULL CHECK(delta_cents BETWEEN -100000000000 AND 100000000000),business_date date NOT NULL,
 reference varchar(120) NOT NULL DEFAULT '',notes varchar(2000) NOT NULL DEFAULT '',actor_member_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,source_type,source_id),
 FOREIGN KEY(tenant_id,account_id) REFERENCES crm_fund_account(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX fund_entry_account_page ON crm_fund_entry(tenant_id,account_id,business_date,created_at);
CREATE TABLE crm_invoice(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,contract_id uuid NOT NULL,invoice_number varchar(40) NOT NULL,
 title varchar(200) NOT NULL,buyer_name varchar(200) NOT NULL,tax_number varchar(80) NOT NULL DEFAULT '',
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 1 AND 100000000000),red_cents bigint NOT NULL DEFAULT 0 CHECK(red_cents>=0 AND red_cents<=amount_cents),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','SUBMITTED','APPROVED','ISSUED','VOID')),
 invoice_reference varchar(120),issued_date date,notes varchar(2000) NOT NULL DEFAULT '',
 created_by_member_id uuid NOT NULL,approved_by_member_id uuid,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,invoice_number),
 FOREIGN KEY(tenant_id,contract_id) REFERENCES crm_contract(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,approved_by_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE UNIQUE INDEX invoice_reference_once ON crm_invoice(tenant_id,invoice_reference) WHERE invoice_reference IS NOT NULL;
CREATE TABLE crm_invoice_red(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,invoice_id uuid NOT NULL,amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 1 AND 100000000000),
 red_reference varchar(120) NOT NULL,red_date date NOT NULL,reason varchar(500) NOT NULL,actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,red_reference),FOREIGN KEY(tenant_id,invoice_id) REFERENCES crm_invoice(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_expense(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,account_id uuid NOT NULL,expense_number varchar(40) NOT NULL,
 payee varchar(200) NOT NULL,category varchar(80) NOT NULL,amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 1 AND 100000000000),
 payment_date date NOT NULL,payment_reference varchar(120) NOT NULL,notes varchar(2000) NOT NULL DEFAULT '',
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','CONFIRMED','VOID','REVERSED')),
 created_by_member_id uuid NOT NULL,confirmed_by_member_id uuid,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,expense_number),UNIQUE(tenant_id,payment_reference),
 FOREIGN KEY(tenant_id,account_id) REFERENCES crm_fund_account(tenant_id,id),FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,confirmed_by_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_finance_event(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,entity_type varchar(20) NOT NULL CHECK(entity_type IN('INVOICE','EXPENSE','ACCOUNT')),
 entity_id uuid NOT NULL,business_version bigint NOT NULL,action varchar(30) NOT NULL,reason varchar(500) NOT NULL DEFAULT '',snapshot jsonb NOT NULL,
 actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,entity_type,entity_id,business_version),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_fund_account','crm_fund_entry','crm_invoice','crm_invoice_red','crm_expense','crm_finance_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_fund_account,crm_invoice,crm_expense TO crm_runtime;
GRANT SELECT,INSERT ON crm_fund_entry,crm_invoice_red,crm_finance_event TO crm_runtime;
