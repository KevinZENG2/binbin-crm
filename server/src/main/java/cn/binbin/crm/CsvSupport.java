package cn.binbin.crm;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

public final class CsvSupport {
    private CsvSupport(){}
    public record Row(int number,int line,List<String> values) {}
    public static List<Row> parse(byte[] bytes,int maxRows){
        if(bytes.length>524288)throw ApiError.bad("CSV 文件最多512KB");String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw ApiError.bad("CSV 需使用 UTF-8 编码");}
        if(text.startsWith("\uFEFF"))text=text.substring(1);if(text.indexOf('\0')>=0)throw ApiError.bad("CSV 包含无效字符");
        var rows=new ArrayList<Row>();var values=new ArrayList<String>();var field=new StringBuilder();boolean quoted=false,closed=false,started=false;int line=1,start=1;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}else{if(c=='\n')line++;field.append(c);}continue;}
            if(c=='"'){if(started||field.length()>0||closed)throw ApiError.bad("CSV 引号格式无效，第"+line+"行");quoted=true;started=true;continue;}
            if(c==','||c=='\r'||c=='\n'){
                values.add(field.toString());field.setLength(0);started=false;closed=false;
                if(c!=','){if(!values.stream().allMatch(String::isBlank)){rows.add(new Row(rows.size()+1,start,List.copyOf(values)));if(rows.size()>maxRows+1)throw ApiError.bad("一次最多导入"+maxRows+"条客户");}values.clear();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;line++;start=line;}
            }else{if(closed)throw ApiError.bad("CSV 闭合引号后只能是分隔符，第"+line+"行");field.append(c);started=true;}
        }
        if(quoted)throw ApiError.bad("CSV 引号未闭合");
        if(started||closed||field.length()>0||!values.isEmpty()){values.add(field.toString());if(!values.stream().allMatch(String::isBlank))rows.add(new Row(rows.size()+1,start,List.copyOf(values)));}
        if(rows.size()>maxRows+1)throw ApiError.bad("一次最多导入"+maxRows+"条客户");return rows;
    }
    public static String cell(Object value){String s=value==null?"":String.valueOf(value);String trimmed=s.stripLeading();if(!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0||s.startsWith("\t")||s.startsWith("\r"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    public static byte[] write(List<String> headers,List<List<Object>> rows){var out=new StringBuilder("\uFEFF");out.append(String.join(",",headers.stream().map(CsvSupport::cell).toList())).append("\r\n");for(var row:rows)out.append(String.join(",",row.stream().map(CsvSupport::cell).toList())).append("\r\n");return out.toString().getBytes(StandardCharsets.UTF_8);}
}
