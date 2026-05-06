import { useState } from 'react';
import type TraceSummaryType from 'Frontend/generated/dev/council/model/TraceSummary';
import type TraceSpanDetailType from 'Frontend/generated/dev/council/model/TraceSpanDetail';
import './TraceCard.css';

interface TraceCardProps {
  trace: TraceSummaryType;
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  return `${(ms / 60000).toFixed(1)}m`;
}

function timeAgo(isoString: string | undefined): string {
  if (!isoString) return '';
  const diff = Date.now() - new Date(isoString).getTime();
  const seconds = Math.floor(diff / 1000);
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function SpanTree({ spans, totalDurationMs, depth = 0 }: {
  spans: TraceSpanDetailType[];
  totalDurationMs: number;
  depth?: number;
}) {
  return (
    <>
      {spans.map((span, index) => {
        const barPercent = totalDurationMs > 0 ? Math.min(100, (span.durationMs! / totalDurationMs) * 100) : 0;
        const tokenCount = span.labels?.['council.tokens.total'] || span.labels?.['gen_ai.usage.output_tokens'];
        const memberName = span.labels?.['council.member'];

        return (
          <div key={`${span.spanId}-${index}`}>
            <div className="span-row">
              <span className="span-indent" style={{ width: depth * 24 }} />
              <span className="span-connector">{depth > 0 ? '├─' : ''}</span>
              <span className="span-name">{span.name}</span>
              {memberName && (
                <span className="span-member-badge">{memberName}</span>
              )}
              {tokenCount && (
                <span className="span-token-badge">{Number(tokenCount).toLocaleString()} tok</span>
              )}
              <span className="span-duration">{formatDuration(span.durationMs!)}</span>
              <span className="span-bar-container">
                <span className="span-bar" style={{ width: `${barPercent}%` }} />
              </span>
            </div>
            {span.childSpans && span.childSpans.length > 0 && (
              <SpanTree spans={span.childSpans.filter((s): s is TraceSpanDetailType => s !== undefined)} totalDurationMs={totalDurationMs} depth={depth + 1} />
            )}
          </div>
        );
      })}
    </>
  );
}

export default function TraceCard({ trace }: TraceCardProps) {
  const [expanded, setExpanded] = useState(false);

  const sessionDisplay = trace.sessionId
    ? trace.sessionId.substring(0, 8)
    : trace.traceId?.substring(0, 8) || 'unknown';

  return (
    <div className="trace-card">
      <button className="trace-summary" onClick={() => setExpanded(!expanded)}>
        <span className={`trace-toggle ${expanded ? 'expanded' : ''}`}>&#9654;</span>
        <span className="trace-session">Session: {sessionDisplay}</span>
        <span className="trace-meta">
          <span className="trace-duration">{formatDuration(trace.totalDurationMs!)}</span>
          {trace.totalTokens! > 0 && (
            <span className="trace-tokens">{trace.totalTokens!.toLocaleString()} tokens</span>
          )}
          <span className="trace-time">{timeAgo(trace.startTime)}</span>
          {trace.cloudTraceUrl && (
            <a
              className="trace-cloud-link"
              href={trace.cloudTraceUrl}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()}
            >
              View in Cloud Trace &#8599;
            </a>
          )}
        </span>
        <span className="trace-spans-count">{trace.spanCount} spans</span>
      </button>

      {expanded && trace.rootSpans && trace.rootSpans.length > 0 && (
        <div className="span-tree">
          <SpanTree spans={trace.rootSpans.filter((s): s is TraceSpanDetailType => s !== undefined)} totalDurationMs={trace.totalDurationMs!} />
        </div>
      )}
    </div>
  );
}
