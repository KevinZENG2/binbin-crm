package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class DeliveryProtocolTest {
    private byte[] utf8(String text){return text.getBytes(StandardCharsets.UTF_8);}
    @Test void quotedCsvPreservesCommaQuoteAndPhysicalLine(){
        var rows=CsvSupport.parse(utf8("\uFEFF名称,备注\r\n\"客户,一\",\"第一行\n第二行\"\r\n\"客户\"\"二\",普通\r\n"),500);
        assertThat(rows).hasSize(3);assertThat(rows.get(1).values()).containsExactly("客户,一","第一行\n第二行");
        assertThat(rows.get(2).line()).isEqualTo(4);assertThat(rows.get(2).values().getFirst()).isEqualTo("客户\"二");
    }
    @Test void malformedUtf8AndQuotesAreRejected(){
        assertThatThrownBy(()->CsvSupport.parse(new byte[]{(byte)0xC3,0x28},500)).isInstanceOf(ApiError.class);
        for(String csv:List.of("a\nb\"c", "a\n\"b\"c", "a\n\"open", "a\nx\0y"))assertThatThrownBy(()->CsvSupport.parse(utf8(csv),500)).isInstanceOf(ApiError.class);
    }
    @Test void csvLimitsCountLogicalRecordsAndPermitFinalEmptyCell(){
        assertThat(CsvSupport.parse(utf8("a,b\n\n客户,\n"),1).get(1).values()).containsExactly("客户","");
        assertThatThrownBy(()->CsvSupport.parse(utf8("a\nb\nc"),1)).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->CsvSupport.parse(new byte[524289],500)).isInstanceOf(ApiError.class);
    }
    @Test void exportsNeutralizeSpreadsheetFormulasAndEscapeQuotes(){
        for(String dangerous:List.of("=1+1","  +cmd","-7","@SUM(A1)","\tvalue","\rvalue"))assertThat(CsvSupport.cell(dangerous)).startsWith("\"'");
        assertThat(CsvSupport.cell("正常\"文字")).isEqualTo("\"正常\"\"文字\"");
        assertThat(new String(CsvSupport.write(List.of("列"),List.of(Arrays.asList((Object)null))),StandardCharsets.UTF_8)).isEqualTo("\uFEFF\"列\"\r\n\"\"\r\n");
    }
    @Test void attachmentFileNamesRemoveDirectoriesAndRejectHeaderControls(){
        assertThat(AttachmentService.filename("C:\\fakepath\\报告.pdf")).isEqualTo("报告.pdf");
        assertThatThrownBy(()->AttachmentService.filename("bad\r\nname.txt")).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->AttachmentService.filename("../")).isInstanceOf(ApiError.class);
    }
    @Test void fileTypeRequiresAllowedExtensionAndMatchingSignature(){
        assertThat(AttachmentService.fileType("a.PNG",new byte[]{(byte)137,80,78,71,13,10,26,10})).isEqualTo("image/png");
        assertThat(AttachmentService.fileType("a.pdf",utf8("%PDF-1.7"))).isEqualTo("application/pdf");
        assertThat(AttachmentService.fileType("a.txt",utf8("中文文本"))).isEqualTo("text/plain");
        for(String name:List.of("a.png","a.html","a.docx","a.exe"))assertThatThrownBy(()->AttachmentService.fileType(name,utf8("text"))).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->AttachmentService.fileType("a.txt",new byte[]{0})).isInstanceOf(ApiError.class);
        assertThatThrownBy(()->AttachmentService.fileType("a.txt",new byte[0])).isInstanceOf(ApiError.class);
    }
    @Test void administrationAndViewerDoNotGrantBulkWriteOrExport(){
        assertThat(DeliveryAccess.defaults(Set.of("TENANT_ADMIN"))).isEqualTo(DeliveryAccess.none());
        var viewer=DeliveryAccess.defaults(Set.of("VIEWER"));assertThat(viewer.reports()).isTrue();assertThat(viewer.attachmentRead()).isTrue();assertThat(viewer.export()).isFalse();assertThat(viewer.customerImport()).isFalse();assertThat(viewer.attachmentWrite()).isFalse();
        assertThat(DeliveryAccess.defaults(Set.of("SALES")).customerImport()).isFalse();assertThat(DeliveryAccess.defaults(Set.of("SALES_MANAGER")).customerImport()).isTrue();
    }
    @Test void combinedPoliciesPreserveIndependentDeliverySwitches(){
        var access=DeliveryAccess.from(List.of(Map.of("report_can_read",true),Map.of("attachment_can_read",true,"attachment_can_write",true)));
        assertThat(access.reports()).isTrue();assertThat(access.attachmentWrite()).isTrue();assertThat(access.customerImport()).isFalse();assertThat(access.export()).isFalse();
    }
}
