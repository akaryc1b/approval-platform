package io.github.akaryc1b.approval.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/platform")
public class PlatformInfoController {

    private final String applicationName;

    public PlatformInfoController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
            "name", applicationName,
            "status", "foundation",
            "engine", "flowable-8"
        );
    }
}
