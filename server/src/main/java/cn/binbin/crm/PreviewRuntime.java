package cn.binbin.crm;

import java.time.Instant;
import java.util.Map;

/** Optional lifecycle metadata for an explicitly configured temporary review. */
final class PreviewRuntime {
    private PreviewRuntime() {}

    static Map<String,Object> describe(String configuredUntil) {
        if(configuredUntil==null||configuredUntil.isBlank())return Map.of();
        var until=Instant.parse(configuredUntil.trim());
        return Map.of("expiresAt",until.toString(),"ephemeral",true,"syntheticDataOnly",true);
    }
}
