package com.loopers.batch.job.reconcile;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@ConditionalOnProperty(name = "spring.batch.job.name", havingValue = OrderEventReconcileJobConfig.JOB_NAME)
@Configuration
public class OrderEventReconcileJobConfig {
    public static final String JOB_NAME = "orderEventReconcileJob";
    private static final String STEP_NAME = "orderEventReconcileStep";

    @Bean(JOB_NAME)
    public Job orderEventReconcileJob(JobRepository jobRepository, Step orderEventReconcileStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(orderEventReconcileStep)
            .build();
    }

    @JobScope
    @Bean(STEP_NAME)
    public Step orderEventReconcileStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
        ReconcileProperties reconcileProperties) {
        return new StepBuilder(STEP_NAME, jobRepository)
            .<MissingOrder, MissingOrder>chunk(reconcileProperties.chunkSize(), transactionManager)
            .reader(() -> null)
            .writer(chunk -> { })
            .build();
    }
}
