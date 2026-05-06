import type ConsensusMetrics from 'Frontend/generated/dev/council/model/ConsensusMetrics';
import './ConsensusMetricsPanel.css';

interface Props {
  metrics: ConsensusMetrics;
  showDisagreementSeverity: boolean;
}

function getConsensusLabel(score: number): string {
  if (score >= 0.8) return 'Strong Agreement';
  if (score >= 0.5) return 'Moderate Agreement';
  return 'Low Agreement';
}

function getSeverityLabel(score: number): string {
  if (score >= 0.8) return 'Highly Contentious';
  if (score >= 0.5) return 'Moderately Contentious';
  return 'Mildly Contentious';
}

function getColorClass(score: number, invert: boolean): string {
  // For consensus: high is good (green). For severity: high is bad (red).
  const effective = invert ? 1 - score : score;
  if (effective >= 0.8) return 'metric-green';
  if (effective >= 0.5) return 'metric-orange';
  return 'metric-red';
}

export default function ConsensusMetricsPanel({ metrics, showDisagreementSeverity }: Props) {
  const consensus = metrics.rankingConsensusScore ?? 0;
  const severity = metrics.disagreementSeverity ?? 0;

  return (
    <div className="consensus-metrics">
      <div className="metric-card">
        <h4 className="metric-card-title">Ranking Consensus</h4>
        <div className={`metric-card-value ${getColorClass(consensus, false)}`}>
          {Math.round(consensus * 100)}%
        </div>
        <div className={`metric-card-label ${getColorClass(consensus, false)}`}>
          {getConsensusLabel(consensus)}
        </div>
      </div>

      {showDisagreementSeverity && (
        <div className="metric-card">
          <h4 className="metric-card-title">Disagreement Severity</h4>
          <div className={`metric-card-value ${getColorClass(severity, true)}`}>
            {Math.round(severity * 100)}%
          </div>
          <div className={`metric-card-label ${getColorClass(severity, true)}`}>
            {getSeverityLabel(severity)}
          </div>
        </div>
      )}
    </div>
  );
}
