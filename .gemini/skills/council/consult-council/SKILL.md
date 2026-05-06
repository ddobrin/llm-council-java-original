---
name: Consult the LLM Council
description: >-
  This skill should be used when the user asks to "consult the council",
  "ask the council", "submit to the council", "LLM council",
  "multi-model deliberation", "council deliberation", or
  "get council opinion" on a question or topic. Submits a query to the
  locally running LLM Council application for multi-model deliberation
  across 5 analysis stages (individual responses, peer ranking, agreement
  analysis, disagreement analysis, final synthesis).
---

# Consult the LLM Council

Submit a question to the LLM Council for multi-model deliberation. The council
runs 5 analysis stages: individual responses, peer ranking, agreement analysis,
disagreement analysis, and final synthesis. The complete session result is
returned as JSON.

## Prerequisites

The LLM Council app must be running on localhost:8080. Start it with either:
- `java -jar target/llm-council-1.0.0-SNAPSHOT.jar --spring.profiles.active=local`
- `./llm-council --spring.profiles.active=local,native`

## Workflow

1. Check whether the LLM Council app is running:
   ```
   curl -sf http://localhost:8080/actuator/health
   ```
   If not running, inform the user and provide the startup commands from Prerequisites above.

2. Extract the core question or topic from the user's message.

3. Submit the query to the council API:
   ```
   curl -sf -X POST http://localhost:8080/api/council/consult \
     -H "Content-Type: application/json" \
     -d '{"query": "<extracted question>"}' \
     --max-time 300
   ```

4. The response is a JSON object with this structure:
   - `title`: Generated title for the session
   - `filename`: Where the session was saved on disk
   - `session`: The full council session containing:
     - `individualResponses`: Each council member's individual answer
     - `individualRankings`: Each member's peer ranking of all responses
     - `aggregateRankings`: Consensus ranking across all evaluators
     - `individualAgreements` / `aggregateAgreements`: Points of consensus
     - `individualDisagreements` / `aggregateDisagreements`: Points of divergence
     - `consensusMetrics`: Kendall's W ranking consensus score + disagreement severity
     - `finalResponse`: Chairman's synthesized final answer

5. Present the full JSON output exactly as returned by the API. Do not summarize or truncate.

6. If the curl command fails or times out, inform the user. The council flow involves
   multiple LLM calls and typically takes 30-90 seconds to complete.
