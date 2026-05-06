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

import dev.council.model.*;
import dev.council.service.CouncilService;
import dev.council.service.TraceRetrievalService;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.hilla.BrowserCallable;
import org.jspecify.annotations.NonNull;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hilla endpoint exposing the Council API to the React frontend.
 */

@BrowserCallable
@AnonymousAllowed
public class CouncilEndpoint {

    private final CouncilService councilService;
    private final Optional<TraceRetrievalService> traceRetrievalService;
    private final Duration sessionTimeout;
    private final Duration saveTimeout;

    public CouncilEndpoint(
            CouncilService councilService,
            Optional<TraceRetrievalService> traceRetrievalService,
            @org.springframework.beans.factory.annotation.Value("${council.endpoint.session-timeout:PT15S}") Duration sessionTimeout,
            @org.springframework.beans.factory.annotation.Value("${council.endpoint.save-timeout:PT1M}") Duration saveTimeout) {
        this.councilService = councilService;
        this.traceRetrievalService = traceRetrievalService;
        this.sessionTimeout = sessionTimeout;
        this.saveTimeout = saveTimeout;
    }

    @NonNull
    public List<@NonNull CouncilMember> getCouncilMembers() {
        return councilService.getCouncilMembers();
    }

    @NonNull
    public CouncilMember getChairman() {
        return councilService.getChairman();
    }

    @NonNull
    public CouncilSession createSession(@NonNull String query) {
        return councilService.createSession(query)
                .timeout(sessionTimeout)
                .block();
    }

    @NonNull
    public Flux<@NonNull IndividualResponse> runStage1(@NonNull String sessionId) {
        councilService.setDeliberationMode(sessionId, "manual");
        return councilService.runInitialStage(sessionId);
    }

    /**
     * Stage 2: Peer ranking. All data retrieved server-side from session state.
     */
    @NonNull
    public Flux<@NonNull IndividualRanking> runStage2(@NonNull String sessionId) {
        return councilService.runPeerRankingStage(sessionId);
    }

    /**
     * Stage 3: Agreement analysis. All data retrieved server-side from session state.
     */
    @NonNull
    public Flux<@NonNull IndividualAgreement> runStage3(@NonNull String sessionId) {
        return councilService.runAgreementAnalysis(sessionId);
    }

    /**
     * Stage 4: Disagreement analysis. All data retrieved server-side from session state.
     */
    @NonNull
    public Flux<@NonNull IndividualDisagreement> runStage4(@NonNull String sessionId) {
        return councilService.runDisagreementAnalysis(sessionId);
    }

    /**
     * Stage 5: Final synthesis. All data retrieved server-side from session state.
     * Returns Flux for consistency with stages 1-4 (no blocking).
     */
    @NonNull
    public Flux<@NonNull FinalResponse> runStage5(@NonNull String sessionId) {
        return councilService.runFinalSynthesis(sessionId);
    }

    /**
     * Get the authoritative label-to-model mapping from server-side session state.
     */
    @NonNull
    public Map<@NonNull String, @NonNull String> getLabelToModel(@NonNull String sessionId) {
        return councilService.getLabelToModel(sessionId);
    }

    /**
     * Calculate aggregate rankings. Data retrieved server-side from session state.
     */
    @NonNull
    public List<@NonNull AggregateRanking> calculateAggregateRankings(@NonNull String sessionId) {
        return councilService.calculateAggregateRankings(sessionId);
    }

    /**
     * Calculate aggregate agreements. Data retrieved server-side from session state.
     */
    @NonNull
    public List<@NonNull AggregateAgreement> calculateAggregateAgreements(@NonNull String sessionId) {
        return councilService.calculateAggregateAgreements(sessionId);
    }

    /**
     * Calculate aggregate disagreements. Data retrieved server-side from session state.
     */
    @NonNull
    public List<@NonNull AggregateDisagreement> calculateAggregateDisagreements(@NonNull String sessionId) {
        return councilService.calculateAggregateDisagreements(sessionId);
    }

    /**
     * Calculate consensus metrics (ranking consensus + disagreement severity).
     * Data retrieved server-side from session state.
     */
    @NonNull
    public ConsensusMetrics calculateConsensusMetrics(@NonNull String sessionId) {
        return councilService.calculateConsensusMetrics(sessionId);
    }

    /**
     * Save completed session. All data retrieved server-side from session state.
     * Prevents trust boundary violation by not accepting client-provided data.
     */
    @NonNull
    public SavedSession saveCompletedSession(@NonNull String sessionId) {
        return councilService.saveCompletedSessionFromCache(sessionId)
                .timeout(saveTimeout)
                .block();
    }

    @NonNull
    public List<@NonNull SavedSession> listSavedSessions() throws IOException {
        return councilService.listSavedSessions();
    }

    @NonNull
    public SavedSession loadSavedSession(@NonNull String filename) throws IOException {
        return councilService.loadSavedSession(filename);
    }

    @NonNull
    public List<@NonNull TraceSummary> getDeliberationTraces(@NonNull TraceDuration duration) {
        return traceRetrievalService
                .map(svc -> svc.getDeliberationTraces(duration))
                .orElse(List.of());
    }
}
