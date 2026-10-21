ALTER TABLE crm_ai_run DROP CONSTRAINT crm_ai_run_task_type_check;
ALTER TABLE crm_ai_run ADD CONSTRAINT crm_ai_run_task_type_check CHECK(task_type IN('CUSTOMER_SUMMARY','OPPORTUNITY_COACH','TICKET_REPLY','CAMPAIGN_ADVICE','LEAD_ASSESSMENT','VISIT_PREP','FOLLOWUP_DRAFT','NEW_LEAD','TICKET_TRIAGE','WEEKLY_SUMMARY','ANOMALY_EXPLAIN','APPROVAL_ASSIST'));
ALTER TABLE crm_ai_run DROP CONSTRAINT crm_ai_run_entity_type_check;
ALTER TABLE crm_ai_run ADD CONSTRAINT crm_ai_run_entity_type_check CHECK(entity_type IN('CUSTOMER','OPPORTUNITY','TICKET','CAMPAIGN','LEAD','WEEKLY','QUOTE','WORKSPACE'));
ALTER TABLE crm_ai_run ALTER COLUMN result_text TYPE varchar(24000);

CREATE TABLE sys_ai_prompt_version(
 id uuid PRIMARY KEY,task_type varchar(32) NOT NULL,title varchar(100) NOT NULL,instruction varchar(4000) NOT NULL,
 created_by_user_id uuid REFERENCES iam_user(id),created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sys_ai_prompt_release(
 task_type varchar(32) PRIMARY KEY,prompt_id uuid NOT NULL REFERENCES sys_ai_prompt_version(id),
 previous_prompt_id uuid REFERENCES sys_ai_prompt_version(id),enabled boolean NOT NULL DEFAULT false,
 rollout_percent int NOT NULL DEFAULT 0 CHECK(rollout_percent BETWEEN 0 AND 100),
 version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO sys_ai_prompt_version(id,task_type,title,instruction) VALUES
 ('80000000-0000-4000-8000-000000000001','CUSTOMER_SUMMARY','客户经营摘要 v1','总结客户现状、机会、风险与下一步动作。'),
 ('80000000-0000-4000-8000-000000000002','OPPORTUNITY_COACH','商机推进建议 v1','分析商机健康度、阻碍和三个具体推进动作。'),
 ('80000000-0000-4000-8000-000000000003','TICKET_REPLY','工单回复草稿 v1','起草专业克制的公开回复，不承诺未知处理时间、赔偿或结果。'),
 ('80000000-0000-4000-8000-000000000004','CAMPAIGN_ADVICE','营销活动建议 v1','根据活动目标、预算与现状建议执行方案和衡量指标；不生成客户名单，不擅自外发。'),
 ('80000000-0000-4000-8000-000000000005','LEAD_ASSESSMENT','线索研判 v1','根据现有线索事实研判匹配度、信息缺口与跟进优先级；缺失信息标记待确认。'),
 ('80000000-0000-4000-8000-000000000006','VISIT_PREP','拜访准备 v1','准备拜访目标、提问清单与核实事项；不得虚构客户需求或拜访记录。'),
 ('80000000-0000-4000-8000-000000000007','FOLLOWUP_DRAFT','跟进记录草稿 v1','仅根据用户提供的已发生互动起草跟进记录；未知时间、结果必须待人工确认；不要虚构沟通经过。'),
 ('80000000-0000-4000-8000-000000000008','NEW_LEAD','新建线索草稿 v1','提取用户明确给出的线索姓名、公司、行业、来源和地区。未知必填项留待人工补全。仅草稿，不声称已创建。不要推测联系方式。'),
 ('80000000-0000-4000-8000-000000000009','TICKET_TRIAGE','客诉与工单分诊 v1','建议工单分类、优先级、依据及待核实信息；不自动变更 SLA、状态或分派。'),
 ('80000000-0000-4000-8000-000000000010','WEEKLY_SUMMARY','销售周报辅助 v1','从周报素材提炼进展、成果、风险和下周计划，区分记录事实和建议，不增加未经核实的业绩。'),
 ('80000000-0000-4000-8000-000000000011','ANOMALY_EXPLAIN','经营异常解释 v1','根据授权范围的汇总指标解释可能异常，明确样本范围及因果关系未证实，不推测个人绩效。'),
 ('80000000-0000-4000-8000-000000000012','APPROVAL_ASSIST','报价审批辅助 v1','整理报价审批材料、金额与有效期风险，列出需主管核查事项；不得代替人工批准、驳回或财务决策。');
INSERT INTO sys_ai_prompt_release(task_type,prompt_id,enabled,rollout_percent)
 SELECT task_type,id,task_type IN('CUSTOMER_SUMMARY','OPPORTUNITY_COACH','TICKET_REPLY'),
 CASE WHEN task_type IN('CUSTOMER_SUMMARY','OPPORTUNITY_COACH','TICKET_REPLY') THEN 100 ELSE 0 END FROM sys_ai_prompt_version;

CREATE TABLE crm_ai_quality_policy(
 tenant_id uuid PRIMARY KEY REFERENCES sys_tenant(id),sensitive_terms jsonb NOT NULL DEFAULT '[]',
 strict_facts boolean NOT NULL DEFAULT true,version bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK(jsonb_typeof(sensitive_terms)='array' AND jsonb_array_length(sensitive_terms)<=20)
);
ALTER TABLE crm_ai_run ADD COLUMN prompt_version_id uuid REFERENCES sys_ai_prompt_version(id),
 ADD COLUMN release_version bigint,ADD COLUMN quality_policy_version bigint,ADD COLUMN source_hash char(64),
 ADD COLUMN quality_status varchar(20) NOT NULL DEFAULT 'NOT_EVALUATED' CHECK(quality_status IN('NOT_EVALUATED','PASS','REVIEW','BLOCKED')),
 ADD COLUMN quality_findings jsonb NOT NULL DEFAULT '[]',
 ADD COLUMN review_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(review_status IN('PENDING','ACCEPTED','REJECTED')),
 ADD COLUMN review_version bigint NOT NULL DEFAULT 0;

CREATE TABLE crm_ai_evaluation(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,run_id uuid NOT NULL,reviewer_member_id uuid NOT NULL,
 decision varchar(16) NOT NULL CHECK(decision IN('ACCEPTED','REJECTED')),
 factual_score int NOT NULL CHECK(factual_score BETWEEN 1 AND 5),usefulness_score int NOT NULL CHECK(usefulness_score BETWEEN 1 AND 5),
 notes varchar(1000) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(tenant_id,run_id) REFERENCES crm_ai_run(tenant_id,id),
 FOREIGN KEY(tenant_id,reviewer_member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
CREATE TABLE crm_ai_proposal(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,run_id uuid NOT NULL,member_id uuid NOT NULL,
 action varchar(24) NOT NULL CHECK(action IN('CREATE_LEAD','CREATE_FOLLOWUP')),payload jsonb NOT NULL,
 token_hash char(64) NOT NULL,source_hash char(64) NOT NULL,expires_at timestamptz NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN('DRAFT','APPLIED','CANCELLED')),
 result_id uuid,version bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT now(),applied_at timestamptz,
 UNIQUE(tenant_id,id),UNIQUE(tenant_id,run_id,member_id),
 FOREIGN KEY(tenant_id,run_id) REFERENCES crm_ai_run(tenant_id,id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES sys_tenant_member(tenant_id,id)
);
DO $$ DECLARE n text; BEGIN
 FOREACH n IN ARRAY ARRAY['crm_ai_quality_policy','crm_ai_evaluation','crm_ai_proposal'] LOOP
 EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',n);
 EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',n);
 EXECUTE format('CREATE POLICY tenant_boundary ON %I USING(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=nullif(current_setting(''crm.tenant_id'',true),'''')::uuid)',n);
 END LOOP;
END $$;
GRANT SELECT,INSERT ON sys_ai_prompt_version,crm_ai_evaluation TO crm_runtime;
GRANT SELECT,INSERT,UPDATE ON sys_ai_prompt_release,crm_ai_quality_policy,crm_ai_proposal TO crm_runtime;
COMMENT ON TABLE crm_ai_proposal IS 'Editable human-confirmed allowlisted actions. One applied result per run and member. No generic AI tools.';
