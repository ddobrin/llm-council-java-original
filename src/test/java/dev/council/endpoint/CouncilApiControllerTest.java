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

import dev.council.model.SavedSession;
import dev.council.model.CouncilSession;
import dev.council.service.CouncilService;
import dev.council.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("CouncilApiController — REST surface")
class CouncilApiControllerTest {

    private CouncilService councilService;
    private CouncilApiController controller;

    @BeforeEach
    void setUp() {
        councilService = mock(CouncilService.class);
        controller = new CouncilApiController(councilService, Duration.ofMinutes(5));
    }

    private CouncilSession sampleSession() {
        return TestFixtures.sessionFor("550e8400-e29b-41d4-a716-446655440099", "Topic");
    }

    private SavedSession sampleSaved() {
        return new SavedSession("Topic", "topic-550e8400.json", sampleSession());
    }

    private void stubFullDeliberationChain() {
        when(councilService.createSession(anyString())).thenReturn(Mono.just(sampleSession()));
        when(councilService.runInitialStage(anyString())).thenReturn(Flux.empty());
        when(councilService.runPeerRankingStage(anyString())).thenReturn(Flux.empty());
        when(councilService.runAgreementAnalysis(anyString())).thenReturn(Flux.empty());
        when(councilService.runDisagreementAnalysis(anyString())).thenReturn(Flux.empty());
        when(councilService.runFinalSynthesis(anyString())).thenReturn(Flux.empty());
        when(councilService.saveCompletedSessionFromCache(anyString()))
                .thenReturn(Mono.just(sampleSaved()));
    }

    @Nested
    @DisplayName("POST /api/council/consult")
    class Consult {

        @Test
        @DisplayName("empty query → 400, no service interaction")
        void emptyQuery_returns400() {
            ResponseEntity<SavedSession> response = controller.consult(Map.of("query", ""));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(councilService);
        }

        @Test
        @DisplayName("missing query field → 400")
        void missingQuery_returns400() {
            ResponseEntity<SavedSession> response = controller.consult(Map.of());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(councilService);
        }

        @Test
        @DisplayName("whitespace-only query → 400")
        void whitespaceQuery_returns400() {
            ResponseEntity<SavedSession> response = controller.consult(Map.of("query", "   "));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(councilService);
        }

        @Test
        @DisplayName("null query value → 400")
        void nullQueryValue_returns400() {
            // Map.of doesn't allow nulls; use HashMap
            Map<String, String> body = new HashMap<>();
            body.put("query", null);
            ResponseEntity<SavedSession> response = controller.consult(body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(councilService);
        }

        @Test
        @DisplayName("valid query → 200 with SavedSession body")
        void validQuery_returns200WithSavedSession() {
            stubFullDeliberationChain();

            ResponseEntity<SavedSession> response = controller.consult(Map.of("query", "hello"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().title()).isEqualTo("Topic");
            verify(councilService).createSession("hello");
        }

        @Test
        @DisplayName("Flux.defer chain — all 5 stages invoked in order, then save")
        void stagesInvokedInOrder() {
            stubFullDeliberationChain();

            controller.consult(Map.of("query", "hello"));

            InOrder ord = inOrder(councilService);
            ord.verify(councilService).createSession("hello");
            ord.verify(councilService).runInitialStage(anyString());
            ord.verify(councilService).runPeerRankingStage(anyString());
            ord.verify(councilService).runAgreementAnalysis(anyString());
            ord.verify(councilService).runDisagreementAnalysis(anyString());
            ord.verify(councilService).runFinalSynthesis(anyString());
            ord.verify(councilService).saveCompletedSessionFromCache(anyString());
        }

        @Test
        @DisplayName("service hangs → externalized timeout fires, throws TimeoutException")
        void serviceHangs_timeoutFires() {
            // Use a fresh controller with very short timeout
            CouncilApiController fast = new CouncilApiController(councilService, Duration.ofMillis(100));
            when(councilService.createSession("hello")).thenReturn(Mono.never());

            long start = System.currentTimeMillis();
            assertThatThrownBy(() -> fast.consult(Map.of("query", "hello")))
                    .satisfies(ex -> {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        assertThat(cause).isInstanceOf(TimeoutException.class);
                    });
            assertThat(System.currentTimeMillis() - start)
                    .as("timeout should fire fast").isLessThan(5000);
        }
    }

    @Nested
    @DisplayName("POST /api/council/quick-consult")
    class QuickConsult {

        @Test
        @DisplayName("valid query → 200; agreement and disagreement stages NOT invoked")
        void validQuery_skipsAgreementAndDisagreement() {
            stubFullDeliberationChain();

            ResponseEntity<SavedSession> response = controller.quickConsult(Map.of("query", "hello"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(councilService).runInitialStage(anyString());
            verify(councilService).runPeerRankingStage(anyString());
            verify(councilService).runFinalSynthesis(anyString());
            verify(councilService, never()).runAgreementAnalysis(anyString());
            verify(councilService, never()).runDisagreementAnalysis(anyString());
        }

        @Test
        @DisplayName("empty query → 400, no service interaction")
        void emptyQuery_returns400() {
            ResponseEntity<SavedSession> response = controller.quickConsult(Map.of("query", ""));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(councilService);
        }
    }

    @Nested
    @DisplayName("@ExceptionHandler methods")
    class ExceptionHandlers {

        @Test
        @DisplayName("handleBadRequest → 400 with error message in body")
        void handleBadRequest_returns400WithMessage() {
            ResponseEntity<Map<String, String>> response =
                    controller.handleBadRequest(new IllegalArgumentException("bad input"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "bad input");
        }

        @Test
        @DisplayName("handleError → 500 with error message in body")
        void handleError_returns500WithMessage() {
            ResponseEntity<Map<String, String>> response =
                    controller.handleError(new RuntimeException("boom"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("error", "boom");
        }
    }
}
