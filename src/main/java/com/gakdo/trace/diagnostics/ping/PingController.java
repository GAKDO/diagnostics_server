package com.gakdo.trace.diagnostics.ping;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PingController {

    @GetMapping("/api/v1/diagnostics/ping")
    public Map<String, String> ping() {
        return Map.of("service", "diagnostics-server", "status", "ok");
    }
}
