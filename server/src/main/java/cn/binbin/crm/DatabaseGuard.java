package cn.binbin.crm;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @Order(1)
public class DatabaseGuard implements ApplicationRunner {
    private final JdbcTemplate db;
    public DatabaseGuard(JdbcTemplate db){this.db=db;}
    public void run(ApplicationArguments args) {
        var role=db.queryForMap("SELECT rolname,rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user");
        if(!"crm_runtime".equals(role.get("rolname")) || Boolean.TRUE.equals(role.get("rolsuper")) || Boolean.TRUE.equals(role.get("rolbypassrls")))throw new IllegalStateException("Runtime database role must be crm_runtime without superuser or BYPASSRLS");
        var safe=db.queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relrowsecurity AND c.relforcerowsecurity AND pg_get_userbyid(c.relowner)='crm_migrator'",String.class);
        var expected=java.util.Set.of("crm_activity","crm_approval_rule","crm_attachment","crm_campaign","crm_campaign_execution","crm_campaign_member","crm_campaign_result","crm_campaign_revision","crm_collection_plan","crm_contact","crm_contract","crm_contract_change","crm_contract_counter","crm_contract_quote_link","crm_contract_revision","crm_custom_field","crm_custom_value","crm_customer","crm_customer_share","crm_customer_tag","crm_dictionary_item","crm_import_batch","crm_integration_key","crm_integration_nonce","crm_knowledge_article","crm_knowledge_category","crm_knowledge_revision","crm_lead","crm_marketing_consent","crm_number_rule","crm_opportunity","crm_opportunity_history","crm_process_stage","crm_product","crm_quote","crm_quote_approval","crm_quote_approval_event","crm_quote_counter","crm_quote_line","crm_quote_revision","crm_receipt","crm_receipt_counter","crm_receipt_reversal","crm_receipt_revision","crm_service_notification","crm_sla_breach","crm_sla_policy","crm_tag","crm_task","crm_ticket","crm_ticket_article","crm_ticket_comment","crm_ticket_counter","crm_ticket_revision","crm_ticket_sla_episode","crm_webhook_attempt","crm_webhook_outbox","crm_webhook_subscription","org_department","sys_audit_log","sys_idempotency_request","sys_notification","sys_object_cleanup","sys_role_policy","sys_support_access_log","sys_tenant_profile");
        if(!new java.util.HashSet<>(safe).equals(expected))throw new IllegalStateException("All business tables must have forced RLS and separate migration ownership");
    }
}
