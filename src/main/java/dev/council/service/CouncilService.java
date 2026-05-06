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
import dev.council.model.CouncilSession.CouncilStage;
import dev.council.model.IndividualAgreement.AgreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint;
import dev.council.model.IndividualDisagreement.DisagreementPoint.Position;
import dev.council.model.AggregateDisagreement.AggregatePosition;
import dev.council.service.storage.ConversationStorage;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Council service that orchestrates LLM interactions for the deliberation
 * process.
 */
@Service
public class CouncilService {

    private static final Logger log = LoggerFactory.getLogger(CouncilService.class);

    /**
     * Server-side session state holder. Stores all intermediate results to prevent
     * trust boundary violations where clients could inject fabricated data.
     *
     * Thread-safety: Uses synchronized collections for concurrent writes from
     * parallel
     * Flux emissions, and volatile for single-assignment fields.
     */
    private static class SessionState {
        private final String sessionId;
        private final String query;
        private final List<IndividualResponse> individualResponses = Collections.synchronizedList(new ArrayList<>());
        private final List<IndividualRanking> individualRankings = Collections.synchronizedList(new ArrayList<>());
        private final List<IndividualAgreement> individualAgreements = Collections.synchronizedList(new ArrayList<>());
        private final List<IndividualDisagreement> individualDisagreements = Collections
                .synchronizedList(new ArrayList<>());
        private final Map<String, String> labelToModel = Collections.synchronizedMap(new LinkedHashMap<>());
        private volatile FinalResponse finalResponse;
        private final String traceId;
        private final String deliberationSpanId;
        private volatile Span deliberationSpan;
        private volatile Observation deliberationObservation;

        SessionState(String sessionId, String query, String traceId, String deliberationSpanId, Span deliberationSpan) {
            this.sessionId = sessionId;
            this.query = query;
            this.traceId = traceId;
            this.deliberationSpanId = deliberationSpanId;
            this.deliberationSpan = deliberationSpan;
        }

        String getSessionId() {
            return sessionId;
        }

        String getQuery() {
            return query;
        }

        List<IndividualResponse> getIndividualResponses() {
            return individualResponses;
        }

        List<IndividualRanking> getIndividualRankings() {
            return individualRankings;
        }

        List<IndividualAgreement> getIndividualAgreements() {
            return individualAgreements;
        }

        List<IndividualDisagreement> getIndividualDisagreements() {
            return individualDisagreements;
        }

        Map<String, String> getLabelToModel() {
            return labelToModel;
        }

        FinalResponse getFinalResponse() {
            return finalResponse;
        }

        void setFinalResponse(FinalResponse response) {
            this.finalResponse = response;
        }

        String getTraceId() {
            return traceId;
        }

        String getDeliberationSpanId() {
            return deliberationSpanId;
        }

        Span getDeliberationSpan() {
            return deliberationSpan;
        }

        void setDeliberationSpan(Span span) {
            this.deliberationSpan = span;
        }

        Observation getDeliberationObservation() {
            return deliberationObservation;
        }

        void setDeliberationObservation(Observation observation) {
            this.deliberationObservation = observation;
        }
    }

    private final List<CouncilMember> councilMembers;
    private final CouncilMember chairman;
    private final CouncilMember titleModel;
    private final ChatClientRegistry chatClientRegistry;
    private final ConversationStorage conversationStorage;
    private final ResponseParserService parserService;
    private final ObservationRegistry observationRegistry;
    private final Tracer tracer;
    private volatile Cache<String, SessionState> activeSessions;

    /** Resolved at startup from {@code conversation.storage.location}. */
    private enum StorageMode { LOCAL, GCS }

    private StorageMode storageMode;

    // system prompts
    @Value("classpath:system/individual-review.st")
    Resource initialReviewSystem;

    @Value("classpath:system/peer-ranking.st")
    Resource peerRankingSystem;

    @Value("classpath:system/agreement-analysis.st")
    Resource agreementAnalysisSystem;

    @Value("classpath:system/disagreement-analysis.st")
    Resource disagreementAnalysisSystem;

    @Value("classpath:system/final-response.st")
    Resource finalResponseSystem;

    @Value("classpath:system/title-generation.st")
    Resource titleGenerationSystem;

    @Value("${conversation.storage.location}")
    String conversationStorageLocation;

    /**
     * Validates and parses the configured storage mode at startup.
     * Package-private for direct invocation from unit tests that bypass
     * Spring's lifecycle.
     */
    @PostConstruct
    void init() {
        if (conversationStorageLocation == null || conversationStorageLocation.isBlank()) {
            throw new IllegalStateException(
                    "conversation.storage.location must be set to one of: LOCAL, GCS");
        }
        try {
            this.storageMode = StorageMode.valueOf(
                    conversationStorageLocation.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid conversation.storage.location: '" + conversationStorageLocation
                            + "'. Must be one of: LOCAL, GCS", e);
        }
        log.info("Conversation storage mode: {}", storageMode);
    }

    public CouncilService(List<CouncilMember> councilMembers, CouncilMember chairmanModel,
            CouncilMember titleModel, ChatClientRegistry chatClientRegistry,
            ConversationStorage conversationStorage, ResponseParserService parserService,
            ObservationRegistry observationRegistry, Tracer tracer) {
        this.councilMembers = councilMembers;
        this.chairman = chairmanModel;
        this.titleModel = titleModel;
        this.chatClientRegistry = chatClientRegistry;
        this.conversationStorage = conversationStorage;
        this.parserService = parserService;
        this.observationRegistry = observationRegistry;
        this.tracer = tracer;

        log.info("CouncilService initialized with {} council members", councilMembers.size());
    }

    /**
     * Lazily creates the session cache on first access, deferring Caffeine's
     * internal class loading (SSLMSW via Class.forName) out of the startup path.
     */
    private Cache<String, SessionState> getActiveSessions() {
        if (activeSessions == null) {
            synchronized (this) {
                if (activeSessions == null) {
                    activeSessions = Caffeine.newBuilder()
                            .maximumSize(1000)
                            .expireAfterWrite(Duration.ofHours(2))
                            .removalListener((String key, SessionState value, RemovalCause cause) -> {
                                if (value != null) {
                                    if (value.getDeliberationObservation() != null) {
                                        value.getDeliberationObservation().stop();
                                    }
                                    if (value.getDeliberationSpan() != null) {
                                        value.getDeliberationSpan().setStatus(StatusCode.OK, "Session removed: " + cause);
                                        value.getDeliberationSpan().end();
                                    }
                                }
                                if (cause.wasEvicted()) {
                                    log.debug("Session evicted: {} (cause: {})", key, cause);
                                }
                            })
                            .build();
                }
            }
        }
        return activeSessions;
    }

    public List<CouncilMember> getCouncilMembers() {
        return councilMembers;
    }

    public CouncilMember getChairman() {
        return chairman;
    }

    /**
     * Get the deliberation observation for a session (for endpoint-level tracing).
     */
    public Observation getDeliberationObservation(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        return state != null ? state.getDeliberationObservation() : null;
    }

