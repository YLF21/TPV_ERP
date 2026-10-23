package com.tpverp.backend.servertransfer;

import java.time.*;
import java.util.concurrent.ScheduledFuture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Wrap the scheduled Runnable, including final and package-private scheduler beans. */
@Configuration
public class ServerTransferSchedulerGuard {
    @Bean(name="taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(ServerTransferFence fence,
            @Value("${spring.task.scheduling.pool.size:1}") int poolSize) {
        var scheduler=new ThreadPoolTaskScheduler() {
            private Runnable guard(Runnable task) {
                return () -> { if(!fence.enter())return; try{task.run();}finally{fence.leave();} };
            }
            @Override public ScheduledFuture<?> schedule(Runnable task,Trigger trigger){return super.schedule(guard(task),trigger);}
            @Override public ScheduledFuture<?> schedule(Runnable task,Instant start){return super.schedule(guard(task),start);}
            @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task,Instant start,Duration period){return super.scheduleAtFixedRate(guard(task),start,period);}
            @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task,Duration period){return super.scheduleAtFixedRate(guard(task),period);}
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Instant start,Duration delay){return super.scheduleWithFixedDelay(guard(task),start,delay);}
            @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task,Duration delay){return super.scheduleWithFixedDelay(guard(task),delay);}
        };
        scheduler.setPoolSize(poolSize);scheduler.setThreadNamePrefix("tpv-scheduled-");scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
