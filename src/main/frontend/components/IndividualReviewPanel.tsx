import { Tabs } from '@vaadin/react-components/Tabs';
import { Tab } from '@vaadin/react-components/Tab';
import { Markdown } from '@vaadin/react-components/Markdown';
import type IndividualResponse from 'Frontend/generated/dev/council/model/IndividualResponse';
import type CouncilMember from 'Frontend/generated/dev/council/model/CouncilMember';
import { getAvatarColor } from '../utils/council-utils';
import StageHeader from './StageHeader';
import './StageHeader.css';
import './IndividualReviewPanel.css';

interface Props {
  stageNumber: number;
  responses: IndividualResponse[];
  selectedTab: number;
  onTabChange: (index: number) => void;
  isLoading: boolean;
  councilMembers: CouncilMember[];
}

export default function IndividualReviewPanel({ stageNumber, responses, selectedTab, onTabChange, isLoading, councilMembers }: Props) {
  const selectedResponse = responses[selectedTab];

  return (
    <section className="stage-panel">
      <StageHeader
        stageNumber={stageNumber}
        title="Individual Responses"
        isLoading={isLoading}
        loadingText={`Collecting responses (${responses.length}/${councilMembers.length})`}
      />

      <Tabs
        selected={selectedTab}
        onSelectedChanged={(e) => onTabChange(e.detail.value)}
        className="response-tabs"
      >
        {responses.map((response, index) => (
          <Tab key={response.modelId ?? index}>
            <span
              className="model-avatar"
              style={{ backgroundColor: getAvatarColor(response.modelId, councilMembers) }}
            />
            {response.modelName}
          </Tab>
        ))}
      </Tabs>

      {selectedResponse && (
        <div>
          <div className="response-content">
            <Markdown>{selectedResponse.content ?? ''}</Markdown>
          </div>
          <div className="response-meta">
            <span>Model: {selectedResponse.modelId}</span>
            <span>Duration: {selectedResponse.durationMs}ms</span>
            <span>Tokens: {selectedResponse.totalTokens > 0
                ? selectedResponse.totalTokens.toLocaleString()
                : 'N/A'}</span>
          </div>
        </div>
      )}
    </section>
  );
}
