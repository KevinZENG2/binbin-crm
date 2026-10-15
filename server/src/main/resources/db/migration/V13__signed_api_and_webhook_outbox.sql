DO $$DECLARE t uuid;BEGIN FOR t IN SELECT id FROM sys_tenant LOOP PERFORM set_config('crm.tenant_id',t::text,true);UPDATE sys_role_policy SET integration_can_read=role_code='TENANT_ADMIN',integration_can_manage=role_code='TENANT_ADMIN',version=version+1 WHERE tenant_id=t;END LOOP;PERFORM set_config('crm.tenant_id','',true);END$$;
CREATE TABLE crm_integration_key(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,name varchar(100) NOT NULL,token_hash char(64) NOT NULL,scopes jsonb NOT NULL,
 owner_member_id uuid NOT NULL,owner_auth_version bigint NOT NULL,created_by_member_id uuid NOT NULL,expires_at timestamptz NOT NULL,revoked_at timestamptz,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id));
CREATE TABLE crm_integration_nonce(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,key_id uuid NOT NULL,nonce varchar(64) NOT NULL,request_hash char(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,key_id,nonce),FOREIGN KEY(tenant_id,key_id) REFERENCES crm_integration_key(tenant_id,id));
CREATE INDEX integration_nonce_window ON crm_integration_nonce(tenant_id,key_id,created_at);
CREATE TABLE crm_webhook_subscription(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,name varchar(100) NOT NULL,url varchar(2048) NOT NULL,events jsonb NOT NULL,encrypted_secret text NOT NULL,
 owner_member_id uuid NOT NULL,owner_auth_version bigint NOT NULL,created_by_member_id uuid NOT NULL,active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,owner_member_id) REFERENCES sys_tenant_member(tenant_id,id),FOREIGN KEY(tenant_id,created_by_member_id) REFERENCES sys_tenant_member(tenant_id,id));
CREATE TABLE crm_webhook_outbox(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,subscription_id uuid NOT NULL,event_type varchar(50) NOT NULL,entity_id uuid NOT NULL,business_version bigint NOT NULL,payload jsonb NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN('PENDING','RETRY','DELIVERED','DEAD','BLOCKED')),attempts int NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 8),next_attempt_at timestamptz NOT NULL DEFAULT now(),last_error_code varchar(60),delivered_at timestamptz,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,subscription_id,event_type,entity_id,business_version),FOREIGN KEY(tenant_id,subscription_id) REFERENCES crm_webhook_subscription(tenant_id,id));
CREATE INDEX webhook_pending ON crm_webhook_outbox(tenant_id,status,next_attempt_at);
CREATE TABLE crm_webhook_attempt(id uuid PRIMARY KEY,tenant_id uuid NOT NULL,outbox_id uuid NOT NULL,attempt_no int NOT NULL,outcome varchar(20) NOT NULL CHECK(outcome IN('DELIVERED','RETRY','DEAD','BLOCKED')),http_status int,error_code varchar(60),created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,outbox_id,attempt_no),FOREIGN KEY(tenant_id,outbox_id) REFERENCES crm_webhook_outbox(tenant_id,id));
DO $$DECLARE n text;BEGIN FOREACH n IN ARRAY ARRAY['crm_integration_key','crm_integration_nonce','crm_webhook_subscription','crm_webhook_outbox','crm_webhook_attempt'] LOOP EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);END LOOP;END$$;
GRANT SELECT,INSERT,UPDATE ON crm_integration_key,crm_webhook_subscription,crm_webhook_outbox TO crm_runtime;
GRANT SELECT,INSERT ON crm_integration_nonce,crm_webhook_attempt TO crm_runtime;
CREATE FUNCTION crm_enqueue_webhook() RETURNS trigger LANGUAGE plpgsql AS $$DECLARE ev text;BEGIN
 IF TG_OP='UPDATE' AND NEW.version=OLD.version THEN RETURN NEW;END IF;
 ev=TG_ARGV[0]||CASE WHEN TG_OP='INSERT' THEN '.created' ELSE '.updated' END;
 INSERT INTO crm_webhook_outbox(id,tenant_id,subscription_id,event_type,entity_id,business_version,payload)
 SELECT gen_random_uuid(),NEW.tenant_id,s.id,ev,NEW.id,NEW.version,jsonb_build_object('event',ev,'tenantId',NEW.tenant_id,'entityId',NEW.id,'businessVersion',NEW.version,'occurredAt',to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
 FROM crm_webhook_subscription s WHERE s.tenant_id=NEW.tenant_id AND s.active AND s.events ? ev ON CONFLICT DO NOTHING;
 RETURN NEW;END$$;
CREATE TRIGGER customer_webhook AFTER INSERT OR UPDATE ON crm_customer FOR EACH ROW EXECUTE FUNCTION crm_enqueue_webhook('customer');
CREATE TRIGGER lead_webhook AFTER INSERT OR UPDATE ON crm_lead FOR EACH ROW EXECUTE FUNCTION crm_enqueue_webhook('lead');
CREATE TRIGGER opportunity_webhook AFTER INSERT OR UPDATE ON crm_opportunity FOR EACH ROW EXECUTE FUNCTION crm_enqueue_webhook('opportunity');
CREATE TRIGGER ticket_webhook AFTER INSERT OR UPDATE ON crm_ticket FOR EACH ROW EXECUTE FUNCTION crm_enqueue_webhook('ticket');
CREATE TRIGGER receipt_webhook AFTER INSERT OR UPDATE ON crm_receipt FOR EACH ROW EXECUTE FUNCTION crm_enqueue_webhook('receipt');
CREATE TRIGGER campaign_webhook AFTER INSERT OR UPDATE ON crm_campaign FOR EACH ROW EXECUTE FUNCTION crm_enqueue_webhook('campaign');
