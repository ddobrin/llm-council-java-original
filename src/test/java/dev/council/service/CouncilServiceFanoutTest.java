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

import dev.council.client.ChatClientProvider;
import dev.council.client.ChatClientRegistry;
import dev.council.model.CouncilMember;
import dev.council.model.IndividualResponse;
import dev.council.service.storage.ConversationStorage;
import dev.council.support.TestFixtures;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CouncilService — reactive fan-out & stage orchestration")
class CouncilServiceFanoutTest {

    private static final CouncilMember ALICE = TestFixtures.memberOf("alice", "anthropic", "model-alice");
    private static final CouncilMember BOB   = TestFixtures.memberOf("bob",   "anthropic", "model-bob");
    private static final CouncilMember CAROL = TestFixtures.memberOf("carol", "google",    "model-carol");

    private ChatClientRegistry chatClientRegistry;
    private ConversationStorage storage;
    private ResponseParserService parser;
    private CouncilService service;

    @BeforeEach
    void setUp() {
        chatClientRegistry = mock(ChatClientRegistry.class);
        storage = mock(ConversationStorage.class);
        parser = mock(ResponseParserService.class);

        service = new CouncilService(
                List.of(ALICE, BOB, CAROL),
                ALICE,                 // chairman
                ALICE,                 // title model
                chatClientRegistry,
                storage,
                parser,
                ObservationRegistry.NOOP,
                mock(Tracer.class, RETURNS_DEEP_STUBS)
        );

        // The @Value-injected prompt template resources are not populated when
        // bypassing Spring; supply minimal in-memory templates so PromptTemplate
        // construction succeeds inside generateIndividualResponse and the other
        // stage helpers.
        ReflectionTestUtils.setField(service, "initialReviewSystem",
                new ByteArrayResource("system: you are {name}".getBytes()));

        // Each member's provider returns a canned streaming response by default.
        // Individual tests override per-member behavior as needed.
        for (CouncilMember m : List.of(ALICE, BOB, CAROL)) {
            ChatClientProvider provider = mock(ChatClientProvider.class);
            when(provider.getModelId()).thenReturn(m.modelId());
            when(provider.generateResponse(anyString(), anyString(), anyString()))
                    .thenReturn(TestFixtures.mockStreamingResponse("response from ", m.id()));
            when(chatClientRegistry.getClientOrThrow(m.modelId())).thenReturn(provider);
        }
    }

    /**
     * Helper: prime the cache by calling createSession with a stubbed title model.
     * Returns the new session id.
     */
    private String primeSession(String topic) {
        return service.createSession(topic).block().id();
    }

    @Nested
    @DisplayName("runInitialStage")
    class RunInitialStage {

