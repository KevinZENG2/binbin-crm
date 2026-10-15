package cn.binbin.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class CrmApplication {
    public static void main(String[] args) { SpringApplication.run(CrmApplication.class, args); }
}
