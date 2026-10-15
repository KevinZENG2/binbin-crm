package cn.binbin.crm;

import java.net.URI;
import java.time.Duration;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

@Service
public class ObjectStore {
    private final S3Client client;private final String bucket,prefix;
    public ObjectStore(@Value("${CRM_S3_ENDPOINT:}") String endpoint,@Value("${CRM_S3_BUCKET:}") String bucket,@Value("${CRM_S3_REGION:auto}") String region,
        @Value("${CRM_S3_ACCESS_KEY:}") String key,@Value("${CRM_S3_SECRET_KEY:}") String secret,@Value("${CRM_S3_PATH_STYLE:false}") boolean pathStyle,@Value("${CRM_OBJECT_PREFIX:preview}") String prefix){
        this.bucket=bucket;this.prefix=prefix;if(!prefix.matches("[a-z0-9-]{1,80}"))throw new IllegalArgumentException("Object namespace invalid");
        if(endpoint.isBlank()||bucket.isBlank()||key.isBlank()||secret.isBlank()){client=null;return;}
        var uri=URI.create(endpoint);if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null)throw new IllegalArgumentException("S3 requires HTTPS");
        client=S3Client.builder().endpointOverride(uri).region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key,secret)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).chunkedEncodingEnabled(false).build())
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(20)))
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(30)).apiCallAttemptTimeout(Duration.ofSeconds(20))).build();
    }
    public boolean configured(){return client!=null;}
    public String key(java.util.UUID tenant,java.util.UUID id){return prefix+"/attachments/"+tenant+"/"+id;}
    private void ready(String key){if(client==null)throw new ApiError(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","附件服务尚未就绪");if(!key.startsWith(prefix+"/attachments/"))throw new IllegalArgumentException("Object outside attachment namespace");}
    public void put(String key,byte[] bytes,String type){ready(key);client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(type).build(),RequestBody.fromBytes(bytes));}
    public byte[] get(String key){ready(key);return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();}
    private byte[] cachedQuoteFont;
    public synchronized byte[] quoteFont(){
        if(client==null)throw new ApiError(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"QUOTE_FONT_UNAVAILABLE","报价字体服务尚未就绪");
        if(cachedQuoteFont==null){String sha="12a7fef9f024f411ed7e2d89a614b9865f14ae4129ad4993a7e117f36322a794";byte[] data=client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key("system/fonts/noto-sans-sc-"+sha+".ttf").build()).asByteArray();
            try{String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));if(!sha.equals(actual))throw new IllegalStateException("QUOTE_FONT_HASH_MISMATCH");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}cachedQuoteFont=data;
        }return cachedQuoteFont;
    }
    public void delete(String key){ready(key);client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());}
    public boolean healthy(){if(client==null)return false;try{client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());return true;}catch(Exception e){return false;}}
    @PreDestroy public void close(){if(client!=null)client.close();}
}
