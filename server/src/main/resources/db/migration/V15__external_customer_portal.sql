CREATE TABLE portal_account(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),customer_id uuid NOT NULL,email varchar(254) NOT NULL,email_norm varchar(254) NOT NULL,
 display_name varchar(100) NOT NULL,password_hash varchar(100) NOT NULL,status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','DISABLED')),
 auth_version bigint NOT NULL DEFAULT 0,must_change_password boolean NOT NULL DEFAULT true,created_by_member_id uuid NOT NULL,
 last_login_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,email_norm),FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),CHECK(email_norm=lower(btrim(email)))
);
CREATE INDEX portal_account_customer ON portal_account(tenant_id,customer_id,status);
CREATE TABLE portal_session(
 id uuid PRIMARY KEY,account_id uuid NOT NULL REFERENCES portal_account(id),token_hash char(64) NOT NULL UNIQUE,csrf_token varchar(80) NOT NULL,
 account_auth_version bigint NOT NULL,expires_at timestamptz NOT NULL,revoked_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX portal_session_account ON portal_session(account_id,expires_at);
CREATE TABLE portal_login_limit(
 key_hash char(64) PRIMARY KEY,failures int NOT NULL DEFAULT 0,blocked_until timestamptz,updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE portal_audit(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),customer_id uuid NOT NULL,account_id uuid,
 action varchar(80) NOT NULL,entity_type varchar(40) NOT NULL,entity_id uuid,trace_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),FOREIGN KEY(account_id) REFERENCES portal_account(id)
);
CREATE INDEX portal_audit_scope ON portal_audit(tenant_id,customer_id,created_at DESC,id);
CREATE TABLE portal_idempotency(
 account_id uuid NOT NULL REFERENCES portal_account(id),operation varchar(80) NOT NULL,request_key varchar(100) NOT NULL,request_hash char(64) NOT NULL,
 result_id uuid,created_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(account_id,operation,request_key)
);

ALTER TABLE crm_ticket ADD COLUMN type_code varchar(40) NOT NULL DEFAULT 'GENERAL',ADD COLUMN portal_created boolean NOT NULL DEFAULT false;
ALTER TABLE crm_ticket_comment ALTER COLUMN actor_member_id DROP NOT NULL,ADD COLUMN portal_account_id uuid REFERENCES portal_account(id),
 ADD CONSTRAINT ticket_comment_one_actor CHECK((actor_member_id IS NULL)<>(portal_account_id IS NULL));
ALTER TABLE crm_ticket_revision ALTER COLUMN actor_member_id DROP NOT NULL,ADD COLUMN portal_account_id uuid REFERENCES portal_account(id),
 ADD CONSTRAINT ticket_revision_one_actor CHECK((actor_member_id IS NULL)<>(portal_account_id IS NULL));
ALTER TABLE crm_attachment ALTER COLUMN uploaded_by_member_id DROP NOT NULL,ADD COLUMN uploaded_by_portal_account_id uuid REFERENCES portal_account(id),
 ADD CONSTRAINT attachment_one_uploader CHECK((uploaded_by_member_id IS NULL)<>(uploaded_by_portal_account_id IS NULL));

GRANT SELECT,INSERT,UPDATE ON portal_account,portal_session,portal_login_limit TO crm_runtime;
GRANT SELECT,INSERT ON portal_audit,portal_idempotency TO crm_runtime;
GRANT DELETE ON portal_session TO crm_runtime;
GRANT UPDATE(type_code,portal_created) ON crm_ticket TO crm_runtime;

COMMENT ON TABLE portal_account IS 'External customer identities; service queries must always bind tenant_id and customer_id.';
COMMENT ON TABLE portal_session IS 'Portal authentication control plane; never accepts internal CRM session cookies.';
