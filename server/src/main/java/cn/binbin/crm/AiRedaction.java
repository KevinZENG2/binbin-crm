package cn.binbin.crm;


/** Applied to free text, instructions, context previews and model output. */
final class AiRedaction {
 private AiRedaction(){}
 static String redact(String text){
  if(text==null)return "";
  return text.replaceAll("(?i)(?:bearer\\s+|sk-)[a-z0-9_./+\\-=]{8,}","[凭据已隐藏]")
   .replaceAll("(?i)(password|passwd|pwd|api[_-]?key|access[_-]?token|secret|密码|密钥)\\s*[:=：]\\s*[^\\s,，;；}\\\"]+","$1=[已隐藏]")
   .replaceAll("(?i)[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9.-]+\\.[a-z]{2,}","[邮箱已隐藏]")
   .replaceAll("(?<![A-Za-z0-9])(?:\\+?86[- ]?)?1[3-9][0-9](?:[- ]?[0-9]){8}(?![0-9])","[手机已隐藏]")
   .replaceAll("(?<![A-Za-z0-9])(?:[0-9][ -]?){15,19}(?![A-Za-z0-9])","[证件或账号已隐藏]")
   .replaceAll("(?<![A-Za-z0-9])[0-9A-Z]{18}(?![A-Za-z0-9])","[企业或证件标识已隐藏]")
   .replaceAll("(?<![0-9])0[0-9]{2,3}[- ]?[0-9]{7,8}(?![0-9])","[电话已隐藏]");
 }
 static long cost(long input,long output,long inputRate,long outputRate){
  long units=Math.addExact(Math.multiplyExact(input,inputRate),Math.multiplyExact(output,outputRate));
  return units/1000000+(units%1000000==0?0:1);
 }
}
