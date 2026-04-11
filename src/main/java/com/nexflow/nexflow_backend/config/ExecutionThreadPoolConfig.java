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
        // This pool runs whole flow executions in the background.
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("flow-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated thread pool for FORK branch execution only.
     * Ensures branches run in parallel: core size is set so multiple branches
     * get threads at once (e.g. 3 branches = 3 threads), avoiding cumulative
     * runtimes when each branch has the same timeout.
     */
    @Bean(name = "forkBranchExecutor")
    public Executor forkBranchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // FORK branches use their own pool so parallel branches do not block the main flow pool.
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("fork-branch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

