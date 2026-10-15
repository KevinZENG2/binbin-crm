CREATE TABLE sys_plan (
 id uuid PRIMARY KEY,code varchar(40) NOT NULL UNIQUE,name varchar(100) NOT NULL,
 monthly_price_cents bigint NOT NULL CHECK(monthly_price_cents BETWEEN 0 AND 100000000),
 seat_limit integer NOT NULL CHECK(seat_limit BETWEEN 1 AND 10000),
 customer_limit integer NOT NULL CHECK(customer_limit BETWEEN 1 AND 10000000),
 active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO sys_plan(id,code,name,monthly_price_cents,seat_limit,customer_limit) VALUES
 ('00000000-0000-4000-8000-000000000501','basic','基础版（测试套餐）',19900,10,1000),
 ('00000000-0000-4000-8000-000000000502','professional','专业版（测试套餐）',59900,50,10000);
ALTER TABLE sys_tenant ADD COLUMN plan_id uuid REFERENCES sys_plan(id),
 ADD COLUMN industry varchar(80) NOT NULL DEFAULT '',ADD COLUMN version bigint NOT NULL DEFAULT 0,
 ADD COLUMN active_member_count integer NOT NULL DEFAULT 0 CHECK(active_member_count>=0),
 ADD COLUMN customer_count integer NOT NULL DEFAULT 0 CHECK(customer_count>=0);
UPDATE sys_tenant SET plan_id='00000000-0000-4000-8000-000000000501';
ALTER TABLE sys_tenant ALTER COLUMN plan_id SET NOT NULL;
ALTER TABLE iam_user ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;

CREATE TABLE org_department (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),name varchar(100) NOT NULL,
 parent_id uuid,active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,parent_id) REFERENCES org_department(tenant_id,id),CHECK(parent_id IS NULL OR parent_id<>id)
);
CREATE UNIQUE INDEX org_department_name_uk ON org_department(tenant_id,coalesce(parent_id,'00000000-0000-0000-0000-000000000000'::uuid),name) WHERE active;
ALTER TABLE sys_tenant_member ADD COLUMN department_id uuid,ADD COLUMN version bigint NOT NULL DEFAULT 0,
 ADD CONSTRAINT member_department_tenant_fk FOREIGN KEY(tenant_id,department_id) REFERENCES org_department(tenant_id,id);

CREATE TABLE sys_role_policy (
 tenant_id uuid NOT NULL REFERENCES sys_tenant(id),role_code varchar(40) NOT NULL,
 can_read boolean NOT NULL,can_write boolean NOT NULL,record_scope varchar(20) NOT NULL CHECK(record_scope IN ('ALL','DEPARTMENT','OWN_SHARED')),
 credit_access varchar(10) NOT NULL CHECK(credit_access IN ('NONE','READ','WRITE')),
 description_access varchar(10) NOT NULL CHECK(description_access IN ('NONE','READ','WRITE')),
 can_share boolean NOT NULL DEFAULT false,version bigint NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,role_code),CHECK(role_code IN ('TENANT_ADMIN','SALES_MANAGER','SALES','VIEWER')),
 CHECK(NOT can_write OR can_read),CHECK(NOT can_share OR can_write),CHECK(role_code<>'VIEWER' OR NOT can_write)
);
INSERT INTO sys_role_policy(tenant_id,role_code,can_read,can_write,record_scope,credit_access,description_access,can_share)
 SELECT id,'TENANT_ADMIN',false,false,'OWN_SHARED','NONE','NONE',false FROM sys_tenant;
INSERT INTO sys_role_policy SELECT id,'SALES_MANAGER',true,true,'ALL','WRITE','WRITE',true,0 FROM sys_tenant;
INSERT INTO sys_role_policy SELECT id,'SALES',true,true,'OWN_SHARED','WRITE','WRITE',true,0 FROM sys_tenant;
INSERT INTO sys_role_policy SELECT id,'VIEWER',true,false,'ALL','NONE','READ',false,0 FROM sys_tenant;
ALTER TABLE sys_member_role ADD CONSTRAINT member_role_policy_fk FOREIGN KEY(tenant_id,role_code) REFERENCES sys_role_policy(tenant_id,role_code);

