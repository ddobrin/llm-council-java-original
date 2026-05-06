import { Markdown } from '@vaadin/react-components/Markdown';
import type FinalResponse from 'Frontend/generated/dev/council/model/FinalResponse';
import StageHeader from './StageHeader';
import './StageHeader.css';
import './FinalSynthesisPanel.css';

interface Props {
  stageNumber: number;
  response: FinalResponse;
  isLoading: boolean;
}

export default function FinalSynthesisPanel({ stageNumber, response, isLoading }: Props) {
  return (
    <section className="stage-panel">
      <StageHeader
        stageNumber={stageNumber}
        title="Final Synthesis"
        isLoading={isLoading}
        loadingText="Council Chairman synthesizing..."
      />

      <div className="final-response">
        <div className="final-response-content">
          <Markdown>{response.content ?? ''}</Markdown>
        </div>
        <div className="chairman-badge">
          Synthesized by {response.chairmanModelName}
          <span style={{ marginLeft: '1rem', opacity: 0.7 }}>
            ({response.durationMs}ms)
          </span>
          <span style={{ marginLeft: '0.5rem', opacity: 0.7 }}>
            | Tokens: {response.totalTokens > 0
              ? response.totalTokens.toLocaleString()
              : 'N/A'}
          </span>
        </div>
      </div>
    </section>
  );
}
