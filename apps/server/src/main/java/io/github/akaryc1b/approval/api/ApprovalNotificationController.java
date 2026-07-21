package io.github.akaryc1b.approval.api;

import io.github.akaryc1b.approval.application.ApprovalNotificationService;
import io.github.akaryc1b.approval.application.ApprovalNotificationService.ReadAllResult;
import io.github.akaryc1b.approval.application.ApprovalNotificationService.UnreadCount;
import io.github.akaryc1b.approval.application.ApprovalNotificationService.UpdatePreferencesCommand;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.DeliveryAttempt;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationChannel;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationEventType;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationHistoryPage;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationIntent;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.NotificationPreference;
import io.github.akaryc1b.approval.application.port.ApprovalNotificationStore.PreferenceBundle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/notifications")
public class ApprovalNotificationController {

    private static final String TENANT_ID = "X-Tenant-Id";
    private static final String OPERATOR_ID = "X-Operator-Id";

    private final ApprovalNotificationService service;

    public ApprovalNotificationController(ApprovalNotificationService service) {
        this.service = service;
    }

    @GetMapping("/preferences")
    public PreferenceBundle findPreferences(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId
    ) {
        return service.findPreferences(tenantId, operatorId);
    }

    @PutMapping("/preferences")
    public PreferenceBundle updatePreferences(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @Valid @RequestBody UpdatePreferencesRequest request
    ) {
        return service.updatePreferences(new UpdatePreferencesCommand(
            tenantId,
            operatorId,
            request.timezone(),
            request.quietHoursEnabled(),
            request.quietHoursStart(),
            request.quietHoursEnd(),
            request.emergencyBypass(),
            request.digestEnabled(),
            request.expectedVersion(),
            request.preferences().stream().map(PreferenceRequest::preference).toList()
        ));
    }

    @GetMapping
    public NotificationHistoryPage findHistory(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @RequestParam(defaultValue = "false") boolean unreadOnly,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(defaultValue = "0") @Min(0) int offset
    ) {
        return service.findHistory(tenantId, operatorId, unreadOnly, limit, offset);
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId
    ) {
        return service.unreadCount(tenantId, operatorId);
    }

    @PostMapping("/{intentId}/read")
    public ResponseEntity<NotificationIntent> markRead(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID intentId
    ) {
        return service.markRead(tenantId, operatorId, intentId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/read-all")
    public ReadAllResult markAllRead(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId
    ) {
        return service.markAllRead(tenantId, operatorId);
    }

    @GetMapping("/{intentId}/attempts")
    public List<DeliveryAttempt> findAttempts(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID intentId
    ) {
        return service.findAttempts(tenantId, operatorId, intentId);
    }

    @PostMapping("/{intentId}/replay")
    public NotificationIntent replay(
        @RequestHeader(TENANT_ID) String tenantId,
        @RequestHeader(OPERATOR_ID) String operatorId,
        @PathVariable UUID intentId
    ) {
        return service.replay(tenantId, operatorId, intentId);
    }

    public record UpdatePreferencesRequest(
        @NotBlank @Size(max = 128) String timezone,
        boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        boolean emergencyBypass,
        boolean digestEnabled,
        @Min(0) long expectedVersion,
        @NotNull @Size(max = 24) List<@Valid PreferenceRequest> preferences
    ) {
        public UpdatePreferencesRequest {
            preferences = preferences == null ? List.of() : List.copyOf(preferences);
        }
    }

    public record PreferenceRequest(
        @NotNull NotificationEventType eventType,
        @NotNull NotificationChannel channel,
        boolean enabled
    ) {
        NotificationPreference preference() {
            return new NotificationPreference(eventType, channel, enabled);
        }
    }
}
