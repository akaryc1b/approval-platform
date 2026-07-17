package io.github.akaryc1b.approval.config;

import io.github.akaryc1b.approval.application.ApprovalApplicationService;
import io.github.akaryc1b.approval.engine.ApprovalEngine;
import io.github.akaryc1b.approval.engine.flowable.FlowableApprovalEngine;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApprovalPlatformConfiguration {

    @Bean
    ApprovalEngine approvalEngine(RuntimeService runtimeService, TaskService taskService) {
        return new FlowableApprovalEngine(runtimeService, taskService);
    }

    @Bean
    ApprovalApplicationService approvalApplicationService(ApprovalEngine approvalEngine) {
        return new ApprovalApplicationService(approvalEngine);
    }
}
