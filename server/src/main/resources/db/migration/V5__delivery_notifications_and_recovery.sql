ALTER TABLE sys_role_policy ADD COLUMN report_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN export_can_read boolean NOT NULL DEFAULT false,ADD COLUMN import_can_write boolean NOT NULL DEFAULT false,
 ADD COLUMN attachment_can_read boolean NOT NULL DEFAULT false,ADD COLUMN attachment_can_write boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT attachment_write_requires_read CHECK(NOT attachment_can_write OR attachment_can_read),
 ADD CONSTRAINT viewer_delivery_readonly CHECK(role_code<>'VIEWER' OR (NOT import_can_write AND NOT attachment_can_write));
DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET report_can_read=can_read,export_can_read=can_read AND role_code<>'VIEWER',
   import_can_write=can_write AND role_code='SALES_MANAGER',attachment_can_read=can_read,attachment_can_write=can_write,version=version+1 WHERE tenant_id=t;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
ALTER TABLE sys_plan ADD COLUMN storage_limit_bytes bigint NOT NULL DEFAULT 1073741824 CHECK(storage_limit_bytes BETWEEN 1048576 AND 1099511627776);
ALTER TABLE sys_tenant ADD COLUMN attachment_bytes bigint NOT NULL DEFAULT 0 CHECK(attachment_bytes>=0);
CREATE TABLE crm_import_batch (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,member_id uuid NOT NULL,owner_member_id uuid NOT NULL,
 file_name varchar(200) NOT NULL,rows_data jsonb NOT NULL,content_hash varchar(64) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PREVIEW' CHECK(status IN ('PREVIEW','COMMITTED')),
 selected_rows jsonb,results jsonb,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),committed_at timestamptz,UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX import_member_page ON crm_import_batch(tenant_id,member_id,created_at DESC,id);
CREATE TABLE crm_attachment (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,customer_id uuid NOT NULL,opportunity_id uuid,
 file_name varchar(200) NOT NULL,content_type varchar(100) NOT NULL,size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 5242880),
 sha256 varchar(64) NOT NULL,object_key varchar(300) NOT NULL UNIQUE,
 status varchar(20) NOT NULL DEFAULT 'READY' CHECK(status IN ('READY','DELETED')),
 uploaded_by_member_id uuid NOT NULL,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),deleted_at timestamptz,UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,uploaded_by_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX attachment_customer_page ON crm_attachment(tenant_id,customer_id,created_at DESC,id);
CREATE TABLE sys_notification (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,member_id uuid NOT NULL,task_id uuid NOT NULL,task_version bigint NOT NULL,
 reminder_date date NOT NULL,kind varchar(20) NOT NULL CHECK(kind IN ('DUE','OVERDUE')),read_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,member_id,task_id,task_version,reminder_date,kind),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,task_id) REFERENCES crm_task(tenant_id,id)
);
CREATE TABLE sys_object_cleanup (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),object_key varchar(300) NOT NULL UNIQUE,
 attempts int NOT NULL DEFAULT 0,next_attempt_at timestamptz NOT NULL DEFAULT now(),completed_at timestamptz,error_code varchar(80),created_at timestamptz NOT NULL DEFAULT now()
);
DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['crm_import_batch','crm_attachment','sys_notification','sys_object_cleanup'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',name);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',name);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',name);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_import_batch,crm_attachment,sys_notification,sys_object_cleanup TO crm_runtime;
CREATE FUNCTION attachment_usage() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' AND NEW.status='READY' THEN UPDATE sys_tenant SET attachment_bytes=attachment_bytes+NEW.size_bytes WHERE id=NEW.tenant_id;
 ELSIF TG_OP='UPDATE' AND OLD.status='READY' AND NEW.status='DELETED' THEN UPDATE sys_tenant SET attachment_bytes=attachment_bytes-OLD.size_bytes WHERE id=OLD.tenant_id;
 END IF;RETURN NEW;END $$;
CREATE TRIGGER attachment_counter AFTER INSERT OR UPDATE OF status ON crm_attachment FOR EACH ROW EXECUTE FUNCTION attachment_usage();
CREATE TABLE sys_job_run (
 id uuid PRIMARY KEY,job_name varchar(40) NOT NULL,status varchar(20) NOT NULL CHECK(status IN ('RUNNING','SUCCESS','FAILED')),
 started_at timestamptz NOT NULL DEFAULT now(),finished_at timestamptz,processed_count bigint NOT NULL DEFAULT 0,
 error_code varchar(80),artifact_key varchar(300),artifact_sha256 varchar(64),artifact_bytes bigint,
 UNIQUE(job_name,id)
);
GRANT SELECT,INSERT,UPDATE ON sys_job_run TO crm_runtime;
ALTER TABLE iam_user ADD COLUMN recovery_email varchar(254),ADD COLUMN recovery_email_verified_at timestamptz;
CREATE TABLE auth_reset_limit(key_hash varchar(64) PRIMARY KEY,requests int NOT NULL DEFAULT 0,window_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE auth_password_reset (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES iam_user(id),token_hash varchar(64) NOT NULL UNIQUE,
 auth_version bigint NOT NULL,expires_at timestamptz NOT NULL,used_at timestamptz,created_at timestamptz NOT NULL DEFAULT now()
);
GRANT SELECT,INSERT,UPDATE,DELETE ON auth_reset_limit,auth_password_reset TO crm_runtime;
