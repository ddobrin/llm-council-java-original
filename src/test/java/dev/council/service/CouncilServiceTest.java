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
package dev.council.service;

import dev.council.client.ChatClientRegistry;
import dev.council.model.*;
import dev.council.model.AggregateDisagreement.AggregatePosition;
import dev.council.model.IndividualAgreement.AgreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint.Position;
import dev.council.service.storage.ConversationStorage;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for CouncilService aggregate calculation methods.
 *
 * These methods are pure functions (no I/O, no reactive chains) making them
 * ideal for fast, isolated unit tests without a Spring context.
 */
class CouncilServiceTest {

    private CouncilService councilService;

    // Three council members corresponding to Response A, B, C labels
    private static final CouncilMember MEMBER_A =
            new CouncilMember("id-a", "Alpha", "OpenAI", "gpt-4o", "#FF0000");
    private static final CouncilMember MEMBER_B =
            new CouncilMember("id-b", "Beta", "Anthropic", "claude-3-sonnet", "#00FF00");
    private static final CouncilMember MEMBER_C =
            new CouncilMember("id-c", "Gamma", "Google", "gemini-pro", "#0000FF");

    private static final CouncilMember CHAIRMAN =
            new CouncilMember("id-chair", "Chairman", "Anthropic", "claude-3-opus", "#FFFFFF");
    private static final CouncilMember TITLE_MODEL =
            new CouncilMember("id-title", "Titler", "OpenAI", "gpt-4o-mini", "#AAAAAA");

