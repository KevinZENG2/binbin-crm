CREATE TABLE crm_sales_order_counter(
 tenant_id uuid NOT NULL REFERENCES sys_tenant(id),order_year int NOT NULL,
 next_value bigint NOT NULL CHECK(next_value BETWEEN 1 AND 999999999),PRIMARY KEY(tenant_id,order_year)
);
CREATE TABLE crm_sales_order(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,order_number varchar(40) NOT NULL,title varchar(200) NOT NULL,
 customer_id uuid NOT NULL,opportunity_id uuid,owner_member_id uuid NOT NULL,created_by_member_id uuid NOT NULL,
 quote_id uuid NOT NULL,quote_version bigint NOT NULL,quote_snapshot jsonb NOT NULL,
 customer_name_snapshot varchar(200) NOT NULL,owner_name_snapshot varchar(100) NOT NULL,
 total_cents bigint NOT NULL CHECK(total_cents>0 AND total_cents<=100000000000),estimated_cost_cents bigint NOT NULL CHECK(estimated_cost_cents BETWEEN 0 AND 100000000000),
 status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','CONFIRMED','IN_DELIVERY','DELIVERED','VOID')),
 expected_delivery_date date,notes varchar(4000) NOT NULL DEFAULT '',confirmed_at timestamptz,void_reason varchar(500),
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,customer_id,id),UNIQUE(tenant_id,order_number),UNIQUE(tenant_id,quote_id,quote_version),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,quote_id,quote_version) REFERENCES crm_quote_revision(tenant_id,quote_id,business_version)
);
CREATE INDEX sales_order_page ON crm_sales_order(tenant_id,status,updated_at DESC,id);
CREATE TABLE crm_sales_order_line(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,order_id uuid NOT NULL,line_number int NOT NULL CHECK(line_number BETWEEN 1 AND 100),
 product_id uuid NOT NULL,sku_snapshot varchar(40) NOT NULL,name_snapshot varchar(120) NOT NULL,unit_snapshot varchar(20) NOT NULL,
 quantity numeric(14,4) NOT NULL CHECK(quantity>0 AND quantity<=1000000),delivered_quantity numeric(14,4) NOT NULL DEFAULT 0 CHECK(delivered_quantity>=0 AND delivered_quantity<=quantity),
 unit_price_cents bigint NOT NULL CHECK(unit_price_cents BETWEEN 0 AND 100000000000),cost_cents bigint NOT NULL CHECK(cost_cents BETWEEN 0 AND 100000000000),
 discount_bps int NOT NULL CHECK(discount_bps BETWEEN 0 AND 10000),amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 0 AND 100000000000),
 description varchar(300) NOT NULL DEFAULT '',UNIQUE(tenant_id,id),UNIQUE(tenant_id,order_id,line_number),
 FOREIGN KEY(tenant_id,order_id) REFERENCES crm_sales_order(tenant_id,id),FOREIGN KEY(tenant_id,product_id) REFERENCES crm_product(tenant_id,id)
);
CREATE TABLE crm_sales_order_event(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,order_id uuid NOT NULL,business_version bigint NOT NULL,
 action varchar(30) NOT NULL,snapshot jsonb NOT NULL,actor_member_id uuid NOT NULL,actor_name_snapshot varchar(100) NOT NULL,
 reason varchar(500) NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,order_id,business_version),
 FOREIGN KEY(tenant_id,order_id) REFERENCES crm_sales_order(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
ALTER TABLE crm_contract ADD COLUMN order_id uuid,
 ADD CONSTRAINT contract_sales_order_customer FOREIGN KEY(tenant_id,customer_id,order_id) REFERENCES crm_sales_order(tenant_id,customer_id,id);
CREATE UNIQUE INDEX contract_sales_order_once ON crm_contract(tenant_id,order_id) WHERE order_id IS NOT NULL;
ALTER TABLE crm_number_rule DROP CONSTRAINT crm_number_rule_document_type_check;
ALTER TABLE crm_number_rule ADD CONSTRAINT crm_number_rule_document_type_check CHECK(document_type IN('QUOTE','ORDER','CONTRACT','RECEIPT','REVERSAL','TICKET'));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  INSERT INTO crm_number_rule(id,tenant_id,document_type,prefix) VALUES(gen_random_uuid(),t,'ORDER','SO') ON CONFLICT DO NOTHING;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_sales_order_counter','crm_sales_order','crm_sales_order_line','crm_sales_order_event'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_sales_order_counter,crm_sales_order,crm_sales_order_line TO crm_runtime;
GRANT SELECT,INSERT ON crm_sales_order_event TO crm_runtime;
