ALTER TABLE crm_customer
 ADD COLUMN lifecycle_stage varchar(20) NOT NULL DEFAULT 'PROSPECT' CHECK(lifecycle_stage IN ('PROSPECT','ACTIVE','DORMANT','LOST')),
 ADD COLUMN merged_into_customer_id uuid,
 ADD COLUMN merged_at timestamptz,
 ADD CONSTRAINT customer_merge_state CHECK((merged_into_customer_id IS NULL AND merged_at IS NULL) OR (merged_into_customer_id IS NOT NULL AND merged_at IS NOT NULL)),
 ADD CONSTRAINT customer_not_self_merge CHECK(merged_into_customer_id IS NULL OR merged_into_customer_id<>id),
 ADD CONSTRAINT customer_merge_target FOREIGN KEY(tenant_id,merged_into_customer_id) REFERENCES crm_customer(tenant_id,id);
CREATE INDEX customer_merge_redirect ON crm_customer(tenant_id,merged_into_customer_id) WHERE merged_into_customer_id IS NOT NULL;

-- Retained merge sources stay addressable for historical FKs but no longer consume the active customer quota.
CREATE OR REPLACE FUNCTION crm_usage_counter() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE delta integer; target uuid; BEGIN
 IF TG_TABLE_NAME='crm_customer' THEN
  target=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
  delta=CASE WHEN TG_OP='INSERT' THEN CASE WHEN NEW.merged_into_customer_id IS NULL THEN 1 ELSE 0 END
    WHEN TG_OP='DELETE' THEN CASE WHEN OLD.merged_into_customer_id IS NULL THEN -1 ELSE 0 END
    ELSE (CASE WHEN NEW.merged_into_customer_id IS NULL THEN 1 ELSE 0 END)-(CASE WHEN OLD.merged_into_customer_id IS NULL THEN 1 ELSE 0 END) END;
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
DROP TRIGGER customer_usage ON crm_customer;
CREATE TRIGGER customer_usage AFTER INSERT OR DELETE OR UPDATE OF merged_into_customer_id ON crm_customer FOR EACH ROW EXECUTE FUNCTION crm_usage_counter();

ALTER TABLE crm_contact
 ADD COLUMN decision_role varchar(20) NOT NULL DEFAULT 'OTHER' CHECK(decision_role IN ('DECISION_MAKER','INFLUENCER','USER','GATEKEEPER','OTHER')),
 ADD COLUMN influence varchar(10) NOT NULL DEFAULT 'UNKNOWN' CHECK(influence IN ('HIGH','MEDIUM','LOW','UNKNOWN')),
 ADD COLUMN contact_preference varchar(20) NOT NULL DEFAULT 'ANY' CHECK(contact_preference IN ('PHONE','EMAIL','WECHAT','IN_PERSON','ANY'));

CREATE TABLE crm_customer_lifecycle_history(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,customer_id uuid NOT NULL,
 action varchar(20) NOT NULL CHECK(action IN ('STAGE','MERGE_SOURCE','MERGE_TARGET')),
 from_stage varchar(20),to_stage varchar(20),related_customer_id uuid,
 reason varchar(500) NOT NULL CHECK(length(btrim(reason))>0),actor_member_id uuid NOT NULL,
 resource_version bigint NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,related_customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE INDEX lifecycle_history_page ON crm_customer_lifecycle_history(tenant_id,customer_id,created_at DESC,id);

CREATE TABLE crm_followup(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,kind varchar(10) NOT NULL CHECK(kind IN ('PLAN','RECORD')),
 target_type varchar(12) NOT NULL CHECK(target_type IN ('LEAD','CUSTOMER','OPPORTUNITY')),
 lead_id uuid,customer_id uuid,opportunity_id uuid,
 plan_id uuid,assignee_member_id uuid NOT NULL,actor_member_id uuid NOT NULL,
 due_at timestamptz,occurred_at timestamptz,
 status varchar(12) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','DONE')),
 content varchar(4000) NOT NULL CHECK(length(btrim(content))>0),
 result_code varchar(20) CHECK(result_code IN ('CONNECTED','NO_ANSWER','INTERESTED','NOT_INTERESTED','OTHER')),
 next_action varchar(1000) NOT NULL DEFAULT '',attachment_id uuid,
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,lead_id) REFERENCES crm_lead(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id,opportunity_id) REFERENCES crm_opportunity(tenant_id,customer_id,id),
 FOREIGN KEY(tenant_id,plan_id) REFERENCES crm_followup(tenant_id,id),
 FOREIGN KEY(tenant_id,attachment_id) REFERENCES crm_attachment(tenant_id,id),
 FOREIGN KEY(tenant_id,assignee_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_member_id) REFERENCES sys_tenant_member(tenant_id,id),
 CHECK((target_type='LEAD' AND lead_id IS NOT NULL AND customer_id IS NULL AND opportunity_id IS NULL)
    OR (target_type='CUSTOMER' AND lead_id IS NULL AND customer_id IS NOT NULL AND opportunity_id IS NULL)
    OR (target_type='OPPORTUNITY' AND lead_id IS NULL AND customer_id IS NOT NULL AND opportunity_id IS NOT NULL)),
 CHECK((kind='PLAN' AND due_at IS NOT NULL AND occurred_at IS NULL AND plan_id IS NULL AND result_code IS NULL)
    OR (kind='RECORD' AND due_at IS NULL AND occurred_at IS NOT NULL AND status='DONE' AND result_code IS NOT NULL))
);
CREATE INDEX followup_lead_page ON crm_followup(tenant_id,lead_id,created_at DESC,id) WHERE lead_id IS NOT NULL;
CREATE INDEX followup_customer_page ON crm_followup(tenant_id,customer_id,created_at DESC,id) WHERE customer_id IS NOT NULL;
CREATE INDEX followup_due_page ON crm_followup(tenant_id,assignee_member_id,due_at,id) WHERE kind='PLAN' AND status='PENDING';
CREATE UNIQUE INDEX followup_plan_one_result ON crm_followup(tenant_id,plan_id) WHERE plan_id IS NOT NULL;

DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['crm_customer_lifecycle_history','crm_followup'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',name);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',name);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',name);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON crm_customer_lifecycle_history,crm_followup TO crm_runtime;
