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

import java.util.List;

/**
 * Stage 2: Ranking evaluation from a council member.
 */
public record IndividualRanking(
    String evaluatorModelId,
    String evaluatorModelName,
    String evaluation,
    List<String> ranking,  // Ordered list: ["Response A", "Response C", "Response B"]
    long durationMs,
    long totalTokens
) {}
