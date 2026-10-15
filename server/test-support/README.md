# CRM 原生云验收 · P2-B v0.8

完整写入验收只针对新建的一次性原生 PostgreSQL 18.6 crm_acceptance，无持久卷、公开域名或 TCP 代理。用户已要求继续开发和云端验证；原预览业务库不作为写入验收目标。

## 执行范围

assemble_native_suite.py 组合 P0～P2-A 的隔离脚本与 checkpoint，统一 SQL import，并包装逐阶段作用域与结果输出。写入前必须核对数据库名、私网主机、同主机 API 8080 端口、初始三企业 / 六账号。CRM_P1A / P1B / P1C / P1D / P2A_WRITE_SCOPE 均为 isolated-new-database。所有密码从私有环境变量读取，不存入源码、结果或日志。

生成命令：python server/test-support/assemble_native_suite.py /ABSOLUTE_SCRATCH_PATH/native-suite.ts 。云端以 gzip 分段环境变量携带脚本，加载前验证 SHA-256。必须等待全新 Java / PostgreSQL 容器启动且种子初始化完成后再运行。Function 部署 SUCCESS 不代表脚本断言通过，必须检查 CRM_RESULT 和最终 CRM_SUITE_DONE。

- verify_api.ts：P0 45 项回归，含安全会话、CSRF、权限和客户协议。
- verify_p1a.ts：P1-A 61 项回归，组织、套餐、额度、续费、共享、支持、改密及会话。
- verify_p1b.ts：P1-B 87 项回归，联系人和线索转换、关联范围、原子性与故障回滚。
- verify_p1c.ts：P1-C 123 项回归，商机状态 / 金额 / 历史、跟进、待办、并发、来源、到期 / 暂停和权限。
- verify_p1d.ts：新交付模块验收，涵盖报表、CSV、S3 私有文件、权限继承、容量及套餐降级、提醒、假 SMTP 找回、会话撤销和故障补偿。数量以最终结果为准。
- collect_p1d_evidence.ts：11 项只读 checkpoint，17 表 RLS、五次迁移、JDBC TLS、真实计数、八家新企业暂停、故障触发器清理等。

内部假 SMTP 使用临时 Function 私网 2525，收件人仅 example.invalid，无对外邮件。附件写入专用随机测试前缀并清理，不能使用原预览 prefix。临时故障触发器只作用于指定验收企业 / 对象并在 finally 删除，不进入 Flyway 或发布源码包。

## 发布后检查

verify_p1d_readonly.ts 仅对已知 HTTPS 预览正常登录、选择自己的企业、GET 和退出。导出写正常访问审计，不修改业务记录；不提交导入、不上传文件、不申请邮件找回、不变更账号 / 企业状态 / 权限。脚本包含原模块 GET 及发布资源字节摘要验证。

collect_preview_baseline.ts 只保存业务表和企业 / 账号元数据的行数与摘要；不输出记录正文、不读取密码哈希。摘要比较不是备份。

## 备份演练边界

一次隔离加密备份恢复演练已验证 33 表、900 行、一个非空附件、17 表 RLS、5 次迁移、S3 读回、认证解密与篡改拒绝。该演练使用 crm_acceptance 中的虚构数据，临时对象已清理；不是原预览备份。用户随后明确授权专用数据库备份账号，已创建并核对全表 SELECT、业务表无写权限及 TLS。原预览每日北京时间 02:00 加密备份，首份快照保留并在新本地库恢复核对 33 表、279 行、17 表 RLS 和 5 次迁移；当前无附件。结果见 P1-D备份启用验收.json。手动生产切流仍未执行。

## 收尾与历史

保存逐项结果 / 脱敏证据后关闭会话；暂停新建验收企业，清除故障触发器，停止临时 Java / PostgreSQL / SMTP 进程，清空独立凭据和 S3 引用。无卷临时库的行不作为持久交付；交付源码和结果。两请求并发检查不是压力测试。结果及部署证据位于 docs/P1-D*.json。

P0～P1-C 脚本和历史文档继续保留。verify_database.ts 属于 P0 历史写入脚本，其中旧角色权限断言已变化，本轮不执行。verify_isolated_sql.mjs 属于内存 WASM 的 SQL 补充检查，不能替代原生 PostgreSQL 18 的并发和 TLS 验收。不要自动运行这些历史脚本到原预览数据库。

## 备份、密钥保管与 SMTP 运维边界

