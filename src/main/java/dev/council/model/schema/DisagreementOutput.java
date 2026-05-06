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
package dev.council.model.schema;

import java.util.List;

/**
 * JSON schema DTO for LLM disagreement analysis output.
 * Used with BeanOutputConverter to request structured JSON from LLMs.
 *
 * This record contains only the fields the LLM should produce - no metadata
 * like duration, tokens, or evaluator IDs which are added by the service layer.
 */
public record DisagreementOutput(
    String analysis,
    List<DivergencePoint> divergencePoints
) {
    /**
     * A point where responses diverge.
     */
    public record DivergencePoint(
        String topic,
        String description,
        List<ResponsePosition> positions
    ) {
        /**
         * A response's position on a divergence point.
         */
        public record ResponsePosition(
            String responseLabel,
            String stance
        ) {}
    }
}
