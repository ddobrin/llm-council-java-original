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

import java.time.Duration;

public enum TraceDuration {

    LAST_15_MINUTES(Duration.ofMinutes(15), "Last 15 Minutes"),
    LAST_30_MINUTES(Duration.ofMinutes(30), "Last 30 Minutes"),
    LAST_1_HOUR(Duration.ofHours(1), "Last 1 Hour"),
    LAST_6_HOURS(Duration.ofHours(6), "Last 6 Hours"),
    LAST_24_HOURS(Duration.ofHours(24), "Last 24 Hours"),
    LAST_7_DAYS(Duration.ofDays(7), "Last 7 Days");

    private final Duration duration;
    private final String displayName;

    TraceDuration(Duration duration, String displayName) {
        this.duration = duration;
        this.displayName = displayName;
    }

    public Duration getDuration() {
        return duration;
    }

    public String getDisplayName() {
        return displayName;
    }
}
