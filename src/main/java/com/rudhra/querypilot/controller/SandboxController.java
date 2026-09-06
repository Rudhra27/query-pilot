package com.rudhra.querypilot.controller;

import com.rudhra.querypilot.service.SandboxDatabaseService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SandboxController {

    private final SandboxDatabaseService sandboxDatabaseService;

    public SandboxController(SandboxDatabaseService sandboxDatabaseService) {
        this.sandboxDatabaseService = sandboxDatabaseService;
    }

    @GetMapping("/api/v1/sandbox/health")
    public ResponseEntity<Boolean> sandboxHealth() {
        return ResponseEntity.ok(sandboxDatabaseService.testAdminConnection());
    }

    @PostMapping("/api/v1/sandbox/reset")
    public ResponseEntity<String> resetSandbox() {

        sandboxDatabaseService.createFreshSandbox();

        return ResponseEntity.ok(
                "Sandbox created successfully."
        );
    }
    @GetMapping("/api/v1/sandbox/connection")
    public ResponseEntity<Boolean> testSandboxConnection() {

        return ResponseEntity.ok(
                sandboxDatabaseService.testSandboxConnection()
        );
    }
}