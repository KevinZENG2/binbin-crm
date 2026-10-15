package cn.binbin.crm;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
    private final NamedParameterJdbcTemplate db;private final AuthService auth;private final ObjectStore storage;private final NotificationService jobs;private final DataSource source;private final P3Jobs p3;
    public OperationsService(NamedParameterJdbcTemplate db,AuthService auth,ObjectStore storage,NotificationService jobs,DataSource source,P3Jobs p3){this.db=db;this.auth=auth;this.storage=storage;this.jobs=jobs;this.source=source;this.p3=p3;}
    public Map<String,Object> status(AuthService.Session s){auth.platform(s);long started=System.nanoTime();db.queryForObject("SELECT 1",Map.of(),Integer.class);var result=new LinkedHashMap<String,Object>();result.put("version","0.11.0");result.put("checkedAt",Instant.now());result.put("database",Map.of("status","UP","latencyMs",(System.nanoTime()-started)/1000000));result.put("storage",Map.of("configured",storage.configured(),"healthy",storage.healthy()));result.put("reminderScheduleEnabled",jobs.enabled());result.put("p3ScheduleEnabled",p3.enabled());var vm=ManagementFactory.getRuntimeMXBean();result.put("uptimeSeconds",vm.getUptime()/1000);var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();result.put("heap",Map.of("usedBytes",heap.getUsed(),"maxBytes",heap.getMax()));if(source instanceof HikariDataSource hikari&&hikari.getHikariPoolMXBean()!=null){var p=hikari.getHikariPoolMXBean();result.put("connections",Map.of("active",p.getActiveConnections(),"idle",p.getIdleConnections(),"waiting",p.getThreadsAwaitingConnection()));}
        // Frequent reminder jobs must not push the latest backup/retention result
        // out of the operator's view. Only sanitized task metadata is returned.
        result.put("jobs",db.queryForList("SELECT id,job_name,status,started_at,finished_at,processed_count,error_code,artifact_sha256,artifact_bytes,(artifact_key IS NOT NULL) AS artifact_available FROM sys_job_run WHERE id IN ((SELECT id FROM sys_job_run ORDER BY started_at DESC,id LIMIT 50) UNION (SELECT DISTINCT ON (job_name) id FROM sys_job_run WHERE job_name IN ('DATABASE_BACKUP','BACKUP_RETENTION') ORDER BY job_name,started_at DESC,id)) ORDER BY started_at DESC,id LIMIT 52",Map.of()).stream().map(SaasService::view).toList());return result;
    }
}
