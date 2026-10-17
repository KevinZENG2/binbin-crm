ALTER TABLE crm_lead
 ADD COLUMN in_pool boolean NOT NULL DEFAULT false,
 ADD COLUMN pool_entered_at timestamptz,
 ADD COLUMN last_assigned_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE crm_lead ADD CONSTRAINT lead_pool_state_valid CHECK ((in_pool AND pool_entered_at IS NOT NULL AND status NOT IN ('CONVERTED','UNQUALIFIED')) OR (NOT in_pool AND pool_entered_at IS NULL));
CREATE INDEX lead_pool_page ON crm_lead(tenant_id,pool_entered_at DESC,id) WHERE in_pool;

ALTER TABLE crm_customer
 ADD COLUMN in_pool boolean NOT NULL DEFAULT false,
 ADD COLUMN pool_entered_at timestamptz,
 ADD COLUMN last_assigned_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE crm_customer ADD CONSTRAINT customer_pool_state_valid CHECK (in_pool=(pool_entered_at IS NOT NULL));
CREATE INDEX customer_pool_page ON crm_customer(tenant_id,pool_entered_at DESC,id) WHERE in_pool;

CREATE TABLE crm_resource_movement (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
 resource_type varchar(20) NOT NULL CHECK(resource_type IN ('LEAD','CUSTOMER')),
 resource_id uuid NOT NULL,
 action varchar(24) NOT NULL CHECK(action IN ('CLAIM','RETURN','ASSIGN','BATCH_ASSIGN','AUTO_RECYCLE','OFFBOARD_TRANSFER','DIRECT_TRANSFER')),
 from_member_id uuid,
 to_member_id uuid,
 actor_member_id uuid,
 reason varchar(300) NOT NULL DEFAULT '',
 resource_version bigint NOT NULL CHECK(resource_version>=0),
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,from_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,to_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX resource_movement_history ON crm_resource_movement(tenant_id,resource_type,resource_id,created_at DESC,id);
ALTER TABLE crm_resource_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_resource_movement FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_resource_movement USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT ON crm_resource_movement TO crm_runtime;
