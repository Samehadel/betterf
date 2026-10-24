package com.theeverthread.knowledge.internal.service;

import io.micrometer.core.instrument.MeterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ClassificationWorker {
    private final ClassificationTransactions transactions;
    private final KnowledgeAi ai;
    private final MeterRegistry metrics;

    public void runOnce() {
        for (int count = 0; count < 10; count++) {
            var work = transactions.claim();
            if (work == null) return;
            try {
                transactions.complete(work, ai.classify(work.content()));
                metrics.counter("theeverthread.knowledge.classification", "result", "complete")
                        .increment();
            } catch (RuntimeException exception) {
                transactions.fail(work);
                metrics.counter("theeverthread.knowledge.classification", "result", "failed_attempt")
                        .increment();
            }
        }
    }
}
