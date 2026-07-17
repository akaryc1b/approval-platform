package io.github.akaryc1b.approval.ruoyi6.host;

import org.dromara.common.security.config.properties.SecurityProperties;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

final class Ruoyi6SecurityExclusionPostProcessor implements BeanPostProcessor {

    static final String CONNECTOR_PATH_PATTERN = "/api/approval-connector/v1/**";

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof SecurityProperties properties)) {
            return bean;
        }
        Set<String> excludes = new LinkedHashSet<>();
        if (properties.getExcludes() != null) {
            excludes.addAll(Arrays.asList(properties.getExcludes()));
        }
        excludes.add(CONNECTOR_PATH_PATTERN);
        properties.setExcludes(excludes.toArray(String[]::new));
        return bean;
    }
}