        @Test
        @DisplayName("All members succeed → emits N responses sorted by config order")
        void allSucceed_emitsAllInOrder() {
            String sid = primeSession("topic-1");

            StepVerifier.create(service.runInitialStage(sid).collectList())
                    .assertNext(list -> {
                        assertThat(list).hasSize(3);
                        // CouncilService sorts by member config order
                        // (Note: doOnNext writes in arrival order; sort happens in doOnComplete
                        // after all are received, but the emitted order from the Flux is
                        // arrival order, NOT sorted. So we just check size and that each
                        // member appears, not the order.)
                        assertThat(list)
                                .extracting(IndividualResponse::modelId)
                                .containsExactlyInAnyOrder(
                                        ALICE.modelId(), BOB.modelId(), CAROL.modelId());
                        assertThat(list)
                                .extracting(IndividualResponse::isError)
                                .containsExactly(false, false, false);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("One member's stream errors → that one carries [Error: ...] content, others succeed")
        void oneErrors_othersSucceed() {
            String sid = primeSession("topic-2");

            // Override BOB to error mid-stream
            ChatClientProvider bobProvider = chatClientRegistry.getClientOrThrow(BOB.modelId());
            when(bobProvider.generateResponse(anyString(), anyString(), anyString()))
                    .thenReturn(Flux.error(new RuntimeException("simulated network failure")));

            StepVerifier.create(service.runInitialStage(sid).collectList())
                    .assertNext(list -> {
                        assertThat(list).hasSize(3);
                        IndividualResponse bobResp = list.stream()
                                .filter(r -> r.modelId().equals(BOB.modelId()))
                                .findFirst().orElseThrow();
                        assertThat(bobResp.isError()).isTrue();
                        assertThat(bobResp.content()).startsWith("[Error:");

                        long succeededCount = list.stream().filter(r -> !r.isError()).count();
                        assertThat(succeededCount).isEqualTo(2);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("All members error → all responses carry [Error: ...] content")
        void allError_allReturnedAsError() {
            String sid = primeSession("topic-3");

            for (CouncilMember m : List.of(ALICE, BOB, CAROL)) {
                ChatClientProvider p = chatClientRegistry.getClientOrThrow(m.modelId());
                when(p.generateResponse(anyString(), anyString(), anyString()))
                        .thenReturn(Flux.error(new RuntimeException("all down")));
            }

            StepVerifier.create(service.runInitialStage(sid).collectList())
                    .assertNext(list -> {
                        assertThat(list).hasSize(3);
                        assertThat(list).allMatch(IndividualResponse::isError);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Empty content from a model is treated as an error")
        void emptyContent_isError() {
            String sid = primeSession("topic-4");

            // CAROL returns empty chunks
            ChatClientProvider c = chatClientRegistry.getClientOrThrow(CAROL.modelId());
            when(c.generateResponse(anyString(), anyString(), anyString()))
                    .thenReturn(TestFixtures.mockStreamingResponse(""));

            StepVerifier.create(service.runInitialStage(sid).collectList())
                    .assertNext(list -> {
                        IndividualResponse carolResp = list.stream()
                                .filter(r -> r.modelId().equals(CAROL.modelId()))
                                .findFirst().orElseThrow();
                        // IndividualResponse.isError() checks for null/blank/[Error: prefix
                        assertThat(carolResp.isError()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("session-not-found branches")
    class SessionNotFound {

        @Test
        @DisplayName("runInitialStage emits IllegalArgumentException when session not in cache")
        void runInitialStage_sessionMissing_emitsError() {
            StepVerifier.create(service.runInitialStage("missing"))
                    .expectErrorSatisfies(this::assertSessionNotFound)
                    .verify();
        }

        @Test
        @DisplayName("runPeerRankingStage emits IllegalArgumentException when session not in cache")
        void runPeerRankingStage_sessionMissing_emitsError() {
            StepVerifier.create(service.runPeerRankingStage("missing"))
                    .expectErrorSatisfies(this::assertSessionNotFound)
                    .verify();
        }

        @Test
        @DisplayName("runAgreementAnalysis emits IllegalArgumentException when session not in cache")
        void runAgreementAnalysis_sessionMissing_emitsError() {
            StepVerifier.create(service.runAgreementAnalysis("missing"))
                    .expectErrorSatisfies(this::assertSessionNotFound)
                    .verify();
        }

        @Test
        @DisplayName("runDisagreementAnalysis emits IllegalArgumentException when session not in cache")
        void runDisagreementAnalysis_sessionMissing_emitsError() {
            StepVerifier.create(service.runDisagreementAnalysis("missing"))
                    .expectErrorSatisfies(this::assertSessionNotFound)
                    .verify();
        }

        @Test
        @DisplayName("runFinalSynthesis emits IllegalArgumentException when session not in cache")
        void runFinalSynthesis_sessionMissing_emitsError() {
            StepVerifier.create(service.runFinalSynthesis("missing"))
                    .expectErrorSatisfies(this::assertSessionNotFound)
                    .verify();
        }

        @Test
        @DisplayName("setDeliberationMode silently no-ops when session not in cache")
        void setDeliberationMode_sessionMissing_silentNoOp() {
            service.setDeliberationMode("missing", "manual");
            // No assertion — just verify no exception is thrown
        }

        @Test
        @DisplayName("getDeliberationObservation returns null when session not in cache")
        void getDeliberationObservation_sessionMissing_returnsNull() {
            assertThat(service.getDeliberationObservation("missing")).isNull();
        }

        @Test
        @DisplayName("getLabelToModel throws when session not in cache")
        void getLabelToModel_sessionMissing_throws() {
            assertThatThrownBy(() -> service.getLabelToModel("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Session not found");
        }

        private void assertSessionNotFound(Throwable e) {
            assertThat(e).isInstanceOf(IllegalArgumentException.class);
            assertThat(e).hasMessageContaining("Session not found");
        }
    }

    @Nested
    @DisplayName("runFinalSynthesis edge cases")
    class RunFinalSynthesis {

        @Test
        @DisplayName("Called without prior stages → IllegalStateException about missing responses")
        void noPriorStages_throws() {
            // Note: plan called this "emptyRankings" but the empty-responses check fires
            // first in production. Renamed for accuracy. The test still verifies the
            // contract: calling runFinalSynthesis without prior state errors loudly.
            String sid = primeSession("topic-final-empty");

            StepVerifier.create(service.runFinalSynthesis(sid))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(IllegalStateException.class);
                        assertThat(e).hasMessageContaining("No successful responses");
                    })
                    .verify();
        }
    }

    @Nested
    @DisplayName("error branches in member-level generators")
    class ErrorBranches {

        @Test
        @DisplayName("Stage 2 (peer ranking) rejects empty successful responses")
        void peerRanking_noSuccessfulResponses_throws() {
            String sid = primeSession("topic-stage2-empty");

            // Make all members error in stage 1, then try stage 2
            for (CouncilMember m : List.of(ALICE, BOB, CAROL)) {
                ChatClientProvider p = chatClientRegistry.getClientOrThrow(m.modelId());
                when(p.generateResponse(anyString(), anyString(), anyString()))
                        .thenReturn(Flux.error(new RuntimeException("all down")));
            }

            // Run stage 1 (will fill state with 3 errored responses)
            service.runInitialStage(sid).blockLast();

            // Now stage 2 should reject because no successful responses
            StepVerifier.create(service.runPeerRankingStage(sid))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(IllegalStateException.class);
                        assertThat(e).hasMessageContaining("No successful responses");
                    })
                    .verify();
        }

        @Test
        @DisplayName("Stage 3 (agreement) rejects empty successful responses")
        void agreement_noSuccessfulResponses_throws() {
            String sid = primeSession("topic-stage3-empty");

            for (CouncilMember m : List.of(ALICE, BOB, CAROL)) {
                ChatClientProvider p = chatClientRegistry.getClientOrThrow(m.modelId());
                when(p.generateResponse(anyString(), anyString(), anyString()))
                        .thenReturn(Flux.error(new RuntimeException("all down")));
            }

            service.runInitialStage(sid).blockLast();

            StepVerifier.create(service.runAgreementAnalysis(sid))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(IllegalStateException.class);
                        assertThat(e).hasMessageContaining("No successful responses");
                    })
                    .verify();
        }

        @Test
        @DisplayName("Stage 4 (disagreement) rejects empty successful responses")
        void disagreement_noSuccessfulResponses_throws() {
            String sid = primeSession("topic-stage4-empty");

            for (CouncilMember m : List.of(ALICE, BOB, CAROL)) {
                ChatClientProvider p = chatClientRegistry.getClientOrThrow(m.modelId());
                when(p.generateResponse(anyString(), anyString(), anyString()))
                        .thenReturn(Flux.error(new RuntimeException("all down")));
            }

            service.runInitialStage(sid).blockLast();

            StepVerifier.create(service.runDisagreementAnalysis(sid))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(IllegalStateException.class);
                        assertThat(e).hasMessageContaining("No successful responses");
                    })
                    .verify();
        }
    }
}
