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
import dev.council.service.CouncilService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * REST API for programmatic access to the council deliberation flow.
 * Complements the Hilla @BrowserCallable endpoint used by the UI.
 */
@RestController
@RequestMapping("/api/council")
public class CouncilApiController {

    private static final Logger log = LoggerFactory.getLogger(CouncilApiController.class);

    private final CouncilService councilService;
    private final Duration totalTimeout;

    public CouncilApiController(
            CouncilService councilService,
            @org.springframework.beans.factory.annotation.Value("${council.api.timeout:PT5M}") Duration totalTimeout) {
        this.councilService = councilService;
        this.totalTimeout = totalTimeout;
    }

    /**
     * Run a full council deliberation: all 5 stages + aggregation + save.
     * Returns the same SavedSession JSON that the UI saves to disk.
     */
    @PostMapping("/consult")
    public ResponseEntity<SavedSession> consult(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("API council consult request: {}", query);

        SavedSession result = councilService.createSession(query)
                .flatMap(session -> {
                    String sessionId = session.id();
                    councilService.setDeliberationMode(sessionId, "api");

                    // Flux.defer / Mono.defer ensures each stage method is called at
                    // subscription time (after the previous stage completes), not during
                    // chain assembly — the service methods do eager cache checks that
                    // would fail if invoked before prior stages populate the cache.
                    return councilService.runInitialStage(sessionId).collectList()
                            .then(Flux.defer(() -> councilService.runPeerRankingStage(sessionId)).collectList())
                            .then(Flux.defer(() -> councilService.runAgreementAnalysis(sessionId)).collectList())
                            .then(Flux.defer(() -> councilService.runDisagreementAnalysis(sessionId)).collectList())
                            .then(Flux.defer(() -> councilService.runFinalSynthesis(sessionId)).collectList())
                            .then(Mono.defer(() -> councilService.saveCompletedSessionFromCache(sessionId)));
                })
                .timeout(totalTimeout)
                .block();

        return ResponseEntity.ok(result);
    }

    /**
     * Run a quick council deliberation: stages 1, 2, 5 only (skipping agreement
     * and disagreement analysis). Returns the same SavedSession JSON structure
     * with empty agreement/disagreement fields.
     */
    @PostMapping("/quick-consult")
    public ResponseEntity<SavedSession> quickConsult(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        log.info("API council quick-consult request: {}", query);

        SavedSession result = councilService.createSession(query)
                .flatMap(session -> {
                    String sessionId = session.id();
                    councilService.setDeliberationMode(sessionId, "api-quick");

                    return councilService.runInitialStage(sessionId).collectList()
                            .then(Flux.defer(() -> councilService.runPeerRankingStage(sessionId)).collectList())
                            .then(Flux.defer(() -> councilService.runFinalSynthesis(sessionId)).collectList())
                            .then(Mono.defer(() -> councilService.saveCompletedSessionFromCache(sessionId)));
                })
                .timeout(totalTimeout)
                .block();

        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception e) {
        log.error("Council API error", e);
        return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
    }
}