    @BeforeEach
    void setUp() {
        councilService = new CouncilService(
                List.of(MEMBER_A, MEMBER_B, MEMBER_C),
                CHAIRMAN,
                TITLE_MODEL,
                mock(ChatClientRegistry.class),
                mock(ConversationStorage.class),
                mock(ResponseParserService.class),
                ObservationRegistry.NOOP,
                mock(Tracer.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateAggregateRankings
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("calculateAggregateRankings")
    class AggregateRankingTests {

        /** Convenience mapping mirroring Stage 1 label assignment order. */
        private final Map<String, String> labelToModel = Map.of(
                "Response A", MEMBER_A.modelId(),
                "Response B", MEMBER_B.modelId(),
                "Response C", MEMBER_C.modelId());

        private IndividualRanking ranking(String evaluatorModelId, List<String> order) {
            return new IndividualRanking(evaluatorModelId, evaluatorModelId + "-name",
                    "evaluation", order, 100L, 50L);
        }

        @Test
        @DisplayName("returns empty list for empty rankings input")
        void emptyRankings() {
            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    List.of(), labelToModel);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("single evaluator produces correct average rank of 1.0 for first-place response")
        void singleEvaluator() {
            IndividualRanking r = ranking(MEMBER_A.modelId(),
                    List.of("Response A", "Response B", "Response C"));

            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    List.of(r), labelToModel);

            assertThat(result).hasSize(3);
            AggregateRanking first = result.get(0);
            assertThat(first.label()).isEqualTo("Response A");
            assertThat(first.averageRank()).isEqualTo(1.0);
            assertThat(first.evaluationCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("results are sorted ascending by average rank (best rank first)")
        void sortedAscendingByAverageRank() {
            // All three evaluators agree on the same order: A > B > C
            List<IndividualRanking> rankings = List.of(
                    ranking(MEMBER_A.modelId(), List.of("Response A", "Response B", "Response C")),
                    ranking(MEMBER_B.modelId(), List.of("Response A", "Response B", "Response C")),
                    ranking(MEMBER_C.modelId(), List.of("Response A", "Response B", "Response C")));

            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    rankings, labelToModel);

            assertThat(result).extracting(AggregateRanking::label)
                    .containsExactly("Response A", "Response B", "Response C");
            assertThat(result).extracting(AggregateRanking::averageRank)
                    .containsExactly(1.0, 2.0, 3.0);
        }

        @Test
        @DisplayName("averages rank positions correctly across disagreeing evaluators")
        void averagesRankPositionsAcrossEvaluators() {
            // Evaluator 1: A=1, B=2, C=3
            // Evaluator 2: C=1, A=2, B=3
            // Expected averages: A=(1+2)/2=1.5, B=(2+3)/2=2.5, C=(3+1)/2=2.0
            List<IndividualRanking> rankings = List.of(
                    ranking(MEMBER_A.modelId(), List.of("Response A", "Response B", "Response C")),
                    ranking(MEMBER_B.modelId(), List.of("Response C", "Response A", "Response B")));

            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    rankings, labelToModel);

            // Sorted ascending: A(1.5), C(2.0), B(2.5)
            assertThat(result).extracting(AggregateRanking::label)
                    .containsExactly("Response A", "Response C", "Response B");
            assertThat(result.get(0).averageRank()).isEqualTo(1.5);
            assertThat(result.get(1).averageRank()).isEqualTo(2.0);
            assertThat(result.get(2).averageRank()).isEqualTo(2.5);
        }

        @Test
        @DisplayName("evaluation count reflects number of times each label was ranked")
        void evaluationCountReflectsRankingFrequency() {
            List<IndividualRanking> rankings = List.of(
                    ranking(MEMBER_A.modelId(), List.of("Response A", "Response B", "Response C")),
                    ranking(MEMBER_B.modelId(), List.of("Response B", "Response A", "Response C")),
                    ranking(MEMBER_C.modelId(), List.of("Response C", "Response A", "Response B")));

            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    rankings, labelToModel);

            assertThat(result).allSatisfy(r -> assertThat(r.evaluationCount()).isEqualTo(3));
        }

        @Test
        @DisplayName("resolves model name from council members list via modelId lookup")
        void resolvesModelNameFromCouncilMembers() {
            IndividualRanking r = ranking(MEMBER_A.modelId(), List.of("Response A"));

            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    List.of(r), Map.of("Response A", MEMBER_A.modelId()));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).modelId()).isEqualTo(MEMBER_A.modelId());
            assertThat(result.get(0).modelName()).isEqualTo(MEMBER_A.name());
        }

        @Test
        @DisplayName("falls back to label as modelName when label is absent from labelToModel map")
        void fallsBackToLabelAsModelNameWhenLabelMissing() {
            IndividualRanking r = ranking(MEMBER_A.modelId(), List.of("Response D"));

            List<AggregateRanking> result = councilService.calculateAggregateRankings(
                    List.of(r), Map.of());

            assertThat(result).hasSize(1);
            AggregateRanking agg = result.get(0);
            assertThat(agg.label()).isEqualTo("Response D");
            assertThat(agg.modelId()).isNull();
            assertThat(agg.modelName()).isEqualTo("Response D");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateAggregateAgreements
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("calculateAggregateAgreements")
    class AggregateAgreementTests {

        private IndividualAgreement agreement(String evaluatorId, List<AgreementPoint> points) {
            return new IndividualAgreement(evaluatorId, evaluatorId + "-name",
                    "analysis", points, 100L, 50L);
        }

        private AgreementPoint point(String topic, String description, String... responses) {
            return new AgreementPoint(topic, description, List.of(responses));
        }

        @Test
        @DisplayName("returns empty list for empty input")
        void emptyAgreements() {
            assertThat(councilService.calculateAggregateAgreements(List.of())).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when evaluator has no agreement points")
        void evaluatorWithNoPoints() {
            IndividualAgreement a = agreement(MEMBER_A.modelId(), List.of());

            assertThat(councilService.calculateAggregateAgreements(List.of(a))).isEmpty();
        }

        @Test
        @DisplayName("single evaluator produces mentionCount of 1 per topic")
        void singleEvaluatorSingleTopic() {
            IndividualAgreement a = agreement(MEMBER_A.modelId(),
                    List.of(point("Performance", "All agree on speed", "Response A", "Response B")));

            List<AggregateAgreement> result = councilService.calculateAggregateAgreements(List.of(a));

            assertThat(result).hasSize(1);
            AggregateAgreement agg = result.get(0);
            assertThat(agg.topic()).isEqualTo("Performance");
            assertThat(agg.description()).isEqualTo("All agree on speed");
            assertThat(agg.mentionCount()).isEqualTo(1);
            assertThat(agg.agreeingResponses()).containsExactlyInAnyOrder("Response A", "Response B");
        }

        @Test
        @DisplayName("same topic mentioned by multiple evaluators increments mentionCount")
        void sameTopic_multipleEvaluators_incrementsMentionCount() {
            IndividualAgreement a1 = agreement(MEMBER_A.modelId(),
                    List.of(point("Security", "Security is important", "Response A", "Response B")));
            IndividualAgreement a2 = agreement(MEMBER_B.modelId(),
                    List.of(point("Security", "Security matters", "Response B", "Response C")));

            List<AggregateAgreement> result = councilService.calculateAggregateAgreements(
                    List.of(a1, a2));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).mentionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("same topic from multiple evaluators merges agreeingResponses (deduped)")
        void sameTopic_mergesAndDeduplicatesAgreeingResponses() {
            IndividualAgreement a1 = agreement(MEMBER_A.modelId(),
                    List.of(point("Testing", "Tests matter", "Response A", "Response B")));
            IndividualAgreement a2 = agreement(MEMBER_B.modelId(),
                    List.of(point("Testing", "Tests matter", "Response B", "Response C")));

            List<AggregateAgreement> result = councilService.calculateAggregateAgreements(
                    List.of(a1, a2));

            // "Response B" appears in both but should not be duplicated
            assertThat(result.get(0).agreeingResponses())
                    .containsExactlyInAnyOrder("Response A", "Response B", "Response C");
        }

        @Test
        @DisplayName("distinct topics remain as separate AggregateAgreements")
        void distinctTopicsProduceSeparateEntries() {
            IndividualAgreement a = agreement(MEMBER_A.modelId(), List.of(
                    point("Performance", "Speed matters", "Response A"),
                    point("Security", "Safety first", "Response B")));

            List<AggregateAgreement> result = councilService.calculateAggregateAgreements(List.of(a));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(AggregateAgreement::topic)
                    .containsExactlyInAnyOrder("Performance", "Security");
        }

        @Test
        @DisplayName("results are sorted descending by mentionCount (highest agreement first)")
        void sortedDescendingByMentionCount() {
            // "Hot topic" mentioned by both evaluators, "Niche" only by one
            IndividualAgreement a1 = agreement(MEMBER_A.modelId(), List.of(
                    point("Hot topic", "Desc", "Response A"),
                    point("Niche", "Desc", "Response A")));
            IndividualAgreement a2 = agreement(MEMBER_B.modelId(), List.of(
                    point("Hot topic", "Desc", "Response B")));

            List<AggregateAgreement> result = councilService.calculateAggregateAgreements(
                    List.of(a1, a2));

            assertThat(result.get(0).topic()).isEqualTo("Hot topic");
            assertThat(result.get(0).mentionCount()).isEqualTo(2);
            assertThat(result.get(1).topic()).isEqualTo("Niche");
            assertThat(result.get(1).mentionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("description is taken from the first evaluator that mentions the topic")
        void descriptionFromFirstMention() {
            IndividualAgreement a1 = agreement(MEMBER_A.modelId(),
                    List.of(point("Topic", "First description", "Response A")));
            IndividualAgreement a2 = agreement(MEMBER_B.modelId(),
                    List.of(point("Topic", "Second description", "Response B")));

            List<AggregateAgreement> result = councilService.calculateAggregateAgreements(
                    List.of(a1, a2));

            assertThat(result.get(0).description()).isEqualTo("First description");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // calculateAggregateDisagreements
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("calculateAggregateDisagreements")
    class AggregateDisagreementTests {

        private IndividualDisagreement disagreement(String evaluatorId, List<DisagreementPoint> points) {
            return new IndividualDisagreement(evaluatorId, evaluatorId + "-name",
                    "analysis", points, 100L, 50L);
        }

        private DisagreementPoint point(String topic, String description, Position... positions) {
            return new DisagreementPoint(topic, description, List.of(positions));
        }

        private Position position(String responseLabel, String stance) {
            return new Position(responseLabel, stance);
        }

        @Test
        @DisplayName("returns empty list for empty input")
        void emptyDisagreements() {
            assertThat(councilService.calculateAggregateDisagreements(List.of())).isEmpty();
        }

        @Test
        @DisplayName("returns empty list when evaluator has no disagreement points")
        void evaluatorWithNoPoints() {
            IndividualDisagreement d = disagreement(MEMBER_A.modelId(), List.of());

            assertThat(councilService.calculateAggregateDisagreements(List.of(d))).isEmpty();
        }

        @Test
        @DisplayName("single evaluator produces mentionCount of 1 per topic")
        void singleEvaluatorSingleTopic() {
            DisagreementPoint p = point("Database",
                    "Different DB preferences",
                    position("Response A", "PostgreSQL"),
                    position("Response B", "MongoDB"));

            IndividualDisagreement d = disagreement(MEMBER_A.modelId(), List.of(p));

            List<AggregateDisagreement> result = councilService.calculateAggregateDisagreements(List.of(d));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).topic()).isEqualTo("Database");
            assertThat(result.get(0).mentionCount()).isEqualTo(1);
            assertThat(result.get(0).positions()).hasSize(2);
        }

        @Test
        @DisplayName("same topic from multiple evaluators increments mentionCount")
        void sameTopic_incrementsMentionCount() {
            DisagreementPoint p1 = point("Framework", "Different frameworks",
                    position("Response A", "React"), position("Response B", "Vue"));
            DisagreementPoint p2 = point("Framework", "Framework choice varies",
                    position("Response A", "React"), position("Response C", "Angular"));

            List<AggregateDisagreement> result = councilService.calculateAggregateDisagreements(List.of(
                    disagreement(MEMBER_A.modelId(), List.of(p1)),
                    disagreement(MEMBER_B.modelId(), List.of(p2))));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).mentionCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("same stance from multiple evaluators consolidates responseLabels under one AggregatePosition")
        void sameStance_consolidatesResponseLabels() {
            // Both evaluators report "React" as Response A's stance on "Framework"
            DisagreementPoint p1 = point("Framework", "Desc",
                    position("Response A", "React"));
            DisagreementPoint p2 = point("Framework", "Desc",
                    position("Response A", "React"), position("Response B", "Vue"));

            List<AggregateDisagreement> result = councilService.calculateAggregateDisagreements(List.of(
                    disagreement(MEMBER_A.modelId(), List.of(p1)),
                    disagreement(MEMBER_B.modelId(), List.of(p2))));

            AggregateDisagreement agg = result.get(0);
            AggregatePosition reactPosition = agg.positions().stream()
                    .filter(pos -> pos.stance().equals("React"))
                    .findFirst()
                    .orElseThrow();

            // "Response A" appears in both evaluators' data for this stance but should not be duplicated
            assertThat(reactPosition.responseLabels()).containsOnlyOnce("Response A");
        }

        @Test
        @DisplayName("distinct topics remain as separate AggregateDisagreements")
        void distinctTopicsProduceSeparateEntries() {
            IndividualDisagreement d = disagreement(MEMBER_A.modelId(), List.of(
                    point("Database", "DB pref", position("Response A", "PostgreSQL")),
                    point("Language", "Lang pref", position("Response B", "TypeScript"))));

            List<AggregateDisagreement> result = councilService.calculateAggregateDisagreements(List.of(d));

            assertThat(result).hasSize(2);
            assertThat(result).extracting(AggregateDisagreement::topic)
                    .containsExactlyInAnyOrder("Database", "Language");
        }

        @Test
        @DisplayName("results are sorted descending by mentionCount (most-discussed disagreements first)")
        void sortedDescendingByMentionCount() {
            DisagreementPoint hotPoint = point("Hot", "Desc",
                    position("Response A", "Option 1"), position("Response B", "Option 2"));
            DisagreementPoint nichePoint = point("Niche", "Desc",
                    position("Response A", "Option X"));

            List<AggregateDisagreement> result = councilService.calculateAggregateDisagreements(List.of(
                    disagreement(MEMBER_A.modelId(), List.of(hotPoint, nichePoint)),
                    disagreement(MEMBER_B.modelId(), List.of(hotPoint))));

            assertThat(result.get(0).topic()).isEqualTo("Hot");
            assertThat(result.get(0).mentionCount()).isEqualTo(2);
            assertThat(result.get(1).topic()).isEqualTo("Niche");
            assertThat(result.get(1).mentionCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("init() — storage mode parsing")
    class StorageModeInitTests {

        @Test
        @DisplayName("accepts 'local' (lowercase)")
        void acceptsLocalLowercase() {
            councilService.conversationStorageLocation = "local";
            councilService.init(); // should not throw
        }

        @Test
        @DisplayName("accepts 'LOCAL' (uppercase) — case-insensitive")
        void acceptsLocalUppercase() {
            councilService.conversationStorageLocation = "LOCAL";
            councilService.init();
        }

        @Test
        @DisplayName("accepts 'gcs'")
        void acceptsGcsLowercase() {
            councilService.conversationStorageLocation = "gcs";
            councilService.init();
        }

        @Test
        @DisplayName("accepts 'GCS'")
        void acceptsGcsUppercase() {
            councilService.conversationStorageLocation = "GCS";
            councilService.init();
        }

        @Test
        @DisplayName("rejects unknown value with IllegalStateException")
        void rejectsUnknownValue() {
            councilService.conversationStorageLocation = "azure";
            IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, councilService::init);
            assertThat(ex.getMessage()).contains("azure").contains("LOCAL").contains("GCS");
        }

        @Test
        @DisplayName("rejects empty string with IllegalStateException")
        void rejectsEmpty() {
            councilService.conversationStorageLocation = "";
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, councilService::init);
        }

        @Test
        @DisplayName("rejects null with IllegalStateException")
        void rejectsNull() {
            councilService.conversationStorageLocation = null;
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, councilService::init);
        }

        @Test
        @DisplayName("rejects 'localcoffee' (prevents prefix-match false positive)")
        void rejectsLocalPrefixOnly() {
            councilService.conversationStorageLocation = "localcoffee";
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, councilService::init);
        }
    }
}
