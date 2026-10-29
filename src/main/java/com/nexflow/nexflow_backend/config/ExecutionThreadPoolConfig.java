package com.nexflow.nexflow_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated thread pools so flow runs never compete with web requests or the JVM common pool.
 * Sized for a small (2 vCPU) server: flows mostly wait on I/O, so more threads than cores is fine.
 */
@Configuration
public class ExecutionThreadPoolConfig {

    /** Whole flow runs (one thread per running flow). */
    @Bean(name = "flowExecutionExecutor")
    public ThreadPoolTaskExecutor flowExecutionExecutor() {
        return pool("flow-exec-", 10, 30, 200);
    }

    /** FORK branches, kept separate so parallel branches never wait behind whole flow runs. */
    @Bean(name = "forkBranchExecutor")
    public ThreadPoolTaskExecutor forkBranchExecutor() {
        return pool("fork-branch-", 10, 40, 200);
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
