package cn.binbin.crm;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
class AiFoundationTest {
 final MockEnvironment env=new MockEnvironment(); final AiGateway gateway=new AiGateway(env,new ObjectMapper());
 @Test void freeTextAndCredentialsAreRedacted(){String source="13800138000 aa@example.com +86 139-0013-8000 010-12345678 110101199003070012 sk-secret123456 password=verysecret api_key:moresecret";String safe=AiRedaction.redact(source);for(String value:List.of("13800138000","aa@example.com","139-0013-8000","12345678","110101199003070012","secret123456","verysecret","moresecret"))assertFalse(safe.contains(value),safe);}
 @Test void normalBusinessTextIsPreserved(){assertEquals("客户阶段：评估，金额1200元，待确认",AiRedaction.redact("客户阶段：评估，金额1200元，待确认"));assertEquals("",AiRedaction.redact(null));}
 @Test void referenceCostRoundsUpOnce(){assertEquals(0,AiRedaction.cost(0,0,100,200));assertEquals(1,AiRedaction.cost(1,1,100,200));assertEquals(300,AiRedaction.cost(1000000,1000000,100,200));assertThrows(ArithmeticException.class,()->AiRedaction.cost(Long.MAX_VALUE,0,100,1));}
 @Test void fixedProviderEndpoints(){assertEquals("https://api.openai.com/v1/chat/completions",gateway.endpoint("OPENAI").toString());assertEquals("https://api.deepseek.com/chat/completions",gateway.endpoint("DEEPSEEK").toString());assertThrows(AiGateway.Failure.class,()->gateway.endpoint("OTHER"));}
 @Test void qwenHostCannotRedirectToArbitraryServers(){for(String url:List.of("http://dashscope.aliyuncs.com/v1","https://127.0.0.1/v1","https://dashscope.aliyuncs.com.evil.test/v1","https://user@dashscope.aliyuncs.com/v1","https://dashscope.aliyuncs.com:443/v1","https://dashscope.aliyuncs.com/v1?secret=x")){env.setProperty("CRM_AI_QWEN_BASE_URL",url);assertThrows(AiGateway.Failure.class,()->gateway.endpoint("QWEN"));}env.setProperty("CRM_AI_QWEN_BASE_URL","https://workspace-123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");assertTrue(gateway.endpoint("QWEN").toString().endsWith("/chat/completions"));}
 @Test void acceptanceStubRequiresBothProfileAndNonSecretFixtureKey(){env.setProperty("CRM_AI_OPENAI_API_KEY","crm-acceptance-only");assertEquals("api.openai.com",gateway.endpoint("OPENAI").getHost());env.setActiveProfiles("acceptance");assertEquals("127.0.0.1",gateway.endpoint("OPENAI").getHost());env.setProperty("CRM_AI_OPENAI_API_KEY","different-test-key");assertEquals("api.openai.com",gateway.endpoint("OPENAI").getHost());}
 @Test void payloadUsesProviderSpecificLimitsAndNoTools(){var a=AiGateway.payload("OPENAI","configured-model","已脱敏",512);assertEquals(false,a.get("store"));assertEquals(512,a.get("max_completion_tokens"));assertFalse(a.containsKey("max_tokens"));for(String p:List.of("DEEPSEEK","QWEN")){var other=AiGateway.payload(p,"model","text",512);assertEquals(512,other.get("max_tokens"));assertFalse(other.containsKey("tools"));if(p.equals("DEEPSEEK"))assertEquals(Map.of("type","disabled"),other.get("thinking"));}}
 String body(String usage){return "{\"choices\":[{\"message\":{\"content\":\"联系 aa@example.com\"}}]"+usage+"}";}
 @Test void validUsageAndOutputRedaction(){var r=gateway.parse(body(",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":12}"),128,1000);assertEquals(100,r.inputTokens());assertTrue(r.usageReported());assertFalse(r.text().contains("aa@example.com"));}
 @Test void missingUsageIsExplicitlyEstimated(){assertFalse(gateway.parse(body(""),128,1000).usageReported());}
 @Test void malformedOrOutOfRangeUsageIsUncertain(){for(String usage:List.of("{\"prompt_tokens\":1.5,\"completion_tokens\":1}","{\"prompt_tokens\":-1,\"completion_tokens\":1}","{\"prompt_tokens\":1001,\"completion_tokens\":1}","{\"prompt_tokens\":1,\"completion_tokens\":129}","{\"prompt_tokens\":1}")){var e=assertThrows(AiGateway.Failure.class,()->gateway.parse(body(",\"usage\":"+usage),128,1000));assertTrue(e.uncertain);}}
 @Test void malformedResponseIsUncertain(){assertTrue(assertThrows(AiGateway.Failure.class,()->gateway.parse("{}",128,1000)).uncertain);assertThrows(AiGateway.Failure.class,()->gateway.parse("broken",128,1000));}
 @Test void oversizedResponseCancelsSubscription(){var body=new AiGateway.LimitedBody();boolean[] cancelled={false};body.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){cancelled[0]=true;}});body.onNext(List.of(ByteBuffer.allocate(262145)));assertTrue(cancelled[0]);assertTrue(body.result.isCompletedExceptionally());}
}
