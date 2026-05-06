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

import com.google.cloud.trace.v1.TraceServiceClient;
import com.google.devtools.cloudtrace.v1.ListTracesRequest;
import com.google.devtools.cloudtrace.v1.Trace;
import com.google.devtools.cloudtrace.v1.TraceSpan;
import com.google.protobuf.Timestamp;
import dev.council.model.TraceDuration;
import dev.council.model.TraceSpanDetail;
import dev.council.model.TraceSummary;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "GOOGLE_CLOUD_PROJECT")
public class TraceRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(TraceRetrievalService.class);
    private static final String WRAPPER_SPAN_NAME = "council.deliberation.wrapper";

    private final String projectId;
    private final Environment environment;

    private volatile TraceServiceClient traceServiceClient;

    public TraceRetrievalService(
            @Value("${GOOGLE_CLOUD_PROJECT:}") String projectId,
            Environment environment) {
        this.projectId = projectId;
        this.environment = environment;
    }

    protected TraceServiceClient getTraceServiceClient() throws IOException {
        if (this.traceServiceClient == null) {
            synchronized (this) {
                if (this.traceServiceClient == null) {
                    this.traceServiceClient = TraceServiceClient.create();
                }
            }
        }
        return this.traceServiceClient;
    }

    // For testing
    void setTraceServiceClient(TraceServiceClient client) {
        this.traceServiceClient = client;
    }

    public List<TraceSummary> getDeliberationTraces(TraceDuration duration) {
        if (projectId == null || projectId.isBlank()) {
            log.debug("GOOGLE_CLOUD_PROJECT not set, returning empty trace list");
            return List.of();
        }

        for (String profile : environment.getActiveProfiles()) {
            if ("local".equals(profile)) {
                log.debug("Local profile active, returning empty trace list");
                return List.of();
            }
        }

        try {
            TraceServiceClient client = getTraceServiceClient();

            Instant now = Instant.now();
            Instant start = now.minus(duration.getDuration());

            ListTracesRequest request = ListTracesRequest.newBuilder()
                    .setProjectId(projectId)
                    .setFilter("+span:" + WRAPPER_SPAN_NAME)
                    .setView(ListTracesRequest.ViewType.COMPLETE)
                    .setStartTime(toTimestamp(start))
                    .setEndTime(toTimestamp(now))
                    .setOrderBy("start desc")
                    .setPageSize(100)
                    .build();

            List<TraceSummary> summaries = new ArrayList<>();
            for (Trace trace : client.listTraces(request).iterateAll()) {
                TraceSummary summary = convertTrace(trace);
                if (summary != null) {
                    summaries.add(summary);
                }
            }

            log.info("Retrieved {} deliberation traces for {} from project {}",
                    summaries.size(), duration.getDisplayName(), projectId);
            return List.copyOf(summaries);

        } catch (Exception e) {
            log.error("Failed to retrieve traces from Cloud Trace: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private TraceSummary convertTrace(Trace trace) {
        List<TraceSpan> spans = trace.getSpansList();
        if (spans.isEmpty()) {
            return null;
        }

        // Build parent-to-children map (parentSpanId -> list of child spans)
        Map<Long, List<TraceSpan>> childrenMap = new HashMap<>();
        for (TraceSpan span : spans) {
            childrenMap.computeIfAbsent(span.getParentSpanId(), _ -> new ArrayList<>()).add(span);
        }

        // Find root spans (parentSpanId == 0 in v1 API)
        List<TraceSpan> rootSpans = childrenMap.getOrDefault(0L, List.of());

        // Build the span tree recursively
        List<TraceSpanDetail> rootDetails = rootSpans.stream()
                .map(span -> buildSpanDetail(span, childrenMap))
                .toList();

        // Extract session ID and total tokens from the wrapper span
        String sessionId = "";
        long totalTokens = 0;
        Instant traceStart = null;
        Instant traceEnd = null;

        for (TraceSpan span : spans) {
            if (WRAPPER_SPAN_NAME.equals(span.getName())) {
                Map<String, String> labels = span.getLabelsMap();
                sessionId = labels.getOrDefault("council.session.id", "");
                String tokensStr = labels.getOrDefault("council.tokens.total", "0");
                try {
                    totalTokens = Long.parseLong(tokensStr);
                } catch (NumberFormatException e) {
                    totalTokens = 0;
                }
            }

            Instant spanStart = toInstant(span.getStartTime());
            Instant spanEnd = toInstant(span.getEndTime());

            if (traceStart == null || spanStart.isBefore(traceStart)) {
                traceStart = spanStart;
            }
            if (traceEnd == null || spanEnd.isAfter(traceEnd)) {
                traceEnd = spanEnd;
            }
        }

        long totalDurationMs = (traceStart != null && traceEnd != null)
                ? traceEnd.toEpochMilli() - traceStart.toEpochMilli()
                : 0;

        String cloudTraceUrl = String.format(
                "https://console.cloud.google.com/traces/list?project=%s&tid=%s",
                projectId, trace.getTraceId());

        return new TraceSummary(
                trace.getTraceId(),
                sessionId,
                traceStart != null ? traceStart.toString() : "",
                traceEnd != null ? traceEnd.toString() : "",
                totalDurationMs,
                totalTokens,
                spans.size(),
                rootDetails,
                cloudTraceUrl
        );
    }

    private TraceSpanDetail buildSpanDetail(TraceSpan span, Map<Long, List<TraceSpan>> childrenMap) {
        List<TraceSpan> children = childrenMap.getOrDefault(span.getSpanId(), List.of());
        List<TraceSpanDetail> childDetails = children.stream()
                .map(child -> buildSpanDetail(child, childrenMap))
                .toList();

        Instant start = toInstant(span.getStartTime());
        Instant end = toInstant(span.getEndTime());
        long durationMs = end.toEpochMilli() - start.toEpochMilli();

        return new TraceSpanDetail(
                Long.toHexString(span.getSpanId()),
                Long.toHexString(span.getParentSpanId()),
                span.getName(),
                start.toString(),
                end.toString(),
                durationMs,
                Map.copyOf(span.getLabelsMap()),
                childDetails
        );
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
