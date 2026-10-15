package cn.binbin.crm;

import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class P4Numbering {
 private P4Numbering(){}
 public static String format(NamedParameterJdbcTemplate db,UUID tenant,String type,int year,long sequence,String fallback){var rows=db.queryForList("SELECT prefix,include_year,padding FROM crm_number_rule WHERE tenant_id=:tenant AND document_type=:type",Map.of("tenant",tenant,"type",type));String prefix=fallback;boolean include=true;int padding=6;if(!rows.isEmpty()){var r=rows.getFirst();prefix=String.valueOf(r.get("prefix"));include=Boolean.TRUE.equals(r.get("include_year"));padding=((Number)r.get("padding")).intValue();}return prefix+(include?"-"+year:"")+"-"+String.format(Locale.ROOT,"%0"+padding+"d",sequence);}
}
