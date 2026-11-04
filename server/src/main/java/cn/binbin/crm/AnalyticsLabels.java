package cn.binbin.crm;
import java.util.Map;
final class AnalyticsLabels {
    private static final Map<String,String> NAMES=Map.ofEntries(
        Map.entry("CORE","核心客户"),Map.entry("ACTIVE","活跃客户"),Map.entry("REACTIVATE","待唤醒客户"),Map.entry("DEVELOPING","待培育客户"),
        Map.entry("CONTACT","初次接触"),Map.entry("NEEDS","需求确认"),Map.entry("SOLUTION","方案讨论"),Map.entry("NEGOTIATION","报价谈判"),Map.entry("READY","签约准备"),Map.entry("WON","成交"),Map.entry("LOST","丢单"),
        Map.entry("NEW","新建"),Map.entry("CONTACTED","已联系"),Map.entry("QUALIFIED","合格"),Map.entry("UNQUALIFIED","不合格"),Map.entry("CONVERTED","已转客户"),
        Map.entry("DRAFT","草稿"),Map.entry("SUBMITTED","待审批"),Map.entry("APPROVED","已批准"),Map.entry("REJECTED","已退回"),Map.entry("VOID","已作废"),Map.entry("CONFIRMED","已确认"),Map.entry("IN_DELIVERY","交付中"),Map.entry("DELIVERED","已交付"),
        Map.entry("RECEIPT","确认到账"),Map.entry("REVERSAL","回款冲销"),Map.entry("OPEN","待受理"),Map.entry("IN_PROGRESS","处理中"),Map.entry("WAITING_CUSTOMER","等待客户"),Map.entry("RESOLVED","已解决"),Map.entry("CLOSED","已关闭"),Map.entry("REVIEWED","已复核"),Map.entry("LOCKED","已锁定"));
    static String state(String key){return NAMES.getOrDefault(key,key.isEmpty()?"未填写":key);}
}
