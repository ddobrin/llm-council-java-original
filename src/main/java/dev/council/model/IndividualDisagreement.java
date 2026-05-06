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
 * Stage 4: Disagreement analysis from an evaluator model.
 * Identifies points where responses diverge on key topics.
 */
public record IndividualDisagreement(
    String evaluatorModelId,
    String evaluatorModelName,
    String analysis,
    List<DisagreementPoint> disagreements,
    long durationMs,
    long totalTokens
) {
    /**
     * A specific point of disagreement across responses.
     */
    public record DisagreementPoint(
        String topic,
        String description,
        List<Position> positions
    ) {
        /**
         * A position taken by a response on a disagreement point.
         */
        public record Position(
            String responseLabel,
            String stance
        ) {}
    }
}
