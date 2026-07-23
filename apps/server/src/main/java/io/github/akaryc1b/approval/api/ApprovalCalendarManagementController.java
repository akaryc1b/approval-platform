package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalSlaService;
import io.github.akaryc1b.approval.application.ApprovalSlaService.ActivateCalendarCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.CalendarValidation;
import io.github.akaryc1b.approval.application.ApprovalSlaService.CreateCalendarCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.PublishCalendarCommand;
import io.github.akaryc1b.approval.application.ApprovalSlaService.SaveCalendarVersionCommand;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.DayOverride;
import io.github.akaryc1b.approval.application.ApprovalWorkingTimeCalculator.WorkingInterval;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarIdentity;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarPage;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.CalendarVersion;
import io.github.akaryc1b.approval.application.port.ApprovalSlaStore.SlaNotFoundException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/management/sla/calendars")
public class ApprovalCalendarManagementController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalSlaService service;

    public ApprovalCalendarManagementController(ApprovalSlaService service) {
        this.service = service;
    }

    @GetMapping
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarPage findCalendars(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return service.findCalendars(tenantId, limit, offset);
    }

    @GetMapping("/{calendarId}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarIdentity findCalendar(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID calendarId
    ) {
        return service.findCalendar(tenantId, calendarId)
            .orElseThrow(() -> new SlaNotFoundException(
                "APPROVAL_CALENDAR_NOT_FOUND",
                "work calendar was not found"
            ));
    }

    @GetMapping("/{calendarId}/versions/{calendarVersion}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_READ,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarVersion findCalendarVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID calendarId,
        @PathVariable @Min(1) int calendarVersion
    ) {
        return service.findCalendarVersion(tenantId, calendarId, calendarVersion)
            .orElseThrow(() -> new SlaNotFoundException(
                "APPROVAL_CALENDAR_NOT_FOUND",
                "work calendar version was not found"
            ));
    }

    @PostMapping
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarIdentity createCalendar(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @Valid @RequestBody CreateCalendarRequest request
    ) {
        return service.createCalendar(new CreateCalendarCommand(
            tenantId,
            request.calendarKey(),
            request.displayName(),
            request.timeZone(),
            operatorId
        ));
    }

    @PutMapping("/{calendarId}/versions/{calendarVersion}")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarVersion saveCalendarVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID calendarId,
        @PathVariable @Min(1) int calendarVersion,
        @Valid @RequestBody CalendarVersionRequest request
    ) {
        return service.saveCalendarVersion(command(
            tenantId,
            calendarId,
            calendarVersion,
            request
        ));
    }

    @PostMapping("/{calendarId}/versions/{calendarVersion}/validate")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_DESIGN,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarValidation validateCalendarVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @PathVariable UUID calendarId,
        @PathVariable @Min(1) int calendarVersion,
        @Valid @RequestBody CalendarVersionRequest request
    ) {
        return service.validateCalendar(command(
            tenantId,
            calendarId,
            calendarVersion,
            request
        ));
    }

    @PostMapping("/{calendarId}/versions/{calendarVersion}/publish")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_PUBLISH,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarVersion publishCalendarVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID calendarId,
        @PathVariable @Min(1) int calendarVersion,
        @Valid @RequestBody VersionTransitionRequest request
    ) {
        return service.publishCalendarVersion(new PublishCalendarCommand(
            tenantId,
            calendarId,
            calendarVersion,
            operatorId,
            request.expectedIdentityVersion()
        ));
    }

    @PostMapping("/{calendarId}/versions/{calendarVersion}/activate")
    @ApprovalManagementPermission(
        value = ApprovalManagementPermission.Requirement.SLA_ACTIVATE,
        resourceScope = ApprovalManagementPermission.ResourceScope.TENANT
    )
    public CalendarIdentity activateCalendarVersion(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID calendarId,
        @PathVariable @Min(1) int calendarVersion,
        @Valid @RequestBody VersionTransitionRequest request
    ) {
        return service.activateCalendarVersion(new ActivateCalendarCommand(
            tenantId,
            calendarId,
            calendarVersion,
            operatorId,
            request.expectedIdentityVersion()
        ));
    }

    private static SaveCalendarVersionCommand command(
        String tenantId,
        UUID calendarId,
        int calendarVersion,
        CalendarVersionRequest request
    ) {
        return new SaveCalendarVersionCommand(
            tenantId,
            calendarId,
            calendarVersion,
            request.timeZone(),
            request.effectiveFrom(),
            request.effectiveTo(),
            request.weeklySchedule(),
            request.overrides(),
            request.expectedIdentityVersion()
        );
    }

    public record CreateCalendarRequest(
        @NotBlank @Size(max = 100) String calendarKey,
        @NotBlank @Size(max = 200) String displayName,
        @NotBlank @Size(max = 100) String timeZone
    ) {
    }

    public record CalendarVersionRequest(
        @NotBlank @Size(max = 100) String timeZone,
        Instant effectiveFrom,
        Instant effectiveTo,
        @NotNull @Size(max = 7) Map<DayOfWeek, List<WorkingInterval>> weeklySchedule,
        @NotNull @Size(max = 20_000) Map<LocalDate, DayOverride> overrides,
        @Min(1) long expectedIdentityVersion
    ) {
        public CalendarVersionRequest {
            weeklySchedule = weeklySchedule == null ? Map.of() : Map.copyOf(weeklySchedule);
            overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
        }
    }

    public record VersionTransitionRequest(@Min(1) long expectedIdentityVersion) {
    }
}
