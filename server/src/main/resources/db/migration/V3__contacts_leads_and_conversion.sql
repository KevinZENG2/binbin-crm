-- Preserve customer policies and explicitly initialize the two new module switches.
ALTER TABLE sys_role_policy
 ADD COLUMN contact_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN contact_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN lead_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN lead_can_write boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT contact_policy_write_requires_read CHECK(NOT contact_can_write OR contact_can_read),
 ADD CONSTRAINT lead_policy_write_requires_read CHECK(NOT lead_can_write OR lead_can_read),
 ADD CONSTRAINT viewer_business_readonly CHECK(role_code<>'VIEWER' OR (NOT contact_can_write AND NOT lead_can_write));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET contact_can_read=can_read,contact_can_write=can_write,
    lead_can_read=can_read,lead_can_write=can_write,version=version+1 WHERE tenant_id=t;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;

CREATE TABLE crm_contact (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),customer_id uuid NOT NULL,
 full_name varchar(100) NOT NULL CHECK(length(trim(full_name))>0),job_title varchar(100) NOT NULL DEFAULT '',
 phone varchar(40) NOT NULL DEFAULT '',email varchar(254) NOT NULL DEFAULT '',
 is_primary boolean NOT NULL DEFAULT false,description text NOT NULL DEFAULT '',
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id)
);
CREATE UNIQUE INDEX one_primary_contact_per_customer ON crm_contact(tenant_id,customer_id) WHERE is_primary;
CREATE INDEX contact_customer_page_idx ON crm_contact(tenant_id,customer_id,updated_at DESC,id);

CREATE TABLE crm_lead (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
 contact_name varchar(100) NOT NULL CHECK(length(trim(contact_name))>0),company_name varchar(200) NOT NULL CHECK(length(trim(company_name))>0),
 industry varchar(80) NOT NULL,source_code varchar(80) NOT NULL,region varchar(100) NOT NULL DEFAULT '',
 job_title varchar(100) NOT NULL DEFAULT '',phone varchar(40) NOT NULL DEFAULT '',email varchar(254) NOT NULL DEFAULT '',description text NOT NULL DEFAULT '',
 status varchar(20) NOT NULL DEFAULT 'NEW' CHECK(status IN ('NEW','CONTACTED','QUALIFIED','UNQUALIFIED','CONVERTED')),
 owner_member_id uuid NOT NULL,version bigint NOT NULL DEFAULT 0,
 converted_customer_id uuid,converted_contact_id uuid,converted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,converted_customer_id,converted_contact_id) REFERENCES crm_contact(tenant_id,customer_id,id),
 CHECK((status='CONVERTED' AND converted_customer_id IS NOT NULL AND converted_contact_id IS NOT NULL AND converted_at IS NOT NULL)
    OR (status<>'CONVERTED' AND converted_customer_id IS NULL AND converted_contact_id IS NULL AND converted_at IS NULL))
);
CREATE INDEX lead_status_page_idx ON crm_lead(tenant_id,status,updated_at DESC,id);
CREATE INDEX lead_owner_page_idx ON crm_lead(tenant_id,owner_member_id,updated_at DESC,id);
ALTER TABLE crm_contact ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_contact USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE crm_lead ENABLE ROW LEVEL SECURITY;ALTER TABLE crm_lead FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_lead USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON crm_contact,crm_lead TO crm_runtime;
