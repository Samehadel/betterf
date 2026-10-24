package com.theeverthread.knowledge.internal.service;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "theeverthread.knowledge.classification.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ClassificationScheduler {
    private final ClassificationWorker worker;

    @Scheduled(fixedDelayString = "${theeverthread.knowledge.classification.delay:5000}")
    public void scheduledRun() {
        worker.runOnce();
    }
}