CREATE TABLE sys_control_request (
 scope_key varchar(64) NOT NULL,user_id uuid NOT NULL REFERENCES iam_user(id),operation varchar(80) NOT NULL,
 request_key varchar(100) NOT NULL,request_hash char(64) NOT NULL,result_id uuid,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(scope_key,user_id,operation,request_key)
);
CREATE TABLE sys_subscription_request (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),plan_id uuid NOT NULL REFERENCES sys_plan(id),
 applicant_member_id uuid NOT NULL,months integer NOT NULL CHECK(months IN (1,3,6,12)),
 amount_cents bigint NOT NULL CHECK(amount_cents>=0),status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','CONFIRMED','REJECTED','CANCELLED')),
 payment_reference varchar(100),decision_reason varchar(300),confirmed_by uuid REFERENCES iam_user(id),
 renewed_from timestamptz,renewed_until timestamptz,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),decided_at timestamptz,
 FOREIGN KEY(tenant_id,applicant_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE UNIQUE INDEX one_pending_renewal_per_tenant ON sys_subscription_request(tenant_id) WHERE status='PENDING';
CREATE TABLE sys_support_grant (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),operator_user_id uuid NOT NULL REFERENCES iam_user(id),
 approver_member_id uuid NOT NULL,reason varchar(300) NOT NULL,expires_at timestamptz NOT NULL,
 allow_credit boolean NOT NULL DEFAULT false,allow_description boolean NOT NULL DEFAULT false,
 revoked_at timestamptz,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,approver_member_id) REFERENCES sys_tenant_member(tenant_id,id),UNIQUE(tenant_id,id)
);
ALTER TABLE auth_session ADD COLUMN support_grant_id uuid REFERENCES sys_support_grant(id);
CREATE TABLE sys_support_access_log (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),grant_id uuid NOT NULL,
 operator_user_id uuid NOT NULL REFERENCES iam_user(id),action varchar(60) NOT NULL,resource_id uuid,
 trace_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,grant_id) REFERENCES sys_support_grant(tenant_id,id)
);
CREATE TABLE sys_platform_audit (
 id uuid PRIMARY KEY,actor_user_id uuid NOT NULL REFERENCES iam_user(id),tenant_id uuid REFERENCES sys_tenant(id),
 action varchar(80) NOT NULL,target_id uuid,details jsonb NOT NULL DEFAULT '{}',trace_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now()
);

DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_tenant SET active_member_count=(SELECT count(*) FROM sys_tenant_member WHERE tenant_id=t AND status='ACTIVE'),
    customer_count=(SELECT count(*) FROM crm_customer WHERE tenant_id=t) WHERE id=t;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
-- Counters are transactional; tenant row locking serializes quota checks with member/customer changes.
CREATE FUNCTION crm_usage_counter() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE delta integer; target uuid; BEGIN
 IF TG_TABLE_NAME='crm_customer' THEN
  delta=CASE WHEN TG_OP='INSERT' THEN 1 ELSE -1 END;target=CASE WHEN TG_OP='INSERT' THEN NEW.tenant_id ELSE OLD.tenant_id END;
  UPDATE public.sys_tenant SET customer_count=customer_count+delta WHERE id=target;
 ELSE
  target=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
  delta=CASE WHEN TG_OP='INSERT' THEN CASE WHEN NEW.status='ACTIVE' THEN 1 ELSE 0 END
    WHEN TG_OP='DELETE' THEN CASE WHEN OLD.status='ACTIVE' THEN -1 ELSE 0 END
    ELSE (CASE WHEN NEW.status='ACTIVE' THEN 1 ELSE 0 END)-(CASE WHEN OLD.status='ACTIVE' THEN 1 ELSE 0 END) END;
  UPDATE public.sys_tenant SET active_member_count=active_member_count+delta WHERE id=target;
 END IF;
 RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION crm_usage_counter() FROM PUBLIC;
CREATE TRIGGER customer_usage AFTER INSERT OR DELETE ON crm_customer FOR EACH ROW EXECUTE FUNCTION crm_usage_counter();
CREATE TRIGGER member_usage AFTER INSERT OR DELETE OR UPDATE OF status ON sys_tenant_member FOR EACH ROW EXECUTE FUNCTION crm_usage_counter();

ALTER TABLE org_department ENABLE ROW LEVEL SECURITY;ALTER TABLE org_department FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON org_department USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE sys_role_policy ENABLE ROW LEVEL SECURITY;ALTER TABLE sys_role_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON sys_role_policy USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
ALTER TABLE sys_support_access_log ENABLE ROW LEVEL SECURITY;ALTER TABLE sys_support_access_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_boundary ON sys_support_access_log USING(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid) WITH CHECK(tenant_id=nullif(current_setting('crm.tenant_id',true),'')::uuid);
GRANT SELECT,INSERT,UPDATE ON iam_user,sys_tenant,sys_tenant_member,sys_member_role,sys_plan,org_department,sys_role_policy,sys_control_request,sys_subscription_request,sys_support_grant TO crm_runtime;
GRANT DELETE ON sys_member_role,crm_customer_share TO crm_runtime;
GRANT SELECT,INSERT ON sys_platform_audit,sys_support_access_log TO crm_runtime;
