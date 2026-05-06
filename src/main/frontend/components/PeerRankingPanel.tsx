import { Tabs } from '@vaadin/react-components/Tabs';
import { Tab } from '@vaadin/react-components/Tab';
import { Markdown } from '@vaadin/react-components/Markdown';
import type IndividualRanking from 'Frontend/generated/dev/council/model/IndividualRanking';
import type AggregateRanking from 'Frontend/generated/dev/council/model/AggregateRanking';
import type CouncilMember from 'Frontend/generated/dev/council/model/CouncilMember';
import { getAvatarColor } from '../utils/council-utils';
import StageHeader from './StageHeader';
import './StageHeader.css';
import './PeerRankingPanel.css';

interface Props {
  stageNumber: number;
  rankings: IndividualRanking[];
  aggregateRankings: AggregateRanking[];
  labelToModel: Record<string, string>;
  isLoading: boolean;
  councilMembers: CouncilMember[];
  selectedTab: number;
  onTabChange: (index: number) => void;
}

function getEvaluationText(evaluation: string | undefined): string {
  if (!evaluation) return '';
  const marker = evaluation.search(/FINAL RANKING[:\s]/i);
  if (marker === -1) return evaluation.trim();
  return evaluation.substring(0, marker).trim();
}

export default function PeerRankingPanel({ stageNumber, rankings, aggregateRankings, labelToModel, isLoading, councilMembers, selectedTab, onTabChange }: Props) {
  const getRankClass = (index: number) => {
    if (index === 0) return 'rank-1';
    if (index === 1) return 'rank-2';
    if (index === 2) return 'rank-3';
    return 'rank-other';
  };

  const selectedRanking = rankings[selectedTab];

  return (
    <section className="stage-panel">
      <StageHeader
        stageNumber={stageNumber}
        title="Peer Review & Rankings"
        isLoading={isLoading}
        loadingText={`Collecting rankings (${rankings.length}/${councilMembers.length})`}
      />

      {Object.keys(labelToModel).length > 0 && (
        <div className="model-legend">
          {Object.entries(labelToModel).map(([label, modelId]) => {
            const member = councilMembers.find(m => m.modelId === modelId);
            return (
              <span key={label} className="legend-item">
                <strong>{label.replace('Response ', 'Model ')}</strong>: {member?.name ?? modelId}
              </span>
            );
          })}
        </div>
      )}

      <Tabs
        selected={selectedTab}
        onSelectedChanged={(e) => onTabChange(e.detail.value)}
        className="response-tabs"
      >
        {rankings.map((ranking, index) => (
          <Tab key={ranking.evaluatorModelId ?? index}>
            <span
              className="model-avatar"
              style={{ backgroundColor: getAvatarColor(ranking.evaluatorModelId, councilMembers) }}
            />
            {ranking.evaluatorModelName}
          </Tab>
        ))}
      </Tabs>

      {selectedRanking && (
        <div>
          <div className="evaluation-content">
            <Markdown>{getEvaluationText(selectedRanking.evaluation) ?? ''}</Markdown>
          </div>

          <div className="ranking-section">
            <h4 className="ranking-section-title">Final Ranking</h4>
            <ol className="ranking-list">
              {selectedRanking.ranking?.map((label, index) => (
                <li key={label ?? index}>
                  <strong>{label}</strong>
                  {label && labelToModel[label] && (
                    <span style={{ color: '#6b7280', marginLeft: '0.5rem' }}>
                      ({councilMembers.find(m => m.modelId === labelToModel[label])?.name ?? labelToModel[label]})
                    </span>
                  )}
                </li>
              ))}
            </ol>
          </div>

          <div className="response-meta">
            <span>Model: {selectedRanking.evaluatorModelId}</span>
            <span>Duration: {selectedRanking.durationMs}ms</span>
            <span>Tokens: {selectedRanking.totalTokens > 0
                ? selectedRanking.totalTokens.toLocaleString()
                : 'N/A'}</span>
          </div>
        </div>
      )}

      {aggregateRankings.length > 0 && (
        <div className="aggregate-rankings">
          <h4 className="aggregate-title">Aggregate Rankings</h4>
          <div className="aggregate-list">
            {aggregateRankings.map((ranking, index) => (
              <div key={ranking.label} className="aggregate-item">
                <span className={`rank-badge ${getRankClass(index)}`}>
                  {index + 1}
                </span>
                <span className="aggregate-model">{ranking.modelName}</span>
                <span className="aggregate-score">
                  Avg. rank: {ranking.averageRank.toFixed(2)}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}
