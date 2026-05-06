import { Tabs } from '@vaadin/react-components/Tabs';
import { Tab } from '@vaadin/react-components/Tab';
import type IndividualAgreement from 'Frontend/generated/dev/council/model/IndividualAgreement';
import type IndividualDisagreement from 'Frontend/generated/dev/council/model/IndividualDisagreement';
import type CouncilMember from 'Frontend/generated/dev/council/model/CouncilMember';
import { getAvatarColor } from '../utils/council-utils';
import StageHeader from './StageHeader';
import './StageHeader.css';
import './AnalysisPanel.css';

type AgreementProps = {
  variant: 'agreement';
  stageNumber: number;
  items: IndividualAgreement[];
  councilMembers: CouncilMember[];
  labelToModel: Record<string, string>;
  isLoading: boolean;
  selectedTab: number;
  onTabChange: (index: number) => void;
};

type DisagreementProps = {
  variant: 'disagreement';
  stageNumber: number;
  items: IndividualDisagreement[];
  councilMembers: CouncilMember[];
  labelToModel: Record<string, string>;
  isLoading: boolean;
  selectedTab: number;
  onTabChange: (index: number) => void;
};

type Props = AgreementProps | DisagreementProps;

const config = {
  agreement: {
    title: 'Agreement Analysis',
    loadingText: 'Identifying agreements...',
    pointsClass: 'agreement-points',
    pointClass: 'agreement-point',
    chipClass: 'response-chip agreement-chip',
  },
  disagreement: {
    title: 'Disagreement Analysis',
    loadingText: 'Identifying disagreements...',
    pointsClass: 'disagreement-points',
    pointClass: 'disagreement-point',
    chipClass: 'response-chip disagreement-chip',
  },
} as const;

export default function AnalysisPanel(props: Props) {
  const { variant, stageNumber, items, councilMembers, labelToModel, isLoading, selectedTab, onTabChange } = props;
  const c = config[variant];
  const selectedItem = items[selectedTab];

  return (
    <section className="stage-panel">
      <StageHeader
        stageNumber={stageNumber}
        title={c.title}
        isLoading={isLoading}
        loadingText={c.loadingText}
        variant={variant}
      />

      <Tabs
        selected={selectedTab}
        onSelectedChanged={(e) => onTabChange(e.detail.value)}
        className="response-tabs"
      >
        {items.map((item, index) => (
          <Tab key={item.evaluatorModelId ?? index}>
            <span
              className="model-avatar"
              style={{ backgroundColor: getAvatarColor(item.evaluatorModelId, councilMembers) }}
            />
            {item.evaluatorModelName}
          </Tab>
        ))}
      </Tabs>

      {selectedItem && (
        <div>
          <div className="analysis-content">
            <div className={c.pointsClass}>
              {variant === 'agreement'
                ? renderAgreementPoints(selectedItem as IndividualAgreement, c.pointClass, c.chipClass, labelToModel, councilMembers)
                : renderDisagreementPoints(selectedItem as IndividualDisagreement, c.pointClass, c.chipClass, labelToModel, councilMembers)}
            </div>
          </div>
          <div className="response-meta">
            <span>Model: {selectedItem.evaluatorModelId}</span>
            <span>Duration: {selectedItem.durationMs}ms</span>
            <span>Tokens: {'totalTokens' in selectedItem && (selectedItem as IndividualAgreement).totalTokens > 0
              ? (selectedItem as IndividualAgreement).totalTokens.toLocaleString()
              : 'N/A'}</span>
          </div>
        </div>
      )}

    </section>
  );
}

function renderAgreementPoints(item: IndividualAgreement, pointClass: string, chipClass: string, labelToModel: Record<string, string>, councilMembers: CouncilMember[]) {
  return item.agreements?.filter(Boolean).map((point, i) => (
    <div key={i} className={pointClass}>
      <div className="point-topic">{point?.topic}</div>
      <div className="point-description">{point?.description}</div>
      <div className="point-responses">
        {point?.agreeingResponses?.filter(Boolean).map((resp, j) => (
          <span key={j} className={chipClass}>
            {resp}
            {resp && labelToModel[resp] && (
              <span className="chip-model-name">
                ({councilMembers.find(m => m.modelId === labelToModel[resp])?.name ?? labelToModel[resp]})
              </span>
            )}
          </span>
        ))}
      </div>
    </div>
  ));
}

function renderDisagreementPoints(item: IndividualDisagreement, pointClass: string, chipClass: string, labelToModel: Record<string, string>, councilMembers: CouncilMember[]) {
  return item.disagreements?.filter(Boolean).map((point, i) => (
    <div key={i} className={pointClass}>
      <div className="point-topic">{point?.topic}</div>
      <div className="point-description">{point?.description}</div>
      <div className="positions-list">
        {point?.positions?.filter(Boolean).map((pos, j) => {
          const rl = pos?.responseLabel;
          return (
          <div key={j} className="position-item">
            <span className={chipClass}>
              {rl}
              {rl && labelToModel[rl] && (
                <span className="chip-model-name">
                  ({councilMembers.find(m => m.modelId === labelToModel[rl])?.name ?? labelToModel[rl]})
                </span>
              )}
            </span>
            <span className="position-stance">{pos?.stance}</span>
          </div>
          );
        })}
      </div>
    </div>
  ));
}

