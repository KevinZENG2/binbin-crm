ALTER TABLE sys_role_policy ADD COLUMN product_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN product_can_write boolean NOT NULL DEFAULT false,ADD COLUMN quote_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN quote_can_write boolean NOT NULL DEFAULT false,ADD COLUMN quote_pdf_can_export boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT product_write_requires_read CHECK(NOT product_can_write OR product_can_read),
 ADD CONSTRAINT quote_write_requires_read CHECK(NOT quote_can_write OR quote_can_read),
 ADD CONSTRAINT quote_pdf_requires_read CHECK(NOT quote_pdf_can_export OR quote_can_read),
 ADD CONSTRAINT viewer_commerce_readonly CHECK(role_code<>'VIEWER' OR (NOT product_can_write AND NOT quote_can_write AND NOT quote_pdf_can_export));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET product_can_read=can_read AND role_code IN ('SALES_MANAGER','SALES','VIEWER'),
   product_can_write=can_write AND role_code='SALES_MANAGER',quote_can_read=can_read AND role_code IN ('SALES_MANAGER','SALES','VIEWER'),
   quote_can_write=can_write AND role_code IN ('SALES_MANAGER','SALES'),
   quote_pdf_can_export=can_read AND export_can_read AND role_code IN ('SALES_MANAGER','SALES'),version=version+1 WHERE tenant_id=t;
 END LOOP;PERFORM set_config('crm.tenant_id','',true);
END $$;
CREATE TABLE crm_product (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),sku varchar(40) NOT NULL,name varchar(120) NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('PRODUCT','SERVICE')),unit varchar(20) NOT NULL,price_cents bigint NOT NULL CHECK(price_cents BETWEEN 0 AND 100000000000),
 description varchar(1000) NOT NULL DEFAULT '',status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','INACTIVE')),
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,sku)
);
CREATE INDEX product_page ON crm_product(tenant_id,status,updated_at DESC,id);
CREATE TABLE crm_quote_counter(tenant_id uuid NOT NULL REFERENCES sys_tenant(id),quote_year int NOT NULL,next_value bigint NOT NULL CHECK(next_value BETWEEN 1 AND 999999999),PRIMARY KEY(tenant_id,quote_year));
CREATE TABLE crm_quote (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),quote_number varchar(40) NOT NULL,title varchar(200) NOT NULL,
 customer_id uuid NOT NULL,opportunity_id uuid,owner_member_id uuid NOT NULL,
 company_name_snapshot varchar(200) NOT NULL,customer_name_snapshot varchar(200) NOT NULL,opportunity_title_snapshot varchar(200),owner_name_snapshot varchar(100) NOT NULL,
 currency varchar(3) NOT NULL DEFAULT 'CNY' CHECK(currency='CNY'),pricing_mode varchar(20) NOT NULL DEFAULT 'TAX_INCLUDED' CHECK(pricing_mode='TAX_INCLUDED'),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','SUBMITTED','VOID')),
 valid_until date NOT NULL,notes varchar(4000) NOT NULL DEFAULT '',void_reason varchar(500),total_cents bigint NOT NULL CHECK(total_cents BETWEEN 0 AND 100000000000),
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),submitted_at timestamptz,voided_at timestamptz,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,quote_number),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX quote_page ON crm_quote(tenant_id,status,updated_at DESC,id);
CREATE INDEX quote_customer ON crm_quote(tenant_id,customer_id,owner_member_id);
CREATE TABLE crm_quote_line (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,quote_id uuid NOT NULL,line_number int NOT NULL CHECK(line_number BETWEEN 1 AND 100),product_id uuid NOT NULL,
 sku_snapshot varchar(40) NOT NULL,name_snapshot varchar(120) NOT NULL,unit_snapshot varchar(20) NOT NULL,
 quantity numeric(14,4) NOT NULL CHECK(quantity>0 AND quantity<=1000000),unit_price_cents bigint NOT NULL CHECK(unit_price_cents BETWEEN 0 AND 100000000000),
 discount_bps int NOT NULL CHECK(discount_bps BETWEEN 0 AND 10000),amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 0 AND 100000000000),description varchar(300) NOT NULL DEFAULT '',
 UNIQUE(tenant_id,quote_id,line_number),FOREIGN KEY(tenant_id,quote_id) REFERENCES crm_quote(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES crm_product(tenant_id,id)
);
CREATE TABLE crm_quote_revision (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,quote_id uuid NOT NULL,business_version bigint NOT NULL CHECK(business_version>=0),
 action varchar(30) NOT NULL,snapshot jsonb NOT NULL,actor_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,quote_id,business_version),FOREIGN KEY(tenant_id,quote_id) REFERENCES crm_quote(tenant_id,id),FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_product','crm_quote_counter','crm_quote','crm_quote_line','crm_quote_revision'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_product,crm_quote_counter,crm_quote TO crm_runtime;
GRANT SELECT,INSERT,DELETE ON crm_quote_line TO crm_runtime;
GRANT SELECT,INSERT ON crm_quote_revision TO crm_runtime;