    /**
     * Set the deliberation mode on the root span (e.g. "manual" or "supervised").
     */
    public void setDeliberationMode(String sessionId, String mode) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state != null && state.getDeliberationSpan() != null) {
            state.getDeliberationSpan().setAttribute("council.deliberation.mode", mode);
        }
    }

    /**
     * Creates a new council session.
     */
    public Mono<CouncilSession> createSession(String query) {
        return Mono.fromCallable(() -> {
            // Create the OTel span for trace ID generation
            Span deliberationSpan = tracer.spanBuilder("council.deliberation")
                    .setSpanKind(SpanKind.INTERNAL)
                    .startSpan();

            String sessionId = UUID.randomUUID().toString();
            deliberationSpan.setAttribute("council.session.id", sessionId);

            deliberationSpan.setAttribute("council.member.count", councilMembers.size());
            deliberationSpan.setAttribute("council.chairman.model", chairman.modelId());
            deliberationSpan.setAttribute("council.members", councilMembers.stream().map(CouncilMember::modelId).collect(Collectors.joining(",")));
            deliberationSpan.setAttribute("council.query.length.chars", query.length());
            deliberationSpan.setAttribute("council.query.length.words", query.trim().split("\\s+").length);

            String traceId = deliberationSpan.getSpanContext().getTraceId();
            String spanId = deliberationSpan.getSpanContext().getSpanId();
            log.info("Created deliberation trace: traceId={}, spanId={} for session {}", traceId, spanId, sessionId);

            // Create a Micrometer Observation within the span's context
            Context parentContext = Context.current().with(deliberationSpan);
            Observation deliberationObs;
            try (Scope scope = parentContext.makeCurrent()) {
                deliberationObs = Observation.createNotStarted("council.deliberation.wrapper", observationRegistry)
                        .lowCardinalityKeyValue("council.session.id", sessionId);
                deliberationObs.start();
            }
            // Note: deliberationObs stays open - it's the parent for all stages

            // Store both the span and observation in SessionState
            SessionState state = new SessionState(sessionId, query, traceId, spanId, deliberationSpan);
            state.setDeliberationObservation(deliberationObs);

            // Create child observation for session creation
            try (Scope scope = parentContext.makeCurrent()) {
                return Observation.createNotStarted("council.session.create", observationRegistry)
                        .parentObservation(deliberationObs)
                        .lowCardinalityKeyValue("council.stage", "create")
                        .observe(() -> {
                            CouncilSession session = new CouncilSession(
                                    sessionId, query, Instant.now(), CouncilStage.PENDING,
                                    List.of(), List.of(), Map.of(), List.of(),
                                    List.of(), List.of(), List.of(), List.of(), null, null);
                            getActiveSessions().put(sessionId, state);
                            log.info("Created council session {} for query: {}", sessionId, query);
                            return session;
                        });
            }
        });
    }

    /**
     * Stage 1: Collect individual responses from all council members.
     * Responses are stored server-side to prevent client manipulation.
     */
    public Flux<IndividualResponse> runInitialStage(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        String query = state.getQuery();
        log.info("Starting Initial Stage for session {} with {} council members", sessionId, councilMembers.size());

        return Flux.defer(() -> {
            Observation parentObs = state.getDeliberationObservation();
            Observation observation = Observation.createNotStarted("council.stage.individual-review", observationRegistry)
                    .lowCardinalityKeyValue("council.stage", "1")
                    .highCardinalityKeyValue("council.session.id", sessionId);
            if (parentObs != null) {
                observation.parentObservation(parentObs);
            }
            observation.start();

            return Flux.fromIterable(councilMembers)
                    .flatMap(member -> generateIndividualResponse(member, query, sessionId), councilMembers.size())
                    .doOnNext(response -> {
                        state.getIndividualResponses().add(response);
                        log.debug("Individual Responses Stage emitting: {} for session {}", response.modelName(),
                                sessionId);
                    })
                    .doOnComplete(() -> {
                        // Sort responses by councilMembers config order for deterministic labels
                        Map<String, Integer> memberOrder = new HashMap<>();
                        for (int i = 0; i < councilMembers.size(); i++) {
                            memberOrder.put(councilMembers.get(i).modelId(), i);
                        }
                        state.getIndividualResponses().sort(
                            Comparator.comparingInt(r -> memberOrder.getOrDefault(r.modelId(), Integer.MAX_VALUE))
                        );

                        List<IndividualResponse> successfulResponses = state.getIndividualResponses().stream()
                                .filter(r -> !r.isError())
                                .toList();
                        long errorCount = state.getIndividualResponses().size() - successfulResponses.size();
                        if (errorCount > 0) {
                            log.warn("Session {}: {} of {} responses were errors and will be excluded from downstream analysis",
                                    sessionId, errorCount, state.getIndividualResponses().size());
                        }
                        observation.highCardinalityKeyValue("council.stage.error.count", String.valueOf(errorCount));
                        long stageTokenTotal = state.getIndividualResponses().stream().mapToLong(IndividualResponse::totalTokens).sum();
                        observation.highCardinalityKeyValue("council.stage.tokens.total", String.valueOf(stageTokenTotal));

                        Map<String, String> mapping = createLabelMapping(successfulResponses);
                        state.getLabelToModel().putAll(mapping);
                        log.info("Individual Responses Stage COMPLETED for session {}, {} responses collected ({} successful)",
                                sessionId, state.getIndividualResponses().size(), successfulResponses.size());
                    })
                    .doOnError(observation::error)
                    .doOnTerminate(() -> log.info("Individual Responses Stage TERMINATED for session {}", sessionId))
                    .doFinally(signal -> {
                        observation.stop();
                        log.info("Individual Responses Stage FINALLY signal: {} for session {}", signal, sessionId);
                    })
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    /**
     * Stage 2: Each model reviews and ranks all responses anonymously.
     * Stage 1 responses are retrieved server-side to prevent client manipulation.
     */
    public Flux<IndividualRanking> runPeerRankingStage(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        List<IndividualResponse> individualResponses = state.getIndividualResponses().stream()
                .filter(r -> !r.isError())
                .toList();
        if (individualResponses.isEmpty()) {
            return Flux.error(new IllegalStateException(
                    "No successful responses available for Peer Ranking Stage"));
        }

        String userQuery = state.getQuery();
        String responsesText = buildResponsesText(individualResponses);

        log.info("Starting Peer Ranking Stage for session {} with {} council members ({} successful responses)",
                sessionId, councilMembers.size(), individualResponses.size());

        return Flux.defer(() -> {
            Observation parentObs = state.getDeliberationObservation();
            Observation observation = Observation.createNotStarted("council.stage.peer-ranking", observationRegistry)
                    .lowCardinalityKeyValue("council.stage", "2")
                    .highCardinalityKeyValue("council.session.id", sessionId);
            if (parentObs != null) {
                observation.parentObservation(parentObs);
            }
            observation.start();

            return Flux.fromIterable(councilMembers)
                    .flatMap(member -> generatePeerRankings(member, userQuery, responsesText, sessionId),
                            councilMembers.size())
                    .doOnNext(ranking -> {
                        state.getIndividualRankings().add(ranking);
                        log.debug("Individual Ranking Stage emitting: {} for session {}", ranking.evaluatorModelName(),
                                sessionId);
                    })
                    .doOnComplete(() -> {
                        long stageTokenTotal = state.getIndividualRankings().stream().mapToLong(IndividualRanking::totalTokens).sum();
                        observation.highCardinalityKeyValue("council.stage.tokens.total", String.valueOf(stageTokenTotal));
                        long stageErrorCount = state.getIndividualRankings().stream().filter(r -> r.ranking().isEmpty()).count();
                        observation.highCardinalityKeyValue("council.stage.error.count", String.valueOf(stageErrorCount));
                        log.info("Individual Ranking Stage COMPLETED for session {}, {} rankings collected",
                                sessionId, state.getIndividualRankings().size());
                    })
                    .doOnError(observation::error)
                    .doOnTerminate(() -> log.info("Individual Ranking Stage TERMINATED for session {}", sessionId))
                    .doFinally(signal -> {
                        observation.stop();
                        log.info("Individual Ranking Stage FINALLY signal: {} for session {}", signal, sessionId);
                    })
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    /**
     * Stage 3: Models identify where responses AGREE.
     * Stage 1 responses are retrieved server-side to prevent client manipulation.
     */
    public Flux<IndividualAgreement> runAgreementAnalysis(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        List<IndividualResponse> individualResponses = state.getIndividualResponses().stream()
                .filter(r -> !r.isError())
                .toList();
        if (individualResponses.isEmpty()) {
            return Flux.error(new IllegalStateException(
                    "No successful responses available for Agreement Analysis"));
        }

        String userQuery = state.getQuery();
        String responsesText = buildResponsesText(individualResponses);

        log.info("Starting Agreement Analysis for session {} with {} council members ({} successful responses)",
                sessionId, councilMembers.size(), individualResponses.size());

        return Flux.defer(() -> {
            Observation parentObs = state.getDeliberationObservation();
            Observation observation = Observation.createNotStarted("council.stage.agreement-analysis", observationRegistry)
                    .lowCardinalityKeyValue("council.stage", "3")
                    .highCardinalityKeyValue("council.session.id", sessionId);
            if (parentObs != null) {
                observation.parentObservation(parentObs);
            }
            observation.start();

            return Flux.fromIterable(councilMembers)
                    .flatMap(member -> generateAgreementPoints(member, userQuery, responsesText, sessionId),
                            councilMembers.size())
                    .doOnNext(agreement -> {
                        state.getIndividualAgreements().add(agreement);
                        log.debug("Individual Agreement Stage emitting: {} for session {}", agreement.evaluatorModelName(),
                                sessionId);
                    })
                    .doOnComplete(() -> {
                        long stageTokenTotal = state.getIndividualAgreements().stream().mapToLong(IndividualAgreement::totalTokens).sum();
                        observation.highCardinalityKeyValue("council.stage.tokens.total", String.valueOf(stageTokenTotal));
                        long stageErrorCount = state.getIndividualAgreements().stream().filter(a -> a.agreements().isEmpty()).count();
                        observation.highCardinalityKeyValue("council.stage.error.count", String.valueOf(stageErrorCount));
                        log.info("Individual Agreement Stage COMPLETED for session {}, {} agreements collected",
                                sessionId, state.getIndividualAgreements().size());
                    })
                    .doOnError(observation::error)
                    .doOnTerminate(() -> log.info("Individual Agreement  TERMINATED for session {}", sessionId))
                    .doFinally(signal -> {
                        observation.stop();
                        log.info("Individual Agreement  FINALLY signal: {} for session {}", signal, sessionId);
                    })
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    /**
     * Stage 4: Models identify where responses DISAGREE.
     * Stage 1 responses are retrieved server-side to prevent client manipulation.
     */
    public Flux<IndividualDisagreement> runDisagreementAnalysis(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        List<IndividualResponse> individualResponses = state.getIndividualResponses().stream()
                .filter(r -> !r.isError())
                .toList();
        if (individualResponses.isEmpty()) {
            return Flux.error(new IllegalStateException(
                    "No successful responses available for Disagreement Analysis"));
        }

        String userQuery = state.getQuery();
        String responsesText = buildResponsesText(individualResponses);

        log.info("Starting Disagreement Analysis for session {} with {} council members ({} successful responses)",
                sessionId, councilMembers.size(), individualResponses.size());

        return Flux.defer(() -> {
            Observation parentObs = state.getDeliberationObservation();
            Observation observation = Observation
                    .createNotStarted("council.stage.disagreement-analysis", observationRegistry)
                    .lowCardinalityKeyValue("council.stage", "4")
                    .highCardinalityKeyValue("council.session.id", sessionId);
            if (parentObs != null) {
                observation.parentObservation(parentObs);
            }
            observation.start();

            return Flux.fromIterable(councilMembers)
                    .flatMap(member -> generateDisagreementPoints(member, userQuery, responsesText, sessionId),
                            councilMembers.size())
                    .doOnNext(disagreement -> {
                        state.getIndividualDisagreements().add(disagreement);
                        log.debug("Individual Disagreement Stage emitting: {} for session {}",
                                disagreement.evaluatorModelName(), sessionId);
                    })
                    .doOnComplete(() -> {
                        long stageTokenTotal = state.getIndividualDisagreements().stream().mapToLong(IndividualDisagreement::totalTokens).sum();
                        observation.highCardinalityKeyValue("council.stage.tokens.total", String.valueOf(stageTokenTotal));
                        long stageErrorCount = state.getIndividualDisagreements().stream().filter(d -> d.disagreements().isEmpty()).count();
                        observation.highCardinalityKeyValue("council.stage.error.count", String.valueOf(stageErrorCount));
                        log.info("Individual Disagreement Stage COMPLETED for session {}, {} disagreements collected",
                                sessionId, state.getIndividualDisagreements().size());
                    })
                    .doOnError(observation::error)
                    .doOnTerminate(() -> log.info("Individual Disagreement Stage TERMINATED for session {}", sessionId))
                    .doFinally(signal -> {
                        observation.stop();
                        log.info("Individual Disagreement Stage FINALLY signal: {} for session {}", signal, sessionId);
                    })
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
        });
    }

    /**
     * Stage 5: Chairman synthesizes final response.
     * All intermediate data is retrieved server-side to prevent client
     * manipulation.
     * Observation is handled internally (same pattern as stages 1-4) to ensure
     * proper trace context propagation through the reactive chain.
     */
    public Flux<FinalResponse> runFinalSynthesis(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            return Flux.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        List<IndividualResponse> individualResponses = state.getIndividualResponses().stream()
                .filter(r -> !r.isError())
                .toList();
        List<IndividualRanking> rankings = state.getIndividualRankings();
        if (individualResponses.isEmpty()) {
            return Flux.error(
                    new IllegalStateException("No successful responses available for Final Synthesis"));
        }
        if (rankings.isEmpty()) {
            return Flux
                    .error(new IllegalStateException("Individual Ranking Stage must complete before final synthesis"));
        }

        // Wrap in Flux.defer() to create observation at subscription time (like stages 1-4)
        return Flux.defer(() -> {
            Observation parentObs = state.getDeliberationObservation();
            Observation observation = Observation.createNotStarted("council.stage.final-synthesis", observationRegistry)
                    .lowCardinalityKeyValue("council.stage", "5")
                    .highCardinalityKeyValue("council.session.id", sessionId);
            if (parentObs != null) {
                observation.parentObservation(parentObs);
            }
            observation.start();
            observation.lowCardinalityKeyValue("council.member.provider", chairman.provider());
            observation.lowCardinalityKeyValue("council.member.model", chairman.modelId());

            String query = state.getQuery();
            log.debug("Generating final synthesis from chairman {}", chairman.name());

            String systemMessage = new PromptTemplate(finalResponseSystem)
                    .render(Map.of(
                            "user_query", query,
                            "individual_responses_text", buildResponsesText(individualResponses),
                            "peer_review_text", buildPeerReviewText(rankings)));

            String userMessage = "Please synthesize the council's responses and provide your final answer.";
            String chatId = sessionId + "-final-" + chairman.modelId();
            Instant startTime = Instant.now();
            AtomicLong tokenCount = new AtomicLong(0);
            AtomicLong promptTokenCount = new AtomicLong(0);
            AtomicLong completionTokenCount = new AtomicLong(0);
            AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
            AtomicLong ttft = new AtomicLong(0);

            return chatClientRegistry.getClientOrThrow(chairman.modelId())
                    .generateResponse(systemMessage, userMessage, chatId)
                    .doOnSubscribe(s -> log.info("Final synthesis: subscribed to {} on thread [{}]",
                            chairman.name(), Thread.currentThread().getName()))
                    .doOnNext(chunk -> {
                        Usage usage = chunk.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() > 0) {
                            tokenCount.set(usage.getTotalTokens());
                            if (usage.getPromptTokens() != null) {
                                promptTokenCount.set(usage.getPromptTokens());
                            }
                            if (usage.getCompletionTokens() != null) {
                                completionTokenCount.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    .filter(cr -> cr.getResult() != null)
                    .map(cr -> cr.getResult().getOutput().getText())
                    .filter(text -> text != null)
                    .doOnNext(text -> {
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            ttft.set(Instant.now().toEpochMilli() - startTime.toEpochMilli());
                        }
                    })
                    .collect(StringBuilder::new, StringBuilder::append)
                    .map(StringBuilder::toString)
                    .map(content -> {
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.info("Received final synthesis from {} ({} ms, {} tokens) on thread [{}]",
                                chairman.name(), duration, tokenCount.get(), Thread.currentThread().getName());
                        observation.highCardinalityKeyValue("council.tokens.total", String.valueOf(tokenCount.get()));
                        observation.highCardinalityKeyValue("council.tokens.prompt", String.valueOf(promptTokenCount.get()));
                        observation.highCardinalityKeyValue("council.tokens.completion", String.valueOf(completionTokenCount.get()));
                        observation.highCardinalityKeyValue("council.response.length", String.valueOf(content.length()));
                        observation.highCardinalityKeyValue("council.ttft.ms", String.valueOf(ttft.get()));
                        FinalResponse response = new FinalResponse(
                                chairman.modelId(),
                                chairman.name(),
                                content,
                                Instant.now(),
                                duration,
                                tokenCount.get());
                        state.setFinalResponse(response);
                        return response;
                    })
                    .onErrorResume(e -> {
                        observation.error(e);  // Record error on observation
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.error("Error generating final synthesis from {} ({} ms): {}",
                                chairman.name(), duration, e.getMessage());
                        return Mono.just(new FinalResponse(
                                chairman.modelId(),
                                chairman.name(),
                                "[Error: " + e.getMessage() + "]",
                                Instant.now(),
                                duration,
                                0L));
                    })
                    .doFinally(signal -> {
                        observation.stop();
                        log.info("Final Synthesis Stage FINALLY signal: {} for session {}", signal, sessionId);
                    })
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation))
                    .flux();  // Convert Mono to Flux (single emission) for consistent reactive pattern
        });
    }

    /**
     * Calculate aggregate rankings from Stage 2 results.
     */
    public List<AggregateRanking> calculateAggregateRankings(
            List<IndividualRanking> rankings, Map<String, String> labelToModel) {

        Map<String, List<Integer>> rankPositions = new HashMap<>();

        for (IndividualRanking ranking : rankings) {
            for (int i = 0; i < ranking.ranking().size(); i++) {
                String label = ranking.ranking().get(i);
                rankPositions.computeIfAbsent(label, _ -> new ArrayList<>()).add(i + 1);
            }
        }

        return rankPositions.entrySet().stream()
                .map(entry -> {
                    String label = entry.getKey();
                    List<Integer> positions = entry.getValue();
                    double avgRank = positions.stream().mapToInt(i -> i).average().orElse(0);
                    String modelId = labelToModel.get(label);
                    String modelName = councilMembers.stream()
                            .filter(m -> modelId != null && m.modelId().equals(modelId))
                            .findFirst()
                            .map(CouncilMember::name)
                            .orElse(label);
                    return new AggregateRanking(label, modelId, modelName, avgRank, positions.size());
                })
                .sorted()
                .toList();
    }

    /**
     * Calculate aggregate agreements from Stage 3 results.
     * Groups agreement points by topic and counts mentions across evaluators.
     */
    public List<AggregateAgreement> calculateAggregateAgreements(List<IndividualAgreement> agreements) {
        Map<String, AggregateAgreementBuilder> byTopic = new HashMap<>();

        for (IndividualAgreement agreement : agreements) {
            for (AgreementPoint point : agreement.agreements()) {
                byTopic.computeIfAbsent(point.topic(), _ -> new AggregateAgreementBuilder(point.topic()))
                        .add(point);
            }
        }

        return byTopic.values().stream()
                .map(AggregateAgreementBuilder::build)
                .sorted()
                .toList();
    }

    /**
     * Calculate aggregate disagreements from Stage 4 results.
     * Groups disagreement points by topic and consolidates positions.
     */
    public List<AggregateDisagreement> calculateAggregateDisagreements(List<IndividualDisagreement> disagreements) {
        Map<String, AggregateDisagreementBuilder> byTopic = new HashMap<>();

        for (IndividualDisagreement disagreement : disagreements) {
            for (DisagreementPoint point : disagreement.disagreements()) {
                byTopic.computeIfAbsent(point.topic(), _ -> new AggregateDisagreementBuilder(point.topic()))
                        .add(point);
            }
        }

        return byTopic.values().stream()
                .map(AggregateDisagreementBuilder::build)
                .sorted()
                .toList();
    }

    /**
     * Get the authoritative label-to-model mapping from server-side session state.
     * This ensures the UI legend matches the labels used in peer review evaluation text.
     */
    public Map<String, String> getLabelToModel(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return Map.copyOf(state.getLabelToModel());
    }

    /**
     * Calculate aggregate rankings using server-side session state.
     */
    public List<AggregateRanking> calculateAggregateRankings(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        Observation obs = startStageObservation("council.aggregation.rankings", "agg-rankings", sessionId, state);
        try {
            List<AggregateRanking> result = calculateAggregateRankings(state.getIndividualRankings(), state.getLabelToModel());
            obs.highCardinalityKeyValue("council.aggregation.item.count", String.valueOf(result.size()));
            return result;
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    /**
     * Calculate aggregate agreements using server-side session state.
     */
    public List<AggregateAgreement> calculateAggregateAgreements(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        Observation obs = startStageObservation("council.aggregation.agreements", "agg-agreements", sessionId, state);
        try {
            List<AggregateAgreement> result = calculateAggregateAgreements(state.getIndividualAgreements());
            obs.highCardinalityKeyValue("council.aggregation.item.count", String.valueOf(result.size()));
            return result;
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    /**
     * Calculate aggregate disagreements using server-side session state.
     */
    public List<AggregateDisagreement> calculateAggregateDisagreements(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        Observation obs = startStageObservation("council.aggregation.disagreements", "agg-disagreements", sessionId, state);
        try {
            List<AggregateDisagreement> result = calculateAggregateDisagreements(state.getIndividualDisagreements());
            obs.highCardinalityKeyValue("council.aggregation.item.count", String.valueOf(result.size()));
            return result;
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    /**
     * Calculate ranking consensus using Kendall's W (Coefficient of Concordance).
     *
     * W = 12 * S / (m² * (n³ - n))
     * where m = number of evaluators, n = number of items,
     * S = sum of squared deviations of rank sums from the mean rank sum.
     *
     * Returns 1.0 when fewer than 2 evaluators or fewer than 2 items (trivial agreement).
     */
    public double calculateRankingConsensus(List<IndividualRanking> rankings) {
        int m = rankings.size();
        if (m < 2) return 1.0;

        // Collect rank positions per item: item label -> list of ranks (1-based)
        Map<String, List<Integer>> ranksByItem = new HashMap<>();
        for (IndividualRanking ranking : rankings) {
            List<String> order = ranking.ranking();
            for (int i = 0; i < order.size(); i++) {
                ranksByItem.computeIfAbsent(order.get(i), _ -> new ArrayList<>()).add(i + 1);
            }
        }

        int n = ranksByItem.size();
        if (n < 2) return 1.0;

        // Calculate mean rank sum: each evaluator assigns ranks 1..n, so mean sum = m * (n+1) / 2
        double meanRankSum = m * (n + 1.0) / 2.0;

        // S = sum of squared deviations of each item's rank sum from the mean
        double s = 0.0;
        for (List<Integer> positions : ranksByItem.values()) {
            double rankSum = positions.stream().mapToInt(i -> i).sum();
            double deviation = rankSum - meanRankSum;
            s += deviation * deviation;
        }

        double w = (12.0 * s) / ((long) m * m * ((long) n * n * n - n));
        return Math.max(0.0, Math.min(1.0, w));
    }

    /**
     * Calculate disagreement severity as a weighted multi-factor score.
     *
     * Per disagreement topic, three factors are computed:
     * 1. Stance diversity (weight 0.4): min(numStances / 3.0, 1.0)
     * 2. Balance (weight 0.35): normalized Shannon entropy of position sizes
     * 3. Mention frequency (weight 0.25): min(mentionCount / councilMemberCount, 1.0)
     *
     * Final score = average of per-topic severities. Empty disagreements → 0.0.
     */
    public double calculateDisagreementSeverity(List<AggregateDisagreement> disagreements, int councilMemberCount) {
        if (disagreements.isEmpty()) return 0.0;

        double totalSeverity = 0.0;
        for (AggregateDisagreement d : disagreements) {
            int numStances = d.positions().size();
            double stanceDiversity = Math.min(numStances / 3.0, 1.0);

            // Shannon entropy normalized to [0,1]
            double balance = 0.0;
            if (numStances > 1) {
                int totalLabels = d.positions().stream()
                        .mapToInt(p -> p.responseLabels().size())
                        .sum();
                if (totalLabels > 0) {
                    double entropy = 0.0;
                    for (var pos : d.positions()) {
                        double p = (double) pos.responseLabels().size() / totalLabels;
                        if (p > 0) entropy -= p * Math.log(p);
                    }
                    double maxEntropy = Math.log(numStances);
                    balance = maxEntropy > 0 ? entropy / maxEntropy : 0.0;
                }
            }

            double mentionFrequency = councilMemberCount > 0
                    ? Math.min((double) d.mentionCount() / councilMemberCount, 1.0)
                    : 0.0;

            double topicSeverity = 0.4 * stanceDiversity + 0.35 * balance + 0.25 * mentionFrequency;
            totalSeverity += topicSeverity;
        }

        return totalSeverity / disagreements.size();
    }

    /**
     * Calculate consensus metrics using server-side session state.
     */
    public ConsensusMetrics calculateConsensusMetrics(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        Observation obs = startStageObservation("council.aggregation.consensus", "agg-consensus", sessionId, state);
        try {
            double consensus = calculateRankingConsensus(state.getIndividualRankings());

            List<AggregateDisagreement> aggDisagreements =
                    calculateAggregateDisagreements(state.getIndividualDisagreements());
            double severity = calculateDisagreementSeverity(aggDisagreements, councilMembers.size());

            obs.highCardinalityKeyValue("council.consensus.kendalls_w", String.valueOf(consensus));
            obs.highCardinalityKeyValue("council.consensus.disagreement_severity", String.valueOf(severity));
            return new ConsensusMetrics(consensus, severity);
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    private Observation startStageObservation(String name, String stage, String sessionId, SessionState state) {
        Observation parentObs = state.getDeliberationObservation();
        Observation obs = Observation.createNotStarted(name, observationRegistry)
                .lowCardinalityKeyValue("council.stage", stage)
                .highCardinalityKeyValue("council.session.id", sessionId);
        if (parentObs != null) {
            obs.parentObservation(parentObs);
        }
        obs.start();
        return obs;
    }

    /**
     * Saves a completed session using server-side cached state.
     * Prevents trust boundary violation by not accepting client-provided data.
     */
    public Mono<SavedSession> saveCompletedSessionFromCache(String sessionId) {
        SessionState state = getActiveSessions().getIfPresent(sessionId);
        if (state == null) {
            return Mono.error(new IllegalArgumentException("Session not found: " + sessionId));
        }

        Observation saveObs = startStageObservation("council.session.save", "save", sessionId, state);

        ConsensusMetrics metrics = calculateConsensusMetrics(sessionId);

        Span span = state.getDeliberationSpan();
        if (span != null) {
            span.setAttribute("council.consensus.kendalls_w", metrics.rankingConsensusScore());
            span.setAttribute("council.consensus.disagreement_severity", metrics.disagreementSeverity());
        }

        CouncilSession session = new CouncilSession(
                sessionId,
                state.getQuery(),
                Instant.now(),
                CouncilStage.COMPLETE,
                List.copyOf(state.getIndividualResponses()),
                List.copyOf(state.getIndividualRankings()),
                Map.copyOf(state.getLabelToModel()),
                calculateAggregateRankings(sessionId),
                List.copyOf(state.getIndividualAgreements()),
                calculateAggregateAgreements(state.getIndividualAgreements()),
                List.copyOf(state.getIndividualDisagreements()),
                calculateAggregateDisagreements(state.getIndividualDisagreements()),
                metrics,
                state.getFinalResponse());
        return saveCompletedSession(session)
                .doOnError(saveObs::error)
                .doFinally(signal -> {
                    saveObs.stop();

                    // Stop the Micrometer observation
                    Observation obs = state.getDeliberationObservation();
                    if (obs != null) {
                        obs.stop();
                        state.setDeliberationObservation(null);
                    }
                    // End the OTel span
                    if (span != null) {
                        span.setStatus(StatusCode.OK);
                        span.end();
                        state.setDeliberationSpan(null);
                        log.info("Ended deliberation span for session {}", sessionId);
                    }
                    // Drop the cache entry now that the session is persisted —
                    // prevents the removalListener from running stop()/end() on
                    // already-null fields when the 2h expireAfterWrite fires.
                    getActiveSessions().invalidate(sessionId);
                });
    }

    // Helper class to build aggregate agreements
    private static class AggregateAgreementBuilder {
        private final String topic;
        private String description;
        private final Set<String> agreeingResponses = new LinkedHashSet<>();
        private int mentionCount = 0;

        AggregateAgreementBuilder(String topic) {
            this.topic = topic;
        }

        void add(AgreementPoint point) {
            if (description == null) {
                description = point.description();
            }
            agreeingResponses.addAll(point.agreeingResponses());
            mentionCount++;
        }

        AggregateAgreement build() {
            return new AggregateAgreement(
                    topic,
                    description,
                    new ArrayList<>(agreeingResponses),
                    mentionCount);
        }
    }

    // Helper class to build aggregate disagreements
    private static class AggregateDisagreementBuilder {
        private final String topic;
        private String description;
        private final Map<String, Set<String>> stanceToResponses = new LinkedHashMap<>();
        private int mentionCount = 0;

        AggregateDisagreementBuilder(String topic) {
            this.topic = topic;
        }

        void add(DisagreementPoint point) {
            if (description == null) {
                description = point.description();
            }
            for (Position pos : point.positions()) {
                stanceToResponses.computeIfAbsent(pos.stance(), _ -> new LinkedHashSet<>())
                        .add(pos.responseLabel());
            }
            mentionCount++;
        }

        AggregateDisagreement build() {
            List<AggregatePosition> positions = stanceToResponses.entrySet().stream()
                    .map(e -> new AggregatePosition(e.getKey(), new ArrayList<>(e.getValue())))
                    .toList();

            return new AggregateDisagreement(
                    topic,
                    description,
                    positions,
                    mentionCount);
        }
    }

    private Map<String, String> createLabelMapping(List<IndividualResponse> responses) {
        if (responses.size() > 26) {
            throw new IllegalStateException(
                "Cannot assign A-Z labels to " + responses.size()
                + " responses: the label scheme supports at most 26 council members.");
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        char label = 'A';
        for (IndividualResponse response : responses) {
            mapping.put("Response " + label, response.modelId());
            label++;
        }
        return mapping;
    }

    private String buildResponsesText(List<IndividualResponse> responses) {
        if (responses.size() > 26) {
            throw new IllegalStateException(
                "Cannot build responses text for " + responses.size()
                + " responses: the label scheme supports at most 26 council members.");
        }
        StringBuilder sb = new StringBuilder();
        char label = 'A';
        for (IndividualResponse response : responses) {
            sb.append("--- Response ").append(label).append(" ---\n");
            sb.append(response.content()).append("\n\n");
            label++;
        }
        return sb.toString();
    }

    private String buildPeerReviewText(List<IndividualRanking> rankings) {
        StringBuilder sb = new StringBuilder();
        for (IndividualRanking ranking : rankings) {
            sb.append("--- Evaluation by ").append(ranking.evaluatorModelName()).append(" ---\n");
            sb.append(ranking.evaluation()).append("\n\n");
        }
        return sb.toString();
    }

    private Mono<IndividualResponse> generateIndividualResponse(CouncilMember member, String query, String sessionId) {
        log.debug("Generating Individual Response from {}", member.name());

        String systemMessage = new PromptTemplate(initialReviewSystem)
                .render(Map.of(
                        "name", member.name()));

        String chatId = sessionId + "-" + member.modelId();
        Instant startTime = Instant.now();
        AtomicLong tokenCount = new AtomicLong(0);
        AtomicLong promptTokenCount = new AtomicLong(0);
        AtomicLong completionTokenCount = new AtomicLong(0);
        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
        AtomicLong ttft = new AtomicLong(0);

        return Mono.deferContextual(ctxView -> {
            Observation parentObs = ctxView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            Observation memberObs = Observation.createNotStarted("council.member.response", observationRegistry)
                    .lowCardinalityKeyValue("council.member", member.name())
                    .lowCardinalityKeyValue("council.member.provider", member.provider())
                    .lowCardinalityKeyValue("council.member.model", member.modelId());
            if (parentObs != null) {
                memberObs.parentObservation(parentObs);
            }
            memberObs.start();

            return chatClientRegistry.getClientOrThrow(member.modelId())
                    .generateResponse(systemMessage, query, chatId)
                    .doOnSubscribe(s -> log.info("Subscribed to {} on thread [{}]",
                            member.name(), Thread.currentThread().getName()))
                    .doOnNext(chunk -> {
                        Usage usage = chunk.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() > 0) {
                            tokenCount.set(usage.getTotalTokens());
                            if (usage.getPromptTokens() != null) {
                                promptTokenCount.set(usage.getPromptTokens());
                            }
                            if (usage.getCompletionTokens() != null) {
                                completionTokenCount.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    .filter(cr -> cr.getResult() != null)
                    .map(cr -> cr.getResult().getOutput().getText())
                    .filter(text -> text != null)
                    .doOnNext(text -> {
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            ttft.set(Instant.now().toEpochMilli() - startTime.toEpochMilli());
                        }
                    })
                    .collect(StringBuilder::new, StringBuilder::append)
                    .map(StringBuilder::toString)
                    .map(content -> {
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.info("Received individual response from {} ({} ms, {} tokens) on thread [{}]",
                                member.name(), duration, tokenCount.get(), Thread.currentThread().getName());
                        memberObs.highCardinalityKeyValue("council.tokens.total", String.valueOf(tokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.prompt", String.valueOf(promptTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.completion", String.valueOf(completionTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.response.length", String.valueOf(content.length()));
                        memberObs.highCardinalityKeyValue("council.ttft.ms", String.valueOf(ttft.get()));
                        return new IndividualResponse(
                                member.modelId(),
                                member.name(),
                                content,
                                null,
                                Instant.now(),
                                duration,
                                tokenCount.get());
                    })
                    .onErrorResume(e -> {
                        memberObs.error(e);
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.error("Error generating Individual response from {} ({} ms): {}",
                                member.name(), duration, e.getMessage());
                        return Mono.just(new IndividualResponse(
                                member.modelId(),
                                member.name(),
                                "[Error: " + e.getMessage() + "]",
                                null,
                                Instant.now(),
                                duration,
                                0L));
                    })
                    .doFinally(sig -> memberObs.stop())
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(c -> c.put(ObservationThreadLocalAccessor.KEY, memberObs));
        });
    }

    private Mono<IndividualRanking> generatePeerRankings(
            CouncilMember member, String userQuery, String responsesText, String sessionId) {
        log.debug("Generating Peer Ranking from {}", member.name());

        String systemMessage = new PromptTemplate(peerRankingSystem)
                .render(Map.of(
                        "user_query", userQuery,
                        "responses_text", responsesText));

        String userMessage = "Please evaluate the responses and provide your ranking.";
        String chatId = sessionId + "-ranking-" + member.modelId();
        Instant startTime = Instant.now();
        AtomicLong tokenCount = new AtomicLong(0);
        AtomicLong promptTokenCount = new AtomicLong(0);
        AtomicLong completionTokenCount = new AtomicLong(0);
        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
        AtomicLong ttft = new AtomicLong(0);

        return Mono.deferContextual(ctxView -> {
            Observation parentObs = ctxView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            Observation memberObs = Observation.createNotStarted("council.member.ranking", observationRegistry)
                    .lowCardinalityKeyValue("council.member", member.name())
                    .lowCardinalityKeyValue("council.member.provider", member.provider())
                    .lowCardinalityKeyValue("council.member.model", member.modelId());
            if (parentObs != null) {
                memberObs.parentObservation(parentObs);
            }
            memberObs.start();

            return chatClientRegistry.getClientOrThrow(member.modelId())
                    .generateResponse(systemMessage, userMessage, chatId)
                    .doOnSubscribe(s -> log.info("Peer ranking: subscribed to {} on thread [{}]",
                            member.name(), Thread.currentThread().getName()))
                    .doOnNext(chunk -> {
                        Usage usage = chunk.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() > 0) {
                            tokenCount.set(usage.getTotalTokens());
                            if (usage.getPromptTokens() != null) {
                                promptTokenCount.set(usage.getPromptTokens());
                            }
                            if (usage.getCompletionTokens() != null) {
                                completionTokenCount.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    .filter(cr -> cr.getResult() != null)
                    .map(cr -> cr.getResult().getOutput().getText())
                    .filter(text -> text != null)
                    .doOnNext(text -> {
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            ttft.set(Instant.now().toEpochMilli() - startTime.toEpochMilli());
                        }
                    })
                    .collect(StringBuilder::new, StringBuilder::append)
                    .map(StringBuilder::toString)
                    .map(content -> {
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        List<String> ranking = parserService.parseRanking(content);
                        log.info("Received peer ranking from {} ({} ms, {} tokens, {} ranked entries) on thread [{}]",
                                member.name(), duration, tokenCount.get(), ranking.size(),
                                Thread.currentThread().getName());
                        memberObs.highCardinalityKeyValue("council.tokens.total", String.valueOf(tokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.prompt", String.valueOf(promptTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.completion", String.valueOf(completionTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.response.length", String.valueOf(content.length()));
                        memberObs.highCardinalityKeyValue("council.ttft.ms", String.valueOf(ttft.get()));
                        memberObs.lowCardinalityKeyValue("council.parse.success", String.valueOf(!ranking.isEmpty()));
                        memberObs.highCardinalityKeyValue("council.parse.item.count", String.valueOf(ranking.size()));
                        return new IndividualRanking(
                                member.modelId(),
                                member.name(),
                                content,
                                ranking,
                                duration,
                                tokenCount.get());
                    })
                    .onErrorResume(e -> {
                        memberObs.error(e);
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.error("Error generating peer ranking from {} ({} ms): {}",
                                member.name(), duration, e.getMessage());
                        return Mono.just(new IndividualRanking(
                                member.modelId(),
                                member.name(),
                                "[Error: " + e.getMessage() + "]",
                                List.of(),
                                duration,
                                0));
                    })
                    .doFinally(sig -> memberObs.stop())
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(c -> c.put(ObservationThreadLocalAccessor.KEY, memberObs));
        });

    }

    private Mono<IndividualAgreement> generateAgreementPoints(
            CouncilMember member, String userQuery, String responsesText, String sessionId) {
        log.debug("Generating Agreement Analysis from {}", member.name());

        String systemMessage = new PromptTemplate(agreementAnalysisSystem)
                .render(Map.of(
                        "user_query", userQuery,
                        "responses_text", responsesText));

        String userMessage = "Please analyze the responses and identify points of agreement.";
        String chatId = sessionId + "-agreement-" + member.modelId();
        Instant startTime = Instant.now();
        AtomicLong tokenCount = new AtomicLong(0);
        AtomicLong promptTokenCount = new AtomicLong(0);
        AtomicLong completionTokenCount = new AtomicLong(0);
        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
        AtomicLong ttft = new AtomicLong(0);

        return Mono.deferContextual(ctxView -> {
            Observation parentObs = ctxView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            Observation memberObs = Observation.createNotStarted("council.member.agreement", observationRegistry)
                    .lowCardinalityKeyValue("council.member", member.name())
                    .lowCardinalityKeyValue("council.member.provider", member.provider())
                    .lowCardinalityKeyValue("council.member.model", member.modelId());
            if (parentObs != null) {
                memberObs.parentObservation(parentObs);
            }
            memberObs.start();

            return chatClientRegistry.getClientOrThrow(member.modelId())
                    .generateResponse(systemMessage, userMessage, chatId)
                    .doOnSubscribe(s -> log.info("Agreement analysis: subscribed to {} on thread [{}]",
                            member.name(), Thread.currentThread().getName()))
                    .doOnNext(chunk -> {
                        Usage usage = chunk.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() > 0) {
                            tokenCount.set(usage.getTotalTokens());
                            if (usage.getPromptTokens() != null) {
                                promptTokenCount.set(usage.getPromptTokens());
                            }
                            if (usage.getCompletionTokens() != null) {
                                completionTokenCount.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    .filter(cr -> cr.getResult() != null)
                    .map(cr -> cr.getResult().getOutput().getText())
                    .filter(text -> text != null)
                    .doOnNext(text -> {
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            ttft.set(Instant.now().toEpochMilli() - startTime.toEpochMilli());
                        }
                    })
                    .collect(StringBuilder::new, StringBuilder::append)
                    .map(StringBuilder::toString)
                    .map(content -> {
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        List<AgreementPoint> agreements = parserService.parseAgreementPoints(content);
                        log.info(
                                "Received agreement analysis from {} ({} ms, {} tokens, {} agreement points) on thread [{}]",
                                member.name(), duration, tokenCount.get(), agreements.size(),
                                Thread.currentThread().getName());
                        memberObs.highCardinalityKeyValue("council.tokens.total", String.valueOf(tokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.prompt", String.valueOf(promptTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.completion", String.valueOf(completionTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.response.length", String.valueOf(content.length()));
                        memberObs.highCardinalityKeyValue("council.ttft.ms", String.valueOf(ttft.get()));
                        memberObs.lowCardinalityKeyValue("council.parse.success", String.valueOf(!agreements.isEmpty()));
                        memberObs.highCardinalityKeyValue("council.parse.item.count", String.valueOf(agreements.size()));
                        return new IndividualAgreement(
                                member.modelId(),
                                member.name(),
                                content,
                                agreements,
                                duration,
                                tokenCount.get());
                    })
                    .onErrorResume(e -> {
                        memberObs.error(e);
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.error("Error generating agreement analysis from {} ({} ms): {}",
                                member.name(), duration, e.getMessage());
                        return Mono.just(new IndividualAgreement(
                                member.modelId(),
                                member.name(),
                                "[Error: " + e.getMessage() + "]",
                                List.of(),
                                duration,
                                0L));
                    })
                    .doFinally(sig -> memberObs.stop())
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(c -> c.put(ObservationThreadLocalAccessor.KEY, memberObs));
        });

    }

    private Mono<IndividualDisagreement> generateDisagreementPoints(
            CouncilMember member, String userQuery, String responsesText, String sessionId) {
        log.debug("Generating Disagreement Analysis from {}", member.name());

        String systemMessage = new PromptTemplate(disagreementAnalysisSystem)
                .render(Map.of(
                        "user_query", userQuery,
                        "responses_text", responsesText));

        String userMessage = "Please analyze the responses and identify points of disagreement.";
        String chatId = sessionId + "-disagreement-" + member.modelId();
        Instant startTime = Instant.now();
        AtomicLong tokenCount = new AtomicLong(0);
        AtomicLong promptTokenCount = new AtomicLong(0);
        AtomicLong completionTokenCount = new AtomicLong(0);
        AtomicBoolean firstChunkReceived = new AtomicBoolean(false);
        AtomicLong ttft = new AtomicLong(0);

        return Mono.deferContextual(ctxView -> {
            Observation parentObs = ctxView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
            Observation memberObs = Observation.createNotStarted("council.member.disagreement", observationRegistry)
                    .lowCardinalityKeyValue("council.member", member.name())
                    .lowCardinalityKeyValue("council.member.provider", member.provider())
                    .lowCardinalityKeyValue("council.member.model", member.modelId());
            if (parentObs != null) {
                memberObs.parentObservation(parentObs);
            }
            memberObs.start();

            return chatClientRegistry.getClientOrThrow(member.modelId())
                    .generateResponse(systemMessage, userMessage, chatId)
                    .doOnSubscribe(s -> log.info("Disagreement analysis: subscribed to {} on thread [{}]",
                            member.name(), Thread.currentThread().getName()))
                    .doOnNext(chunk -> {
                        Usage usage = chunk.getMetadata().getUsage();
                        if (usage != null && usage.getTotalTokens() > 0) {
                            tokenCount.set(usage.getTotalTokens());
                            if (usage.getPromptTokens() != null) {
                                promptTokenCount.set(usage.getPromptTokens());
                            }
                            if (usage.getCompletionTokens() != null) {
                                completionTokenCount.set(usage.getCompletionTokens());
                            }
                        }
                    })
                    .filter(cr -> cr.getResult() != null)
                    .map(cr -> cr.getResult().getOutput().getText())
                    .filter(text -> text != null)
                    .doOnNext(text -> {
                        if (firstChunkReceived.compareAndSet(false, true)) {
                            ttft.set(Instant.now().toEpochMilli() - startTime.toEpochMilli());
                        }
                    })
                    .collect(StringBuilder::new, StringBuilder::append)
                    .map(StringBuilder::toString)
                    .map(content -> {
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        List<DisagreementPoint> disagreements = parserService.parseDisagreementPoints(content);
                        log.info(
                                "Received disagreement analysis from {} ({} ms, {} tokens, {} disagreement points) on thread [{}]",
                                member.name(), duration, tokenCount.get(), disagreements.size(),
                                Thread.currentThread().getName());
                        memberObs.highCardinalityKeyValue("council.tokens.total", String.valueOf(tokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.prompt", String.valueOf(promptTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.tokens.completion", String.valueOf(completionTokenCount.get()));
                        memberObs.highCardinalityKeyValue("council.response.length", String.valueOf(content.length()));
                        memberObs.highCardinalityKeyValue("council.ttft.ms", String.valueOf(ttft.get()));
                        memberObs.lowCardinalityKeyValue("council.parse.success", String.valueOf(!disagreements.isEmpty()));
                        memberObs.highCardinalityKeyValue("council.parse.item.count", String.valueOf(disagreements.size()));
                        return new IndividualDisagreement(
                                member.modelId(),
                                member.name(),
                                content,
                                disagreements,
                                duration,
                                tokenCount.get());
                    })
                    .onErrorResume(e -> {
                        memberObs.error(e);
                        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                        log.error("Error generating disagreement analysis from {} ({} ms): {}",
                                member.name(), duration, e.getMessage());
                        return Mono.just(new IndividualDisagreement(
                                member.modelId(),
                                member.name(),
                                "[Error: " + e.getMessage() + "]",
                                List.of(),
                                duration,
                                0L));
                    })
                    .doFinally(sig -> memberObs.stop())
                    .subscribeOn(Schedulers.boundedElastic())
                    .contextWrite(c -> c.put(ObservationThreadLocalAccessor.KEY, memberObs));
        });
    }

    /**
     * Saves a completed session: generates a title then persists to disk.
     */
    public Mono<SavedSession> saveCompletedSession(CouncilSession session) {
        return generateConversationTitle(session.query())
                .map(title -> {
                    if (storageMode == StorageMode.LOCAL) {
                        try {
                            return conversationStorage.saveSession(title, session);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to save session", e);
                        }
                    }
                    return conversationStorage.saveSessionGcs(title, session);
                });
    }

    public List<SavedSession> listSavedSessions() throws IOException {
        return storageMode == StorageMode.LOCAL
                ? conversationStorage.listSessions()
                : conversationStorage.listGcsSessions();
    }

    public SavedSession loadSavedSession(String filename) throws IOException {
        return storageMode == StorageMode.LOCAL
                ? conversationStorage.loadSession(filename)
                : conversationStorage.loadSessionGcs(filename);
    }

    private Mono<String> generateConversationTitle(String userQuery) {
        String systemMessage = new PromptTemplate(titleGenerationSystem)
                .render(Map.of("user_query", userQuery));

        String userMessage = "Generate the title.";
        String chatId = "title-" + UUID.randomUUID();

        return chatClientRegistry.getClientOrThrow(titleModel.modelId())
                .generateResponse(systemMessage, userMessage, chatId)
                .filter(cr -> cr.getResult() != null)
                .map(cr -> cr.getResult().getOutput().getText())
                .filter(text -> text != null)
                .collect(StringBuilder::new, StringBuilder::append)
                .map(sb -> sb.toString().trim())
                .doOnSuccess(title -> log.info("Generated conversation title: '{}' for query: '{}'",
                        title, userQuery))
                .onErrorResume(e -> {
                    log.error("Error generating conversation title: {}", e.getMessage());
                    return Mono.just("Untitled Session");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
