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
package dev.council.config;

import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.LoggingEnhancer;
import org.slf4j.MDC;

/**
 * Bridges Micrometer Tracing MDC context to Cloud Logging LogEntry fields
 * for trace-log correlation in Cloud Trace / Logs Explorer.
 *
 * <p>Micrometer's OTel bridge populates MDC with {@code traceId} and {@code spanId}.
 * This enhancer reads those values and sets the corresponding LogEntry fields
 * that Cloud Logging uses for trace correlation.
 */
public class MdcTraceLoggingEnhancer implements LoggingEnhancer {

    private static final String GCP_PROJECT = System.getenv("GOOGLE_CLOUD_PROJECT");

    @Override
    public void enhanceLogEntry(LogEntry.Builder builder) {
        String traceId = MDC.get("traceId");
        if (GCP_PROJECT != null && traceId != null && !traceId.isEmpty()) {
            builder.setTrace("projects/" + GCP_PROJECT + "/traces/" + traceId);
        }
        String spanId = MDC.get("spanId");
        if (spanId != null && !spanId.isEmpty()) {
            builder.setSpanId(spanId);
        }
    }
}
