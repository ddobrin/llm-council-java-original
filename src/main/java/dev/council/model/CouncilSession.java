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

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Complete council deliberation session.
 */
public record CouncilSession(
    String id,
    String query,
    Instant createdAt,
    CouncilStage currentStage,
    List<IndividualResponse> individualResponses,
    List<IndividualRanking> individualRankings,
    Map<String, String> labelToModel,  // "Response A" -> "openai/gpt-5.1"
    List<AggregateRanking> aggregateRankings,
    List<IndividualAgreement> individualAgreements,
    List<AggregateAgreement> aggregateAgreements,
    List<IndividualDisagreement> individualDisagreements,
    List<AggregateDisagreement> aggregateDisagreements,
    ConsensusMetrics consensusMetrics,
    FinalResponse finalResponse
) {
    public enum CouncilStage {
        PENDING,
        INDIVIDUAL_RESPONSES_STAGE_IN_PROGRESS,
        INDIVIDUAL_RESPONSES_STAGE_COMPLETE,
        INDIVIDUAL_RANKING_STAGE_IN_PROGRESS,
        INDIVIDUAL_RANKING_STAGE_COMPLETE,
        INDIVIDUAL_AGREEMENT_STAGE_IN_PROGRESS,
        INDIVIDUAL_AGREEMENT_STAGE_COMPLETE,
        INDIVIDUAL_DISAGREEMENT_STAGE_IN_PROGRESS,
        INDIVIDUAL_DISAGREEMENT_STAGE_COMPLETE,
        FINAL_RESPONSE_STAGE_IN_PROGRESS,
        COMPLETE
    }
}
