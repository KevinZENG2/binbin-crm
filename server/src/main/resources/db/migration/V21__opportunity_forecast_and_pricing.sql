CREATE TABLE crm_opportunity_line (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES sys_tenant(id), opportunity_id uuid NOT NULL,
 line_number int NOT NULL CHECK(line_number BETWEEN 1 AND 100), product_id uuid NOT NULL,
 sku_snapshot varchar(40) NOT NULL, name_snapshot varchar(120) NOT NULL, unit_snapshot varchar(20) NOT NULL,
 quantity numeric(11,4) NOT NULL CHECK(quantity>0 AND quantity<=1000000),
 unit_price_cents bigint NOT NULL CHECK(unit_price_cents BETWEEN 0 AND 100000000000),
 discount_bps int NOT NULL DEFAULT 0 CHECK(discount_bps BETWEEN 0 AND 10000),
 amount_cents bigint NOT NULL CHECK(amount_cents BETWEEN 0 AND 100000000000),
 description varchar(300) NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,opportunity_id,line_number), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,id),
 FOREIGN KEY(tenant_id,product_id) REFERENCES crm_product(tenant_id,id)
);
CREATE INDEX opportunity_line_product_idx ON crm_opportunity_line(tenant_id,product_id);
ALTER TABLE crm_opportunity_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_opportunity_line FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_opportunity_line USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,DELETE ON crm_opportunity_line TO crm_runtime;

ALTER TABLE crm_quote_line ADD COLUMN cost_cents bigint NOT NULL DEFAULT 0 CHECK(cost_cents BETWEEN 0 AND 100000000000);
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT DISTINCT tenant_id FROM crm_quote_line LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE crm_quote_line l SET cost_cents=p.cost_cents FROM crm_product p WHERE l.tenant_id=t AND p.tenant_id=l.tenant_id AND p.id=l.product_id;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
ALTER TABLE crm_approval_rule
 ADD COLUMN max_discount_bps int NOT NULL DEFAULT 3000 CHECK(max_discount_bps BETWEEN 0 AND 10000),
 ADD COLUMN min_margin_bps int NOT NULL DEFAULT 1000 CHECK(min_margin_bps BETWEEN 0 AND 10000);
