---
name: Quick Consult the LLM Council
description: >-
  This skill should be used when the user asks to "quick consult the council",
  "fast council", "quick council", "quick LLM council",
  "quick multi-model deliberation", or wants a faster council opinion
  without full agreement and disagreement analysis. Runs only 3 stages:
  individual responses, peer ranking, and final synthesis.
---

# Quick Consult the LLM Council

Submit a question to the LLM Council for a faster deliberation that skips
agreement and disagreement analysis. Runs only 3 stages: individual responses,
peer ranking, and final synthesis. The complete session result is returned as
JSON with empty agreement/disagreement fields.

Typical execution time is 15-45 seconds (vs 30-90 seconds for the full council).

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

3. Submit the query to the quick-consult API:
   ```
   curl -sf -X POST http://localhost:8080/api/council/quick-consult \
     -H "Content-Type: application/json" \
     -d '{"query": "<extracted question>"}' \
     --max-time 300
   ```

4. The response is a JSON object with the same structure as the full consult,
   but with empty agreement/disagreement fields:
   - `title`: Generated title for the session
   - `filename`: Where the session was saved on disk
   - `session`: The council session containing:
     - `individualResponses`: Each council member's individual answer
     - `individualRankings`: Each member's peer ranking of all responses
     - `aggregateRankings`: Consensus ranking across all evaluators
     - `individualAgreements` / `aggregateAgreements`: Empty (stage skipped)
     - `individualDisagreements` / `aggregateDisagreements`: Empty (stage skipped)
     - `consensusMetrics`: Kendall's W ranking consensus score (disagreement severity will be 0.0)
     - `finalResponse`: Chairman's synthesized final answer

5. Present the full JSON output exactly as returned by the API. Do not summarize or truncate.

6. If the curl command fails or times out, inform the user. The quick council flow
   involves multiple LLM calls and typically takes 15-45 seconds to complete.
