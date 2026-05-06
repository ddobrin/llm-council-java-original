/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.council.model;

import java.util.List;

/**
 * Aggregated disagreement point across all evaluators.
 * Consolidates disagreement points from Stage 4 by topic.
 */
public record AggregateDisagreement(
    String topic,
    String description,
    List<AggregatePosition> positions,
    int mentionCount
) implements Comparable<AggregateDisagreement> {

    /**
     * Aggregated position showing which responses share a stance.
     */
    public record AggregatePosition(
        String stance,
        List<String> responseLabels
    ) {}

    @Override
    public int compareTo(AggregateDisagreement other) {
        // Sort by mention count descending
        return Integer.compare(other.mentionCount, this.mentionCount);
    }
}
