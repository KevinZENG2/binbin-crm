ALTER TABLE sys_role_policy
 ADD COLUMN configuration_can_read boolean NOT NULL DEFAULT false,
 ADD COLUMN configuration_can_manage boolean NOT NULL DEFAULT false,
 ADD COLUMN portal_account_can_manage boolean NOT NULL DEFAULT false,
 ADD CONSTRAINT configuration_manage_requires_read CHECK(NOT configuration_can_manage OR configuration_can_read),
 ADD CONSTRAINT configuration_admin_only CHECK(NOT configuration_can_manage OR role_code='TENANT_ADMIN'),
 ADD CONSTRAINT portal_account_admin_only CHECK(NOT portal_account_can_manage OR role_code='TENANT_ADMIN');

DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  UPDATE sys_role_policy SET configuration_can_read=role_code='TENANT_ADMIN',
   configuration_can_manage=role_code='TENANT_ADMIN',portal_account_can_manage=role_code='TENANT_ADMIN',version=version+1
   WHERE tenant_id=t;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;
CREATE TABLE sys_industry_template(
 code varchar(40) PRIMARY KEY,name varchar(100) NOT NULL,description varchar(500) NOT NULL,
 definition jsonb NOT NULL,version bigint NOT NULL DEFAULT 1,active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(code~'^[A-Z][A-Z0-9_]{1,39}$'),CHECK(jsonb_typeof(definition)='object')
);
INSERT INTO sys_industry_template(code,name,description,definition) VALUES
 ('GENERAL','通用客户经营','适合服务、咨询和一般企业客户管理','{"fields":[],"ticketTypes":[["GENERAL","一般咨询"],["SERVICE","服务请求"]]}'::jsonb),
 ('MANUFACTURING','制造业','增加设备、物料、项目和售后场景字段','{"fields":[["CUSTOMER","factory_code","工厂代码","TEXT","基本信息"],["CUSTOMER","main_products","主营产品","TEXTAREA","业务信息"],["OPPORTUNITY","project_model","项目型号","TEXT","项目资料"],["TICKET","equipment_model","设备型号","TEXT","服务资料"],["TICKET","serial_number","设备序列号","TEXT","服务资料"]],"ticketTypes":[["QUALITY","质量问题"],["DELIVERY","交付问题"],["EQUIPMENT","设备服务"],["TECHNICAL","技术支持"]]}'::jsonb),
 ('TRADE','贸易与分销','增加渠道、账期和进出口业务字段','{"fields":[["CUSTOMER","channel_level","渠道等级","SELECT","业务信息"],["CUSTOMER","payment_terms","账期说明","TEXT","财务资料"],["OPPORTUNITY","destination","目的地","TEXT","业务信息"]],"ticketTypes":[["ORDER","订单咨询"],["DELIVERY","物流交付"],["AFTER_SALES","售后服务"]]}'::jsonb),
 ('SOFTWARE','软件与技术服务','增加系统、版本、实施和技术支持字段','{"fields":[["CUSTOMER","system_environment","系统环境","TEXTAREA","技术资料"],["OPPORTUNITY","deployment_mode","部署方式","SELECT","项目资料"],["TICKET","product_version","产品版本","TEXT","服务资料"],["TICKET","environment","运行环境","TEXTAREA","服务资料"]],"ticketTypes":[["BUG","缺陷"],["REQUIREMENT","需求"],["IMPLEMENTATION","实施支持"],["TECHNICAL","技术咨询"]]}'::jsonb);