安装 boto3、psycopg2-binary 和 cryptography 后运行 `python3 server/test-support/test_backup_operations.py`，15 项验证使用随机密钥、虚构对象和内存数据库适配器，不连接任何现有数据库或桶。覆盖 30 天 / 最少 3 份保留、命名空间 / UUID / 元数据异常、删除确认失败、中断后重试、加密包完整性、0600 文件及 SMTP 配置门槛。该结果不等同于真实旧对象清理、独立密钥交接或邮件送达验收。

## P2-A 验收

`verify_p2a.ts` 在明确的私有 `crm_acceptance` 新库验收产品、报价、金额、权限、历史、PDF、并发和事务回滚；执行前需三企业 / 六账号的全新库校验。旧 P0～P1-D 已在同一后端版本通过 437 项回归，P2-A 在新的临时库单独通过 88 项，分别保存证据，不把中途失败的整套运行当作整体通过。`prepare_p2a_backup.ts` 仅在该临时库建立一次性完整备份账户和非空附件，随后的恢复检查需停止临时 PostgreSQL 并清理测试对象。

`verify_p2a_readonly.ts` 仅访问已知 HTTPS 云预览，进行正常登录、自己的企业选择、GET 和退出，不创建产品 / 报价、不上传附件、不发送邮件。最终发布须核对全部三个构建资源的 SHA-256。

本期 `test_backup_operations.py` 合计 17 项通过，新增 V5 / V6 全表集合与强制 RLS 验证。真正的 V6 隔离恢复另核对 38 表、257 行、22 表 RLS、6 次迁移、1 个非空附件及 5 张商业表内容摘要；见 P2-A 证据，不能将历史 33 表结果作为 V6 验证。

## P2-B 验收

`verify_p2b.ts` 只允许指定私有 `crm_acceptance` 与 `CRM_P2B_WRITE_SCOPE=isolated-new-database`，新建虚构企业验证指定经理、用户级禁止自审、当前资料权限、版本绑定、重复决策、并发、撤回 / 作废、批准后重提、金额 / SHA 校验及注入失败原子回滚。完整套件包含 P0～P2-B 与 checkpoint，首次运行前严格检查三企业 / 六账号。本期 525 项旧回归、73 项 P2-B 与 11 项 checkpoint 全部通过最终标记。

历史 P1-D 附件清理改为确认对象删除后，同步标记该脚本自己新建企业的附件为已删除；不能留下 READY 指向已删除测试对象。实际恢复正确拦截了此前这类测试残留，随后在私有临时库核验缺失对象并修正元数据；未放宽备份完整性条件。

`prepare_p2b_backup.ts` / `cleanup_p2b_tests.ts` 用独立前缀验收 V7 数据、非空附件和恢复；原每日服务不是该一次性验证服务。`verify_p2b_readonly.ts` 核对已知 HTTPS 版本、审批权限、队列和构建资源；`verify_p2b_visible_data.ts` 比较原账号自己可读取的六类资料，摘要比较不是全库备份。具体部署及清理结果见本期 JSON。

## P2-C / D 验收

`verify_p2cd.ts` 仅在指定的私有 `crm_acceptance`、`CRM_P2CD_WRITE_SCOPE=isolated-new-database` 内验证批准报价建合同、分期计划、签订 / 生效、受控变更、历史、附件继承权限、登记不计入已收、异人确认、并发超收防护、凭证号归一化、冲正与更正链、汇总与 CSV、当前权限和故障回滚。旧功能 598 项、本期合同 58 项与回款 82 项、最终 checkpoint 11 项通过；证据保留了脚本纠错与重测范围。

`prepare_p2cd_backup.ts` 要求 V9 与真实非空回款凭证，仅建立一次性备份登录。完整加密恢复检查 50 表 / 34 张强制 RLS 表 / 9 次迁移 / 17 张商业表摘要和两份凭证。`cleanup_p2cd_tests.ts` 只清理经成功备份审计的测试前缀和附件元数据，可以在已清空状态重复检查，不能删除原预览备份。

旧 P1-D 清理条件按实际 `p1d-a-<suffix>` / `p1d-b-<suffix>` 企业代码修正；附件容量由既有触发器维护，清理脚本不重复扣减。

`verify_p2cd_upgrade.ts` 仅通过原 owner 的正常 HTTPS 登录、自己企业选择及 GET，在内存比较八类可见资料，公开日志仅输出通过与否，不输出正文、ID 或业务指纹。`verify_p2cd_readonly.ts` 核对版本、模块权限、未知记录不披露、导出协议、备份可用性和三个公开构建资源；不创建合同 / 回款，不请求邮件投递。
