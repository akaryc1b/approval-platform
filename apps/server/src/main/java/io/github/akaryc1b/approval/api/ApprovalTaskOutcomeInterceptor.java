package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalTaskOutcomeContext;
import io.github.akaryc1b.approval.application.port.ApprovalTaskCompletionGuard.TaskOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Binds approve/reject/resubmit HTTP actions to the projection-level completion gate. */
public final class ApprovalTaskOutcomeInterceptor implements HandlerInterceptor {

    private static final Pattern TASK_ACTION = Pattern.compile(
        ".*/api/approval/tasks/[0-9a-fA-F-]{36}/(approve|reject|resubmit)$"
    );

    private final ApprovalTaskOutcomeContext outcomes;

    public ApprovalTaskOutcomeInterceptor(ApprovalTaskOutcomeContext outcomes) {
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
    }

    @Override
    public boolean preHandle(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler
    ) {
        outcomes.clear();
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Matcher matcher = TASK_ACTION.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            return true;
        }
        outcomes.set(TaskOutcome.valueOf(
            matcher.group(1).toUpperCase(Locale.ROOT) + ("resubmit".equals(matcher.group(1))
                ? "TED"
                : "D")
        ));
        return true;
    }

    @Override
    public void afterCompletion(
        HttpServletRequest request,
        HttpServletResponse response,
        Object handler,
        Exception exception
    ) {
        outcomes.clear();
    }
}
