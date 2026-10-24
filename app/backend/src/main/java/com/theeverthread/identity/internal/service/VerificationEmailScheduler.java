package com.theeverthread.identity.internal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "theeverthread.registration.delivery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VerificationEmailScheduler {
    private final VerificationEmailWorker worker;

    public VerificationEmailScheduler(VerificationEmailWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${theeverthread.registration.delivery.delay:5000}")
    public void scheduledRun() {
        worker.runOnce();
    }
}
