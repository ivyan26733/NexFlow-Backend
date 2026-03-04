package com.nexflow.nexflow_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class ExecutionThreadPoolConfig {

    /**
     * Dedicated thread pool for flow execution.
     *
     * This avoids using the JVM's common ForkJoinPool, which can become
     * saturated under load and cause executions to remain in RUNNING state
     * without the engine ever starting.
     */
    @Bean(name = "flowExecutionExecutor")
    public Executor flowExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("flow-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

