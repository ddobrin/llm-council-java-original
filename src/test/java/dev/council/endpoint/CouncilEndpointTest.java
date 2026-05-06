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
package dev.council.endpoint;

import dev.council.model.CouncilMember;
import dev.council.model.CouncilSession;
import dev.council.model.SavedSession;
import dev.council.model.TraceDuration;
import dev.council.model.TraceSummary;
import dev.council.service.CouncilService;
import dev.council.service.TraceRetrievalService;
import dev.council.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CouncilEndpoint — Hilla browser-callable endpoint")
class CouncilEndpointTest {

    private static final Duration FAST_SESSION_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration FAST_SAVE_TIMEOUT = Duration.ofMinutes(1);

    private CouncilService councilService;
    private TraceRetrievalService traceService;

    @BeforeEach
    void setUp() {
        councilService = mock(CouncilService.class);
        traceService = mock(TraceRetrievalService.class);
    }

    private CouncilEndpoint endpointWithTrace() {
        return new CouncilEndpoint(
                councilService, Optional.of(traceService),
                FAST_SESSION_TIMEOUT, FAST_SAVE_TIMEOUT);
    }

    private CouncilEndpoint endpointWithoutTrace() {
        return new CouncilEndpoint(
                councilService, Optional.empty(),
                FAST_SESSION_TIMEOUT, FAST_SAVE_TIMEOUT);
    }

    @Test
    @DisplayName("getCouncilMembers and getChairman are pure passthrough delegates")
    void passthroughDelegates_membersAndChairman() {
        CouncilMember alice = TestFixtures.memberOf("alice", "anthropic", "model-alice");
        when(councilService.getCouncilMembers()).thenReturn(List.of(alice));
        when(councilService.getChairman()).thenReturn(alice);

        CouncilEndpoint endpoint = endpointWithTrace();

        assertThat(endpoint.getCouncilMembers()).containsExactly(alice);
        assertThat(endpoint.getChairman()).isSameAs(alice);
    }

    @Test
    @DisplayName("createSession unwraps the Mono and returns the session")
    void createSession_unwrapsMono() {
        CouncilSession session = TestFixtures.sessionFor(
                "550e8400-e29b-41d4-a716-446655440099", "Topic");
        when(councilService.createSession("hello")).thenReturn(Mono.just(session));

        CouncilEndpoint endpoint = endpointWithTrace();

        CouncilSession result = endpoint.createSession("hello");
        assertThat(result.id()).isEqualTo(session.id());
    }

    @Test
    @DisplayName("runStage1 calls setDeliberationMode FIRST, then runInitialStage")
    void runStage1_setsModeBeforeRunning() {
        when(councilService.runInitialStage(anyString())).thenReturn(Flux.empty());

        CouncilEndpoint endpoint = endpointWithTrace();
        endpoint.runStage1("session-1").blockLast();

        InOrder ord = inOrder(councilService);
        ord.verify(councilService).setDeliberationMode("session-1", "manual");
        ord.verify(councilService).runInitialStage("session-1");
    }

    @Test
    @DisplayName("runStage2/3/4/5 are pure passthrough delegates")
    void runStages_passthrough() {
        when(councilService.runPeerRankingStage(anyString())).thenReturn(Flux.empty());
        when(councilService.runAgreementAnalysis(anyString())).thenReturn(Flux.empty());
        when(councilService.runDisagreementAnalysis(anyString())).thenReturn(Flux.empty());
        when(councilService.runFinalSynthesis(anyString())).thenReturn(Flux.empty());

        CouncilEndpoint endpoint = endpointWithTrace();

        endpoint.runStage2("s").blockLast();
        endpoint.runStage3("s").blockLast();
        endpoint.runStage4("s").blockLast();
        endpoint.runStage5("s").blockLast();

        verify(councilService).runPeerRankingStage("s");
        verify(councilService).runAgreementAnalysis("s");
        verify(councilService).runDisagreementAnalysis("s");
        verify(councilService).runFinalSynthesis("s");
    }

    @Test
    @DisplayName("getDeliberationTraces with Optional.empty() returns empty list, no NPE")
    void getDeliberationTraces_emptyOptional_returnsEmpty() {
        CouncilEndpoint endpoint = endpointWithoutTrace();

        List<TraceSummary> result = endpoint.getDeliberationTraces(TraceDuration.LAST_24_HOURS);
        assertThat(result).isEmpty();
        verifyNoInteractions(traceService);
    }

    @Test
    @DisplayName("getDeliberationTraces with Optional.of() delegates to service")
    void getDeliberationTraces_presentOptional_delegates() {
        when(traceService.getDeliberationTraces(TraceDuration.LAST_24_HOURS))
                .thenReturn(List.of());

        CouncilEndpoint endpoint = endpointWithTrace();
        endpoint.getDeliberationTraces(TraceDuration.LAST_24_HOURS);

        verify(traceService).getDeliberationTraces(TraceDuration.LAST_24_HOURS);
    }

    @Test
    @DisplayName("createSession timeout fires when service hangs")
    void createSession_serviceHangs_timeoutFires() {
        when(councilService.createSession("hello")).thenReturn(Mono.never());

        CouncilEndpoint endpoint = new CouncilEndpoint(
                councilService, Optional.empty(),
                Duration.ofMillis(100), FAST_SAVE_TIMEOUT);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> endpoint.createSession("hello"))
                .isInstanceOf(RuntimeException.class);
        assertThat(System.currentTimeMillis() - start).isLessThan(5000);
    }

    @Test
    @DisplayName("saveCompletedSession delegates to service and returns SavedSession")
    void saveCompletedSession_delegates() {
        CouncilSession session = TestFixtures.sessionFor(
                "550e8400-e29b-41d4-a716-446655440099", "Topic");
        SavedSession saved = new SavedSession("Topic", "topic.json", session);
        when(councilService.saveCompletedSessionFromCache("s"))
                .thenReturn(Mono.just(saved));

        CouncilEndpoint endpoint = endpointWithTrace();

        SavedSession result = endpoint.saveCompletedSession("s");
        assertThat(result).isSameAs(saved);
    }

    @Test
    @DisplayName("listSavedSessions and loadSavedSession are pure passthrough delegates")
    void savedSessionDelegates() throws Exception {
        when(councilService.listSavedSessions()).thenReturn(List.of());
        when(councilService.loadSavedSession("topic.json"))
                .thenReturn(new SavedSession("Topic", "topic.json",
                        TestFixtures.sessionFor("550e8400-e29b-41d4-a716-446655440099", "Topic")));

        CouncilEndpoint endpoint = endpointWithTrace();

        endpoint.listSavedSessions();
        endpoint.loadSavedSession("topic.json");

        verify(councilService).listSavedSessions();
        verify(councilService).loadSavedSession("topic.json");
    }
}
