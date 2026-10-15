package cn.binbin.crm;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
class QuotePdfTest {
 @Test void embeddedChineseFontLongRowsAndNotesRenderAcrossPages()throws Exception{
    String path=System.getProperty("crm.quote.test.font","");assumeTrue(!path.isEmpty()&&Files.exists(Path.of(path)),"Local PDF visual fixture requires verified font");
    var q=new HashMap<String,Object>(Map.of("companyNameSnapshot","彬彬科技 · 测试企业","quoteNumber","QT-2026-000001","version",3,"status","SUBMITTED","title","制造企业 MES / WMS 集成服务报价","customerNameSnapshot","星澜科技 · 虚构客户","ownerNameSnapshot","销售经理","validUntil","2099-12-31","notes","付款与交付说明：按确认后的范围分阶段交付。请核对报价明细、数量、含税单价与折扣。\n".repeat(25),"totalCents",209880));
    var lines=new ArrayList<Map<String,Object>>();for(int i=1;i<=36;i++)lines.add(Map.of("lineNumber",i,"nameSnapshot","系统集成与设备数据采集服务第"+i+"项","skuSnapshot","SERVICE-"+i,"unitSnapshot","项","quantity","3.3333","unitPriceCents",1999,"discountBps",1250,"amountCents",5830,"description","覆盖需求分析、接口联调、部署和验收，保留配置与日志；本行使用虚构资料测试中文换行和长说明。"));q.put("lines",lines);
    byte[] bytes=QuotePdf.render(q,Files.readAllBytes(Path.of(path)));try(var doc=Loader.loadPDF(bytes)){assertThat(doc.getNumberOfPages()).isGreaterThan(2);String text=new PDFTextStripper().getText(doc);assertThat(text).contains("星澜科技","第36项","2098.80","待审批");for(var p:doc.getPages())for(var name:p.getResources().getFontNames())assertThat(p.getResources().getFont(name).isEmbedded()).isTrue();}
    for(var state:Map.of("APPROVED","已批准","REJECTED","已驳回").entrySet()){q.put("status",state.getKey());try(var statusDoc=Loader.loadPDF(QuotePdf.render(q,Files.readAllBytes(Path.of(path))))){assertThat(new PDFTextStripper().getText(statusDoc)).contains(state.getValue());}}
    Path out=Path.of("target/pdf-qa");Files.createDirectories(out);Files.write(out.resolve("quote-long.pdf"),bytes);q.put("lines",lines.subList(0,2));q.put("totalCents",11660);q.put("notes","有效期内请确认方案和交付范围。\n本报价采用人民币含税价。" );q.put("status","DRAFT");Files.write(out.resolve("quote-short.pdf"),QuotePdf.render(q,Files.readAllBytes(Path.of(path))));
 }
}
