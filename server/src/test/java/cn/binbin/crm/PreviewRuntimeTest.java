package cn.binbin.crm;

import static org.assertj.core.api.Assertions.*;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;

class PreviewRuntimeTest {
    @Test void persistentEnvironmentsDoNotAdvertiseAReviewDeadline() {
        assertThat(PreviewRuntime.describe(null)).isEmpty();
        assertThat(PreviewRuntime.describe("  ")).isEmpty();
    }

    @Test void reviewDeadlineHasAnUnambiguousNormalizedInstant() {
        assertThat(PreviewRuntime.describe("2026-10-29T16:00:00+08:00"))
            .containsEntry("expiresAt","2026-10-29T08:00:00Z")
            .containsEntry("ephemeral",true).containsEntry("syntheticDataOnly",true);
    }

    @Test void anInvalidDeadlineCannotSilentlyEnableAReview() {
        assertThatThrownBy(()->PreviewRuntime.describe("tomorrow"))
            .isInstanceOf(DateTimeParseException.class);
    }
}
