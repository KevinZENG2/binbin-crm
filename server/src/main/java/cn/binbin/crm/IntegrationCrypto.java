package cn.binbin.crm;
import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;import javax.crypto.*;import javax.crypto.spec.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.stereotype.Component;
@Component
public class IntegrationCrypto {
 private final byte[] key;private final SecureRandom random=new SecureRandom();
 public IntegrationCrypto(@Value("${CRM_INTEGRATION_ENCRYPTION_KEY:}") String value){if(value.isBlank())key=null;else{if(!value.matches("[a-fA-F0-9]{64}"))throw new IllegalStateException("CRM integration key must be 256 bits");key=HexFormat.of().parseHex(value);}}
 public boolean configured(){return key!=null;}
 String encrypt(UUID tenant,UUID subscription,String secret){if(key==null)throw ApiError.conflict("集成密钥保管尚未配置");try{byte[] iv=new byte[12];random.nextBytes(iv);var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD((tenant+":"+subscription).getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(c.doFinal(secret.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException e){throw new IllegalStateException("Integration encryption failed");}}
 String decrypt(UUID tenant,UUID subscription,String sealed){if(key==null)throw ApiError.conflict("集成密钥保管尚未配置");try{var parts=sealed.split("\\.");var c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));c.updateAAD((tenant+":"+subscription).getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("Integration decryption failed");}}
 static String hmac(String secret,String canonical){try{var m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(m.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Signing failed");}}
 static boolean equal(String expected,String actual){return actual!=null&&actual.matches("[a-f0-9]{64}")&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));}
 static String inbound(String method,String path,String timestamp,String nonce,String body){return method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+Crypto.hash(body);}
 static String outbound(String timestamp,UUID event,String body){return timestamp+"\n"+event+"\n"+Crypto.hash(body);}
}
