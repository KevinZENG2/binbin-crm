package cn.binbin.crm;
import java.io.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Service;

/** Embeds a verified Chinese TTF; pagination is measured using actual glyph widths. */
@Service
public class QuotePdf {
    private final ObjectStore storage;private final java.util.concurrent.Semaphore permits=new java.util.concurrent.Semaphore(1);
    public QuotePdf(ObjectStore storage){this.storage=storage;}
    public byte[] render(Map<String,Object> quote){if(!permits.tryAcquire())throw new ApiError(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"QUOTE_PDF_BUSY","正在生成其他报价 PDF，请稍后重试");try{return render(quote,storage.quoteFont());}catch(ApiError e){throw e;}catch(Exception e){throw new ApiError(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"QUOTE_PDF_UNAVAILABLE","报价 PDF 暂未就绪，请稍后重试");}finally{permits.release();}}
    @SuppressWarnings("unchecked")
    static byte[] render(Map<String,Object> q,byte[] ttf)throws IOException{
        try(var doc=new PDDocument();var output=new ByteArrayOutputStream()){
            var font=PDType0Font.load(doc,new ByteArrayInputStream(ttf),true);var layout=new Layout(doc,font,q);
            layout.page();layout.text("报价单",20,48,layout.y,0.1f,0.29f,0.22f);layout.y-=30;
            layout.paragraph(String.valueOf(q.get("companyNameSnapshot")),12);layout.paragraph("编号 "+q.get("quoteNumber")+"   版本 "+q.get("version")+"   "+status(q),10);
            layout.paragraph("报价主题："+q.get("title"),11);layout.paragraph("客户："+q.get("customerNameSnapshot"),10);
            if(q.get("opportunityTitleSnapshot")!=null)layout.paragraph("关联商机："+q.get("opportunityTitleSnapshot"),10);
            layout.paragraph("报价人："+q.get("ownerNameSnapshot")+"   有效期："+q.get("validUntil"),10);layout.paragraph("币种：人民币 CNY；单价为含税价，折扣后逐行四舍五入到分。",9);layout.y-=12;layout.header();
            for(var item:(List<Map<String,Object>>)q.get("lines")){
                var name=layout.wrap(item.get("lineNumber")+". "+item.get("nameSnapshot")+"\n"+item.get("skuSnapshot")+" · "+item.get("unitSnapshot")+(String.valueOf(item.get("description")).isBlank()?"":"\n"+item.get("description")),190,9);
                float height=Math.max(35,name.size()*13+12);if(layout.y-height<70){layout.page();layout.header();}
                float rowY=layout.y;int n=0;for(String line:name)layout.text(line,9,54,rowY-14-13*n++,0.16f,0.22f,0.18f);
                layout.text(new BigDecimal(String.valueOf(item.get("quantity"))).stripTrailingZeros().toPlainString(),9,258,rowY-14,0.16f,0.22f,0.18f);
                layout.text(money(item.get("unitPriceCents")),9,303,rowY-14,0.16f,0.22f,0.18f);
                layout.text(new BigDecimal(String.valueOf(item.get("discountBps"))).movePointLeft(2).stripTrailingZeros().toPlainString()+"%",9,378,rowY-14,0.16f,0.22f,0.18f);
                layout.text(money(item.get("amountCents")),9,433,rowY-14,0.16f,0.22f,0.18f);layout.y-=height;layout.rule(layout.y);
            }
            if(layout.y<130)layout.page();layout.y-=25;layout.text("报价总额（含税）：¥ "+money(q.get("totalCents")),14,285,layout.y,0.1f,0.29f,0.22f);layout.y-=27;
            if(q.get("voidReason")!=null)layout.paragraph("作废原因："+q.get("voidReason"),10);
            if(q.get("notes")!=null&&!String.valueOf(q.get("notes")).isBlank()){layout.paragraph("报价说明",11);layout.paragraph(String.valueOf(q.get("notes")),10);}
            layout.paragraph("该文件为指定版本的报价记录。待审批报价尚未获批；审批功能在下一阶段接入。",9);layout.close();
            int count=doc.getNumberOfPages();for(int i=0;i<count;i++)try(var stream=new PDPageContentStream(doc,doc.getPage(i),PDPageContentStream.AppendMode.APPEND,true,true)){stream.beginText();stream.setFont(font,8);stream.newLineAtOffset(48,35);stream.showText(q.get("quoteNumber")+" / v"+q.get("version")+"      "+(i+1)+" / "+count);stream.endText();}
            doc.getDocumentInformation().setTitle("报价 "+q.get("quoteNumber"));doc.getDocumentInformation().setAuthor(String.valueOf(q.get("companyNameSnapshot")));doc.save(output);return output.toByteArray();
        }catch(IllegalArgumentException e){throw ApiError.bad("报价含字体暂不支持的字符，请使用常用中文、数字及标点后重试 PDF");}
    }
    private static String money(Object value){return new BigDecimal(String.valueOf(value)).movePointLeft(2).setScale(2).toPlainString();}
    private static String status(Map<String,Object> q){String s=String.valueOf(q.get("status"));String state=s.equals("DRAFT")?"草稿":s.equals("VOID")?"已作废":s.equals("APPROVED")?"已批准":s.equals("REJECTED")?"已驳回":"待审批";return state+(LocalDate.parse(String.valueOf(q.get("validUntil"))).isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai")))?" · 已过期":"");}
    private static final class Layout {
        final PDDocument doc;final PDType0Font font;final Map<String,Object> quote;PDPageContentStream stream;float y;
        Layout(PDDocument d,PDType0Font f,Map<String,Object> q){doc=d;font=f;quote=q;}
        void close()throws IOException{if(stream!=null)stream.close();}
        void page()throws IOException{close();var page=new PDPage(PDRectangle.A4);doc.addPage(page);stream=new PDPageContentStream(doc,page);y=785;text(quote.get("quoteNumber")+" · "+status(quote),8,48,810,0.4f,0.45f,0.42f);}
        void text(String text,float size,float x,float y,float r,float g,float b)throws IOException{stream.setNonStrokingColor(r,g,b);stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,y);stream.showText(text.replace('\t',' '));stream.endText();}
        void rule(float at)throws IOException{stream.setStrokingColor(0.82f,0.87f,0.84f);stream.moveTo(48,at);stream.lineTo(547,at);stream.stroke();}
        void header()throws IOException{if(y<110)page();text("产品 / 服务",9,54,y-14,0.1f,0.29f,0.22f);text("数量",9,258,y-14,0.1f,0.29f,0.22f);text("含税单价",9,303,y-14,0.1f,0.29f,0.22f);text("折扣",9,378,y-14,0.1f,0.29f,0.22f);text("含税金额",9,433,y-14,0.1f,0.29f,0.22f);y-=27;rule(y);}
        List<String> wrap(String text,float width,float size)throws IOException{var result=new ArrayList<String>();for(String paragraph:text.replace('\t',' ').split("\n",-1)){String line="";for(int cp:paragraph.codePoints().toArray()){String next=line+new String(Character.toChars(cp));if(font.getStringWidth(next)/1000*size>width&&!line.isEmpty()){result.add(line);line=new String(Character.toChars(cp));}else line=next;}result.add(line);}return result;}
        void paragraph(String text,float size)throws IOException{for(String line:wrap(text,499,size)){if(y-size-7<70)page();text(line,size,48,y,0.16f,0.22f,0.18f);y-=size+7;}}
    }
}
