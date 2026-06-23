package ru.algaagro.maxapp.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService botExecutorService() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setName("max-long-polling");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService importExecutorService() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r);
            thread.setName("import-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