CREATE TABLE sys_tenant_profile(
 tenant_id uuid PRIMARY KEY REFERENCES sys_tenant(id),industry_template_code varchar(40) NOT NULL DEFAULT 'GENERAL' REFERENCES sys_industry_template(code),
 brand_name varchar(100) NOT NULL DEFAULT '',support_email varchar(254) NOT NULL DEFAULT '',portal_enabled boolean NOT NULL DEFAULT false,
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE crm_custom_field(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),entity_type varchar(20) NOT NULL CHECK(entity_type IN('CUSTOMER','CONTACT','LEAD','OPPORTUNITY','TICKET')),
 field_key varchar(40) NOT NULL CHECK(field_key~'^[a-z][a-z0-9_]{1,39}$'),label varchar(80) NOT NULL CHECK(length(btrim(label))>0),
 data_type varchar(20) NOT NULL CHECK(data_type IN('TEXT','TEXTAREA','NUMBER','DATE','BOOLEAN','SELECT','MULTI_SELECT')),
 required boolean NOT NULL DEFAULT false,section_name varchar(80) NOT NULL DEFAULT '扩展信息',position int NOT NULL DEFAULT 100 CHECK(position BETWEEN 0 AND 10000),
 options jsonb NOT NULL DEFAULT '[]'::jsonb,read_roles text[] NOT NULL DEFAULT ARRAY['TENANT_ADMIN','SALES_MANAGER','SALES','VIEWER']::text[],
 write_roles text[] NOT NULL DEFAULT ARRAY['TENANT_ADMIN','SALES_MANAGER','SALES']::text[],status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','ARCHIVED')),
 version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,entity_type,field_key),CHECK(jsonb_typeof(options)='array')
);
CREATE TABLE crm_custom_value(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,field_id uuid NOT NULL,entity_type varchar(20) NOT NULL,record_id uuid NOT NULL,value jsonb NOT NULL,
 updated_by_member_id uuid NOT NULL,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,field_id,record_id),FOREIGN KEY(tenant_id,field_id) REFERENCES crm_custom_field(tenant_id,id),
 FOREIGN KEY(tenant_id,updated_by_member_id) REFERENCES sys_tenant_member(tenant_id,id),CHECK(jsonb_typeof(value) IN('string','number','boolean','array'))
);
CREATE TABLE crm_tag(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),name varchar(40) NOT NULL CHECK(length(btrim(name))>0),color varchar(7) NOT NULL DEFAULT '#4F8F6B' CHECK(color~'^#[0-9A-Fa-f]{6}$'),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN('ACTIVE','ARCHIVED')),version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,name)
);
CREATE TABLE crm_customer_tag(
 tenant_id uuid NOT NULL,customer_id uuid NOT NULL,tag_id uuid NOT NULL,assigned_by_member_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,customer_id,tag_id),FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id),
 FOREIGN KEY(tenant_id,tag_id) REFERENCES crm_tag(tenant_id,id),FOREIGN KEY(tenant_id,assigned_by_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_process_stage(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),process_type varchar(20) NOT NULL CHECK(process_type IN('OPPORTUNITY','TICKET')),
 stage_code varchar(30) NOT NULL,label varchar(80) NOT NULL,position int NOT NULL CHECK(position BETWEEN 0 AND 1000),probability int NOT NULL DEFAULT 0 CHECK(probability BETWEEN 0 AND 100),
 active boolean NOT NULL DEFAULT true,terminal boolean NOT NULL DEFAULT false,version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,process_type,stage_code)
);
CREATE TABLE crm_number_rule(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),document_type varchar(20) NOT NULL CHECK(document_type IN('QUOTE','CONTRACT','RECEIPT','REVERSAL','TICKET')),
 prefix varchar(10) NOT NULL CHECK(prefix~'^[A-Z0-9]{1,10}$'),include_year boolean NOT NULL DEFAULT true,padding int NOT NULL DEFAULT 6 CHECK(padding BETWEEN 3 AND 9),
 version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,document_type)
);
CREATE TABLE crm_approval_rule(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),process_type varchar(20) NOT NULL CHECK(process_type='QUOTE'),
 name varchar(100) NOT NULL,minimum_amount_cents bigint NOT NULL DEFAULT 0 CHECK(minimum_amount_cents BETWEEN 0 AND 100000000000),
 approver_role varchar(40) NOT NULL DEFAULT 'SALES_MANAGER' CHECK(approver_role='SALES_MANAGER'),self_approval boolean NOT NULL DEFAULT false CHECK(NOT self_approval),
 active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,process_type)
);
CREATE TABLE crm_dictionary_item(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES sys_tenant(id),dictionary_type varchar(30) NOT NULL CHECK(dictionary_type IN('TICKET_TYPE','CUSTOMER_INDUSTRY','LEAD_SOURCE')),
 item_code varchar(40) NOT NULL CHECK(item_code~'^[A-Z0-9_]{1,40}$'),label varchar(80) NOT NULL,position int NOT NULL DEFAULT 100 CHECK(position BETWEEN 0 AND 10000),
 active boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,dictionary_type,item_code)
);

DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['sys_tenant_profile','crm_custom_field','crm_custom_value','crm_tag','crm_customer_tag','crm_process_stage','crm_number_rule','crm_approval_rule','crm_dictionary_item'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
  EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT ON sys_industry_template TO crm_runtime;
GRANT SELECT,INSERT,UPDATE ON sys_tenant_profile,crm_custom_field,crm_custom_value,crm_tag,crm_process_stage,crm_number_rule,crm_approval_rule,crm_dictionary_item TO crm_runtime;
GRANT DELETE ON crm_custom_value TO crm_runtime;
GRANT SELECT,INSERT,DELETE ON crm_customer_tag TO crm_runtime;

DO $$ DECLARE t uuid; BEGIN
 FOR t IN SELECT id FROM sys_tenant LOOP
  PERFORM set_config('crm.tenant_id',t::text,true);
  INSERT INTO sys_tenant_profile(tenant_id,brand_name) SELECT id,name FROM sys_tenant WHERE id=t ON CONFLICT DO NOTHING;
  INSERT INTO crm_process_stage(id,tenant_id,process_type,stage_code,label,position,probability,terminal) VALUES
   (gen_random_uuid(),t,'OPPORTUNITY','CONTACT','初步接触',10,10,false),(gen_random_uuid(),t,'OPPORTUNITY','NEEDS','需求确认',20,25,false),
   (gen_random_uuid(),t,'OPPORTUNITY','SOLUTION','方案报价',30,50,false),(gen_random_uuid(),t,'OPPORTUNITY','NEGOTIATION','商务谈判',40,70,false),
   (gen_random_uuid(),t,'OPPORTUNITY','READY','待签约',50,90,false),(gen_random_uuid(),t,'OPPORTUNITY','WON','成交',60,100,true),(gen_random_uuid(),t,'OPPORTUNITY','LOST','丢单',70,0,true),
   (gen_random_uuid(),t,'TICKET','NEW','新建',10,0,false),(gen_random_uuid(),t,'TICKET','IN_PROGRESS','处理中',20,0,false),
   (gen_random_uuid(),t,'TICKET','WAITING_CUSTOMER','等待客户',30,0,false),(gen_random_uuid(),t,'TICKET','RESOLVED','已解决',40,0,false),
   (gen_random_uuid(),t,'TICKET','CLOSED','已关闭',50,0,true),(gen_random_uuid(),t,'TICKET','CANCELLED','已取消',60,0,true) ON CONFLICT DO NOTHING;
  INSERT INTO crm_number_rule(id,tenant_id,document_type,prefix) VALUES
   (gen_random_uuid(),t,'QUOTE','QT'),(gen_random_uuid(),t,'CONTRACT','CT'),(gen_random_uuid(),t,'RECEIPT','RC'),(gen_random_uuid(),t,'REVERSAL','RV'),(gen_random_uuid(),t,'TICKET','CS') ON CONFLICT DO NOTHING;
  INSERT INTO crm_approval_rule(id,tenant_id,process_type,name) VALUES(gen_random_uuid(),t,'QUOTE','报价一级审批') ON CONFLICT DO NOTHING;
  INSERT INTO crm_dictionary_item(id,tenant_id,dictionary_type,item_code,label,position) VALUES
   (gen_random_uuid(),t,'TICKET_TYPE','GENERAL','一般咨询',10),(gen_random_uuid(),t,'TICKET_TYPE','QUALITY','质量问题',20),
   (gen_random_uuid(),t,'TICKET_TYPE','DELIVERY','交付问题',30),(gen_random_uuid(),t,'TICKET_TYPE','TECHNICAL','技术支持',40) ON CONFLICT DO NOTHING;
 END LOOP;
 PERFORM set_config('crm.tenant_id','',true);
END $$;

