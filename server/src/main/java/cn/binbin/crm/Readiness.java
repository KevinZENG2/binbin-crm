package cn.binbin.crm;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class Readiness implements ApplicationListener<ApplicationReadyEvent> {
    private volatile boolean ready;
    public void onApplicationEvent(ApplicationReadyEvent event){ready=true;}
    public boolean ready(){return ready;}
}
