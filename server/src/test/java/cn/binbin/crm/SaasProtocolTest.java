package cn.binbin.crm;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
class SaasProtocolTest {
 @Test void readAndWriteScopesMergeIndependently(){var a=CustomerAccess.defaults(Set.of("SALES","VIEWER"));assertThat(a.readAll()).isTrue();assertThat(a.writeAll()).isFalse();assertThat(a.write()).isTrue();}
 @Test void supportIsReadOnlyAndMasksByDefault(){var a=CustomerAccess.support(false,false);assertThat(a.readAll()).isTrue();assertThat(a.write()).isFalse();assertThat(a.creditRead()).isFalse();assertThat(a.descriptionRead()).isFalse();}
 @Test void renewalUsesLaterOfNowAndExpiry(){Instant now=Instant.parse("2026-01-15T00:00:00Z"),old=Instant.parse("2025-12-15T00:00:00Z");assertThat(SaasService.renewedEnd(old,now,1,"Asia/Shanghai")).isEqualTo(Instant.parse("2026-02-15T00:00:00Z"));}
 @Test void renewalClampsMonthEnd(){Instant old=Instant.parse("2026-01-31T00:00:00Z");assertThat(SaasService.renewedEnd(old,old.minusSeconds(1),1,"Asia/Shanghai")).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));}
 @Test void passwordsRespectUtf8BcryptLimit(){assertThatThrownBy(()->AuthService.validatePassword("密".repeat(25))).isInstanceOf(ApiError.class);assertThatCode(()->AuthService.validatePassword("A secure passphrase 2026")).doesNotThrowAnyException();}
 @Test void responseMapperDoesNotExposeCredentialDigests(){var v=SaasService.view(Map.of("password_hash","secret","token_hash","secret","csrf_token","secret","must_change_password",true));assertThat(v).containsOnlyKeys("mustChangePassword");}
}
