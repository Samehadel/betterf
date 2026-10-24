package com.theeverthread.knowledge.internal.service;

import java.util.List;

/** Stable catalog IDs are stored on tickets; creators never supply these selections. */
public final class AttributeCatalog {
    public static final List<String> ALL =
            List.of(
                    "type:knowledge",
                    "type:coding",
                    "type:problem",
                    "type:decision",
                    "type:accomplishment",
                    "type:learning",
                    "area:backend",
                    "area:frontend",
                    "area:database",
                    "area:infrastructure",
                    "area:security",
                    "area:testing",
                    "area:product",
                    "area:process",
                    "technology:java",
                    "technology:spring",
                    "technology:angular",
                    "technology:typescript",
                    "technology:javascript",
                    "technology:python",
                    "technology:sql",
                    "technology:postgresql",
                    "technology:docker",
                    "technology:other");

    private AttributeCatalog() {}

    public static List<String> validate(List<String> selections) {
        if (selections == null
                || selections.size() > ALL.size()
                || selections.stream().anyMatch(s -> !ALL.contains(s)))
            throw new IllegalArgumentException("Invalid classification selections");
        return selections.stream().distinct().toList();
    }
}
