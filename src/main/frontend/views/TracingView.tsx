import { useState, useEffect, useCallback } from 'react';
import { Select } from '@vaadin/react-components/Select';
import { Button } from '@vaadin/react-components/Button';
import { ProgressBar } from '@vaadin/react-components/ProgressBar';
import { CouncilEndpoint } from 'Frontend/generated/endpoints';
import type TraceSummary from 'Frontend/generated/dev/council/model/TraceSummary';
import TraceDuration from 'Frontend/generated/dev/council/model/TraceDuration';
import TraceCard from '../components/TraceCard';
import './TracingView.css';

const DURATION_OPTIONS = [
  { label: 'Last 15 Minutes', value: 'LAST_15_MINUTES' },
  { label: 'Last 30 Minutes', value: 'LAST_30_MINUTES' },
  { label: 'Last 1 Hour', value: 'LAST_1_HOUR' },
  { label: 'Last 6 Hours', value: 'LAST_6_HOURS' },
  { label: 'Last 24 Hours', value: 'LAST_24_HOURS' },
  { label: 'Last 7 Days', value: 'LAST_7_DAYS' },
];

export default function TracingView() {
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [duration, setDuration] = useState<string>('LAST_15_MINUTES');
  const [loading, setLoading] = useState(false);

  const fetchTraces = useCallback(async () => {
    setLoading(true);
    try {
      const result = await CouncilEndpoint.getDeliberationTraces(
        TraceDuration[duration as keyof typeof TraceDuration]
      );
      setTraces(result);
    } catch (err) {
      console.error('Failed to fetch traces:', err);
      setTraces([]);
    } finally {
      setLoading(false);
    }
  }, [duration]);

  useEffect(() => {
    fetchTraces();
  }, [fetchTraces]);

  return (
    <div className="tracing-view">
      <div className="tracing-header">
        <div className="tracing-header-row">
          <h2 className="tracing-title">Deliberation Traces</h2>
          <Select
            className="duration-select"
            items={DURATION_OPTIONS}
            value={duration}
            onValueChanged={(e) => setDuration(e.detail.value)}
          />
          <Button
            className="refresh-button"
            theme="tertiary"
            onClick={fetchTraces}
            disabled={loading}
          >
            Refresh
          </Button>
        </div>
      </div>

      {loading && <ProgressBar indeterminate />}

      {!loading && traces.length === 0 && (
        <div className="tracing-empty">
          <p className="tracing-empty-title">No traces found</p>
          <p>No deliberation traces found for the selected time range. Try a longer duration or run a council deliberation first.</p>
        </div>
      )}

      {!loading && traces.length > 0 && (
        <div className="trace-list">
          {traces.map((trace) => (
            <TraceCard key={trace.traceId} trace={trace} />
          ))}
        </div>
      )}
    </div>
  );
}
