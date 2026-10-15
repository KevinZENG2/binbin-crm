CREATE TABLE iam_user (
  id uuid PRIMARY KEY,
  username varchar(128) NOT NULL UNIQUE,
  display_name varchar(100) NOT NULL,
  password_hash varchar(100) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  auth_version bigint NOT NULL DEFAULT 0,
  is_platform_operator boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sys_tenant (
  id uuid PRIMARY KEY,
  code varchar(40) NOT NULL UNIQUE,
  name varchar(200) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
  subscription_end timestamptz NOT NULL,
  timezone varchar(80) NOT NULL DEFAULT 'Asia/Shanghai',
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sys_tenant_member (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
  user_id uuid NOT NULL REFERENCES iam_user(id),
  display_name varchar(100) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id),
  UNIQUE (tenant_id,user_id)
);
CREATE TABLE sys_member_role (
  tenant_id uuid NOT NULL,
  member_id uuid NOT NULL,
  role_code varchar(40) NOT NULL CHECK (role_code IN ('TENANT_ADMIN','SALES_MANAGER','SALES','VIEWER')),
  PRIMARY KEY (tenant_id,member_id,role_code),
  FOREIGN KEY (tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE auth_session (
  id uuid PRIMARY KEY,
  token_hash char(64) NOT NULL UNIQUE,
  csrf_token varchar(80) NOT NULL,
  user_id uuid NOT NULL REFERENCES iam_user(id),
  user_auth_version bigint NOT NULL,
  active_tenant_id uuid REFERENCES sys_tenant(id),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX auth_session_user_idx ON auth_session(user_id,expires_at);
CREATE TABLE auth_login_limit (
  key_hash char(64) PRIMARY KEY,
  failures integer NOT NULL DEFAULT 0,
  blocked_until timestamptz,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE iam_audit (
  id uuid PRIMARY KEY,
  user_id uuid,
  action varchar(60) NOT NULL,
  target_id uuid,
  trace_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE crm_customer (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
  customer_name varchar(200) NOT NULL CHECK (length(trim(customer_name))>0),
  industry varchar(80) NOT NULL,
  level_code varchar(1) NOT NULL CHECK (level_code IN ('A','B','C')),
  source_code varchar(80) NOT NULL,
  region varchar(100) NOT NULL DEFAULT '',
  credit_code varchar(18),
  owner_member_id uuid NOT NULL,
  description text NOT NULL DEFAULT '',
  version bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (tenant_id,id),
  FOREIGN KEY (tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),
  CHECK (credit_code IS NULL OR credit_code ~ '^[0-9A-Z]{18}$')
);
CREATE UNIQUE INDEX crm_customer_credit_uk ON crm_customer(tenant_id,credit_code) WHERE credit_code IS NOT NULL;
CREATE INDEX crm_customer_owner_idx ON crm_customer(tenant_id,owner_member_id,updated_at DESC,id);
CREATE TABLE crm_customer_share (
  tenant_id uuid NOT NULL,
  customer_id uuid NOT NULL,
  member_id uuid NOT NULL,
  can_write boolean NOT NULL DEFAULT false,
  expires_at timestamptz,
  PRIMARY KEY (tenant_id,customer_id,member_id),
  FOREIGN KEY (tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
  FOREIGN KEY (tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE sys_idempotency_request (
  tenant_id uuid NOT NULL,
  member_id uuid NOT NULL,
  operation varchar(80) NOT NULL,
  request_key varchar(100) NOT NULL,
  request_hash char(64) NOT NULL,
  result_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id,member_id,operation,request_key),
  FOREIGN KEY (tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE sys_audit_log (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES sys_tenant(id),
  actor_member_id uuid NOT NULL,
  action varchar(80) NOT NULL,
  entity_type varchar(80) NOT NULL,
  entity_id uuid,
  business_version bigint,
  trace_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);

-- Identity/control-plane discovery is scoped by authenticated user in parameterized service queries.
-- Business tables additionally fail closed when the transaction has no trusted tenant context.
ALTER TABLE crm_customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_customer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_customer
  USING (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE crm_customer_share ENABLE ROW LEVEL SECURITY;
ALTER TABLE crm_customer_share FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON crm_customer_share
  USING (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE sys_idempotency_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_idempotency_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON sys_idempotency_request
  USING (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE sys_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON sys_audit_log
  USING (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = nullif(current_setting('crm.tenant_id',true),'')::uuid);

GRANT SELECT ON iam_user,sys_tenant,sys_tenant_member,sys_member_role TO crm_runtime;
GRANT SELECT,INSERT,UPDATE,DELETE ON auth_session,auth_login_limit TO crm_runtime;
GRANT SELECT,INSERT ON iam_audit TO crm_runtime;
GRANT SELECT,INSERT,UPDATE ON crm_customer,crm_customer_share,sys_idempotency_request TO crm_runtime;
GRANT SELECT,INSERT ON sys_audit_log TO crm_runtime;
