import { useReducer, useCallback, useEffect } from 'react';
import { TextArea } from '@vaadin/react-components/TextArea';
import { Button } from '@vaadin/react-components/Button';
import { ProgressBar } from '@vaadin/react-components/ProgressBar';
import IndividualReviewPanel from '../components/IndividualReviewPanel';
import PeerRankingPanel from '../components/PeerRankingPanel';
import AnalysisPanel from '../components/AnalysisPanel';
import FinalSynthesisPanel from '../components/FinalSynthesisPanel';
import ConsensusMetricsPanel from '../components/ConsensusMetricsPanel';
import { CouncilEndpoint } from 'Frontend/generated/endpoints';
import { subscribeToFlux } from '../utils/council-utils';
import type CouncilMember from 'Frontend/generated/dev/council/model/CouncilMember';
import type IndividualResponse from 'Frontend/generated/dev/council/model/IndividualResponse';
import type IndividualRanking from 'Frontend/generated/dev/council/model/IndividualRanking';
import type IndividualAgreement from 'Frontend/generated/dev/council/model/IndividualAgreement';
import type IndividualDisagreement from 'Frontend/generated/dev/council/model/IndividualDisagreement';
import type FinalResponse from 'Frontend/generated/dev/council/model/FinalResponse';
import type AggregateRanking from 'Frontend/generated/dev/council/model/AggregateRanking';
import type AggregateAgreement from 'Frontend/generated/dev/council/model/AggregateAgreement';
import type AggregateDisagreement from 'Frontend/generated/dev/council/model/AggregateDisagreement';
import type SavedSession from 'Frontend/generated/dev/council/model/SavedSession';
import type ConsensusMetrics from 'Frontend/generated/dev/council/model/ConsensusMetrics';
import './CouncilView.css';

type FlowMode = 'quick' | 'full' | 'reviewRank';
type CouncilStage = 'idle' | 'individualReview' | 'peerRanking' | 'agreementAnalysis' | 'disagreementAnalysis' | 'finalSynthesis' | 'complete';

interface CouncilState {
  flowMode: FlowMode | null;
  currentStage: CouncilStage;
  sessionId: string | null;
  councilMembers: CouncilMember[];
  individualResponses: IndividualResponse[];
  selectedResponseTab: number;
  selectedRankingTab: number;
  peerRankings: IndividualRanking[];
  aggregateRankings: AggregateRanking[];
  labelToModel: Record<string, string>;
  agreements: IndividualAgreement[];
  aggregateAgreements: AggregateAgreement[];
  selectedAgreementTab: number;
  disagreements: IndividualDisagreement[];
  aggregateDisagreements: AggregateDisagreement[];
  selectedDisagreementTab: number;
  consensusMetrics: ConsensusMetrics | null;
  finalSynthesis: FinalResponse | null;
  error: string | null;
}

type Action =
  | { type: 'SET_MEMBERS'; members: CouncilMember[] }
  | { type: 'SET_SESSION'; sessionId: string }
  | { type: 'SET_FLOW_MODE'; flowMode: FlowMode }
  | { type: 'SET_STAGE'; stage: CouncilStage }
  | { type: 'UPDATE_INDIVIDUAL_RESPONSES'; responses: IndividualResponse[] }
  | { type: 'SET_TAB'; index: number }
  | { type: 'SET_RANKING_TAB'; index: number }
  | { type: 'UPDATE_PEER_RANKINGS'; rankings: IndividualRanking[] }
  | { type: 'SET_AGGREGATE_RANKINGS'; rankings: AggregateRanking[]; labelToModel: Record<string, string> }
  | { type: 'UPDATE_AGREEMENTS'; agreements: IndividualAgreement[] }
  | { type: 'SET_AGGREGATE_AGREEMENTS'; agreements: AggregateAgreement[] }
  | { type: 'SET_AGREEMENT_TAB'; index: number }
  | { type: 'UPDATE_DISAGREEMENTS'; disagreements: IndividualDisagreement[] }
  | { type: 'SET_AGGREGATE_DISAGREEMENTS'; disagreements: AggregateDisagreement[] }
  | { type: 'SET_DISAGREEMENT_TAB'; index: number }
  | { type: 'SET_CONSENSUS_METRICS'; metrics: ConsensusMetrics }
  | { type: 'SET_FINAL_SYNTHESIS'; response: FinalResponse }
  | { type: 'SET_ERROR'; error: string }
  | { type: 'RESET' }
  | { type: 'LOAD_SESSION'; session: SavedSession };

const initialSessionState = {
  flowMode: null as FlowMode | null,
  currentStage: 'idle' as CouncilStage,
  sessionId: null,
  individualResponses: [],
  selectedResponseTab: 0,
  selectedRankingTab: 0,
  peerRankings: [],
  aggregateRankings: [],
  labelToModel: {},
  agreements: [],
  aggregateAgreements: [],
  selectedAgreementTab: 0,
  disagreements: [],
  aggregateDisagreements: [],
  selectedDisagreementTab: 0,
  consensusMetrics: null,
  finalSynthesis: null,
  error: null,
};

const initialState: CouncilState = {
  ...initialSessionState,
  councilMembers: [],
};

function reducer(state: CouncilState, action: Action): CouncilState {
  switch (action.type) {
    case 'SET_MEMBERS':
      return { ...state, councilMembers: action.members };
    case 'SET_SESSION':
      return { ...state, sessionId: action.sessionId };
    case 'SET_FLOW_MODE':
      return { ...state, flowMode: action.flowMode };
    case 'SET_STAGE':
      return { ...state, currentStage: action.stage };
    case 'UPDATE_INDIVIDUAL_RESPONSES':
      return { ...state, individualResponses: action.responses };
    case 'SET_TAB':
      return { ...state, selectedResponseTab: action.index };
    case 'SET_RANKING_TAB':
      return { ...state, selectedRankingTab: action.index };
    case 'UPDATE_PEER_RANKINGS':
      return { ...state, peerRankings: action.rankings };
    case 'SET_AGGREGATE_RANKINGS':
      return { ...state, aggregateRankings: action.rankings, labelToModel: action.labelToModel };
    case 'UPDATE_AGREEMENTS':
      return { ...state, agreements: action.agreements };
    case 'SET_AGGREGATE_AGREEMENTS':
      return { ...state, aggregateAgreements: action.agreements };
    case 'SET_AGREEMENT_TAB':
      return { ...state, selectedAgreementTab: action.index };
    case 'UPDATE_DISAGREEMENTS':
      return { ...state, disagreements: action.disagreements };
    case 'SET_AGGREGATE_DISAGREEMENTS':
      return { ...state, aggregateDisagreements: action.disagreements };
    case 'SET_DISAGREEMENT_TAB':
      return { ...state, selectedDisagreementTab: action.index };
    case 'SET_CONSENSUS_METRICS':
      return { ...state, consensusMetrics: action.metrics };
    case 'SET_FINAL_SYNTHESIS':
      return { ...state, finalSynthesis: action.response, currentStage: 'complete' };
    case 'SET_ERROR':
      return { ...state, error: action.error, currentStage: 'idle' };
    case 'RESET':
      return { ...initialSessionState, councilMembers: state.councilMembers };
    case 'LOAD_SESSION': {
      const s = action.session.session!;
      const hasAgreements = (s.individualAgreements?.length ?? 0) > 0;
      const hasFinalSynthesis = !!s.finalResponse;
      let flowMode: FlowMode = 'reviewRank';
      if (hasFinalSynthesis && hasAgreements) flowMode = 'full';
      else if (hasFinalSynthesis) flowMode = 'quick';
      return {
        ...state,
        flowMode,
        currentStage: 'complete',
        sessionId: s.id ?? null,
        individualResponses: (s.individualResponses ?? []).filter((r): r is IndividualResponse => r !== undefined),
        selectedResponseTab: 0,
        selectedRankingTab: 0,
        peerRankings: (s.individualRankings ?? []).filter((r): r is IndividualRanking => r !== undefined),
        aggregateRankings: (s.aggregateRankings ?? []).filter((r): r is AggregateRanking => r !== undefined),
        labelToModel: Object.fromEntries(
          Object.entries(s.labelToModel ?? {}).filter((e): e is [string, string] => e[1] !== undefined)
        ),
        agreements: (s.individualAgreements ?? []).filter((r): r is IndividualAgreement => r !== undefined),
        aggregateAgreements: (s.aggregateAgreements ?? []).filter((r): r is AggregateAgreement => r !== undefined),
        selectedAgreementTab: 0,
        disagreements: (s.individualDisagreements ?? []).filter((r): r is IndividualDisagreement => r !== undefined),
        aggregateDisagreements: (s.aggregateDisagreements ?? []).filter((r): r is AggregateDisagreement => r !== undefined),
        selectedDisagreementTab: 0,
        consensusMetrics: s.consensusMetrics ?? null,
        finalSynthesis: s.finalResponse ?? null,
        error: null,
      };
    }
    default:
      return state;
  }
}

export default function CouncilView() {
  const [state, dispatch] = useReducer(reducer, initialState);
  const { flowMode, currentStage, councilMembers, individualResponses, selectedResponseTab,
    peerRankings, aggregateRankings, labelToModel, selectedRankingTab,
    agreements, aggregateAgreements, selectedAgreementTab,
    disagreements, aggregateDisagreements, selectedDisagreementTab,
    consensusMetrics, finalSynthesis, error } = state;

  // Query is kept as local UI state since it's not part of session lifecycle
  const [query, setQuery] = useReducer((_: string, v: string) => v, '');

  useEffect(() => {
    CouncilEndpoint.getCouncilMembers().then(members =>
      dispatch({ type: 'SET_MEMBERS', members })
    );
  }, []);

  // Listen for load-session events from the sidebar
  useEffect(() => {
    const handler = async (e: Event) => {
      const filename = (e as CustomEvent<string>).detail;
      try {
        const saved = await CouncilEndpoint.loadSavedSession(filename);
        dispatch({ type: 'LOAD_SESSION', session: saved });
        setQuery(saved.session?.query ?? '');
      } catch (err) {
        console.error('Failed to load session:', err);
      }
    };
    window.addEventListener('load-session', handler);
    return () => window.removeEventListener('load-session', handler);
  }, []);

  // Quick council flow: Individual Review -> Peer Ranking -> Final Synthesis (3 stages)
  const runQuickCouncil = useCallback(async () => {
    if (!query.trim()) return;

    dispatch({ type: 'RESET' });
    dispatch({ type: 'SET_FLOW_MODE', flowMode: 'quick' });

    try {
      const session = await CouncilEndpoint.createSession(query);
      const sessionId = session.id!;
      dispatch({ type: 'SET_SESSION', sessionId });

      // Stage 1: Individual Review - Collect individual responses
      dispatch({ type: 'SET_STAGE', stage: 'individualReview' });
      const responses = await subscribeToFlux(
        CouncilEndpoint.runStage1(sessionId),
        (items) => dispatch({ type: 'UPDATE_INDIVIDUAL_RESPONSES', responses: items }),
        { timeout: 180000, label: 'Stage1-Quick' }  // 3 min timeout for LLM responses
      );

      // Get authoritative label mapping from backend (matches labels used in evaluation text)
      const mapping = await CouncilEndpoint.getLabelToModel(sessionId);

      // Stage 2: Peer Ranking - Collect rankings
      // Server retrieves Stage 1 data from session state
      dispatch({ type: 'SET_STAGE', stage: 'peerRanking' });
      const rankings = await subscribeToFlux(
        CouncilEndpoint.runStage2(sessionId),
        (items) => dispatch({ type: 'UPDATE_PEER_RANKINGS', rankings: items }),
        { timeout: 180000, label: 'Stage2-Quick' }  // 3 min timeout for peer rankings
      );

      const aggRankings = await CouncilEndpoint.calculateAggregateRankings(sessionId);
      dispatch({ type: 'SET_AGGREGATE_RANKINGS', rankings: aggRankings, labelToModel: mapping });

      // Skip agreement/disagreement analysis in quick mode
      // Go directly to Final Synthesis - server retrieves all data from session state
      dispatch({ type: 'SET_STAGE', stage: 'finalSynthesis' });
      const [finalResponse] = await subscribeToFlux(
        CouncilEndpoint.runStage5(sessionId),
        (items) => {
          if (items.length > 0) {
            dispatch({ type: 'SET_FINAL_SYNTHESIS', response: items[0] });
          }
        },
        { timeout: 300000, label: 'Stage5-Quick' }  // 5 min timeout for final synthesis
      );

      // Save completed session - server retrieves all data from session state
      try {
        await CouncilEndpoint.saveCompletedSession(sessionId);
        window.dispatchEvent(new CustomEvent('session-saved'));
      } catch (saveErr) {
        console.warn('Failed to save session:', saveErr);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'An unexpected error occurred';
      dispatch({ type: 'SET_ERROR', error: message });
    }
  }, [query]);

  // Full deliberation flow: All 5 stages
  const runFullDeliberation = useCallback(async () => {
    if (!query.trim()) return;

    dispatch({ type: 'RESET' });
    dispatch({ type: 'SET_FLOW_MODE', flowMode: 'full' });

    try {
      const session = await CouncilEndpoint.createSession(query);
      const sessionId = session.id!;
      dispatch({ type: 'SET_SESSION', sessionId });

      // Stage 1: Individual Review - Collect individual responses
      dispatch({ type: 'SET_STAGE', stage: 'individualReview' });
      const responses = await subscribeToFlux(
        CouncilEndpoint.runStage1(sessionId),
        (items) => dispatch({ type: 'UPDATE_INDIVIDUAL_RESPONSES', responses: items }),
        { timeout: 180000, label: 'Stage1-Full' }  // 3 min timeout for LLM responses
      );

      // Get authoritative label mapping from backend (matches labels used in evaluation text)
      const mapping = await CouncilEndpoint.getLabelToModel(sessionId);

      // Stage 2: Peer Ranking - Server retrieves Stage 1 data from session state
      dispatch({ type: 'SET_STAGE', stage: 'peerRanking' });
      const rankings = await subscribeToFlux(
        CouncilEndpoint.runStage2(sessionId),
        (items) => dispatch({ type: 'UPDATE_PEER_RANKINGS', rankings: items }),
        { timeout: 180000, label: 'Stage2-Full' }  // 3 min timeout for peer rankings
      );

      const aggRankings = await CouncilEndpoint.calculateAggregateRankings(sessionId);
      dispatch({ type: 'SET_AGGREGATE_RANKINGS', rankings: aggRankings, labelToModel: mapping });

      // Stage 3: Agreement Analysis - Server retrieves data from session state
      dispatch({ type: 'SET_STAGE', stage: 'agreementAnalysis' });
      const agreementResults = await subscribeToFlux(
        CouncilEndpoint.runStage3(sessionId),
        (items) => dispatch({ type: 'UPDATE_AGREEMENTS', agreements: items }),
        { timeout: 180000, label: 'Stage3-Full' }  // 3 min timeout for agreement analysis
      );

      const aggAgreements = await CouncilEndpoint.calculateAggregateAgreements(sessionId);
      dispatch({ type: 'SET_AGGREGATE_AGREEMENTS', agreements: aggAgreements });

      // Stage 4: Disagreement Analysis - Server retrieves data from session state
      dispatch({ type: 'SET_STAGE', stage: 'disagreementAnalysis' });
      const disagreementResults = await subscribeToFlux(
        CouncilEndpoint.runStage4(sessionId),
        (items) => dispatch({ type: 'UPDATE_DISAGREEMENTS', disagreements: items }),
        { timeout: 180000, label: 'Stage4-Full' }  // 3 min timeout for disagreement analysis
      );

      const aggDisagreements = await CouncilEndpoint.calculateAggregateDisagreements(sessionId);
      dispatch({ type: 'SET_AGGREGATE_DISAGREEMENTS', disagreements: aggDisagreements });

      const fullMetrics = await CouncilEndpoint.calculateConsensusMetrics(sessionId);
      dispatch({ type: 'SET_CONSENSUS_METRICS', metrics: fullMetrics });

      // Stage 5: Final Synthesis - Server retrieves all data from session state
      dispatch({ type: 'SET_STAGE', stage: 'finalSynthesis' });
      const [finalResponse] = await subscribeToFlux(
        CouncilEndpoint.runStage5(sessionId),
        (items) => {
          if (items.length > 0) {
            dispatch({ type: 'SET_FINAL_SYNTHESIS', response: items[0] });
          }
        },
        { timeout: 300000, label: 'Stage5-Full' }  // 5 min timeout for final synthesis
      );

      // Save completed session - Server retrieves all data from session state
      try {
        await CouncilEndpoint.saveCompletedSession(sessionId);
        window.dispatchEvent(new CustomEvent('session-saved'));
      } catch (saveErr) {
        console.warn('Failed to save session:', saveErr);
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'An unexpected error occurred';
      dispatch({ type: 'SET_ERROR', error: message });
    }
  }, [query]);

  // Review & Rank flow: Individual Review -> Peer Ranking (2 stages, no synthesis)
  const runReviewAndRank = useCallback(async () => {
    if (!query.trim()) return;

    dispatch({ type: 'RESET' });
    dispatch({ type: 'SET_FLOW_MODE', flowMode: 'reviewRank' });

    try {
      const session = await CouncilEndpoint.createSession(query);
      const sessionId = session.id!;
      dispatch({ type: 'SET_SESSION', sessionId });

      // Stage 1: Individual Review
      dispatch({ type: 'SET_STAGE', stage: 'individualReview' });
      const responses = await subscribeToFlux(
        CouncilEndpoint.runStage1(sessionId),
        (items) => dispatch({ type: 'UPDATE_INDIVIDUAL_RESPONSES', responses: items }),
        { timeout: 180000, label: 'Stage1-ReviewRank' }  // 3 min timeout for LLM responses
      );

      // Get authoritative label mapping from backend (matches labels used in evaluation text)
      const mapping = await CouncilEndpoint.getLabelToModel(sessionId);

      // Stage 2: Peer Ranking - Server retrieves Stage 1 data from session state
      dispatch({ type: 'SET_STAGE', stage: 'peerRanking' });
      const rankings = await subscribeToFlux(
        CouncilEndpoint.runStage2(sessionId),
        (items) => dispatch({ type: 'UPDATE_PEER_RANKINGS', rankings: items }),
        { timeout: 180000, label: 'Stage2-ReviewRank' }  // 3 min timeout for peer rankings
      );

      const aggRankings = await CouncilEndpoint.calculateAggregateRankings(sessionId);
      dispatch({ type: 'SET_AGGREGATE_RANKINGS', rankings: aggRankings, labelToModel: mapping });

      // Complete — no final synthesis
      dispatch({ type: 'SET_STAGE', stage: 'complete' });
    } catch (err) {
      const message = err instanceof Error ? err.message : 'An unexpected error occurred';
      dispatch({ type: 'SET_ERROR', error: message });
    }
  }, [query]);

  const getProgress = () => {
    if (flowMode === 'quick') {
      // Quick flow: 3 stages
      switch (currentStage) {
        case 'idle': return 0;
        case 'individualReview': return 0.33;
        case 'peerRanking': return 0.66;
        case 'finalSynthesis': return 0.9;
        case 'complete': return 1;
        default: return 0;
      }
    } else if (flowMode === 'reviewRank') {
      // Review & Rank flow: 2 stages
      switch (currentStage) {
        case 'idle': return 0;
        case 'individualReview': return 0.5;
        case 'peerRanking': return 0.9;
        case 'complete': return 1;
        default: return 0;
      }
    } else {
      // Full flow: 5 stages
      switch (currentStage) {
        case 'idle': return 0;
        case 'individualReview': return 0.2;
        case 'peerRanking': return 0.4;
        case 'agreementAnalysis': return 0.6;
        case 'disagreementAnalysis': return 0.8;
        case 'finalSynthesis': return 0.95;
        case 'complete': return 1;
        default: return 0;
      }
    }
  };

  const getStageLabel = () => {
    switch (currentStage) {
      case 'idle': return 'Ready';
      case 'individualReview': return 'Individual Review: Collecting Responses...';
      case 'peerRanking': return 'Peer Ranking: Evaluating Responses...';
      case 'agreementAnalysis': return 'Agreement Analysis: Finding Consensus...';
      case 'disagreementAnalysis': return 'Disagreement Analysis: Identifying Debates...';
      case 'finalSynthesis': return 'Final Synthesis: Chairman Deliberating...';
      case 'complete': return 'Complete';
    }
  };

  const isProcessing = currentStage !== 'idle' && currentStage !== 'complete';

  return (
    <div className="council-view">
      {/* Query Input */}
      <section className="query-section">
        <TextArea
          label="Ask the Council"
          placeholder="Enter your question for the LLM Council..."
          value={query}
          onValueChanged={(e) => setQuery(e.detail.value)}
          className="query-input"
          disabled={isProcessing}
        />
        <div className="mode-cards">
          <div className="mode-card">
            <h3 className="mode-card-title">Consult the Council</h3>
            <ol className="mode-card-stages">
              <li>All models answer independently</li>
              <li>Models review &amp; rank each other (anonymously)</li>
              <li>Council Chairman synthesizes final answer</li>
            </ol>
            <div className="mode-card-action">
              <Button
                theme="primary"
                onClick={runQuickCouncil}
                disabled={!query.trim() || currentStage !== 'idle'}
              >
                {isProcessing && flowMode === 'quick' ? 'Processing...' : 'Consult the Council'}
              </Button>
            </div>
          </div>

          <div className="mode-card">
            <h3 className="mode-card-title">Council Deep Analysis</h3>
            <ol className="mode-card-stages">
              <li>All models answer independently</li>
              <li>Models review &amp; rank each other (anonymously)</li>
              <li>Models identify agreements</li>
              <li>Models identify disagreements</li>
              <li>Council Chairman synthesizes final answer</li>
            </ol>
            <div className="mode-card-action">
              <Button
                theme="primary"
                onClick={runFullDeliberation}
                disabled={!query.trim() || currentStage !== 'idle'}
              >
                {isProcessing && flowMode === 'full' ? 'Processing...' : 'Council Deep Analysis'}
              </Button>
            </div>
          </div>

          <div className="mode-card">
            <h3 className="mode-card-title">Review &amp; Rank</h3>
            <ol className="mode-card-stages">
              <li>All models answer independently</li>
              <li>Models review &amp; rank each other (anonymously)</li>
            </ol>
            <div className="mode-card-action">
              <Button
                theme="primary"
                onClick={runReviewAndRank}
                disabled={!query.trim() || currentStage !== 'idle'}
              >
                {isProcessing && flowMode === 'reviewRank' ? 'Processing...' : 'Review & Rank'}
              </Button>
            </div>
          </div>
        </div>

        {currentStage === 'complete' && (
          <div className="new-session-action">
            <Button theme="tertiary" onClick={() => { dispatch({ type: 'RESET' }); setQuery(''); }}>
              Start New Session
            </Button>
          </div>
        )}
      </section>

      {/* Error Banner */}
      {error && (
        <section className="error-section">
          <span>Council Error: {error}</span>
          <Button theme="tertiary" onClick={() => { dispatch({ type: 'RESET' }); setQuery(''); }}>Dismiss</Button>
        </section>
      )}

      {/* Progress */}
      {currentStage !== 'idle' && !error && (
        <section className="progress-section">
          <div className="progress-label">{getStageLabel()}</div>
          <ProgressBar value={getProgress()} />
        </section>
      )}

      {/* Stage 1: Individual Review - always stage 1 */}
      {individualResponses.length > 0 && (
        <IndividualReviewPanel
          stageNumber={1}
          responses={individualResponses}
          selectedTab={selectedResponseTab}
          onTabChange={(index) => dispatch({ type: 'SET_TAB', index })}
          isLoading={currentStage === 'individualReview'}
          councilMembers={councilMembers}
        />
      )}

      {/* Stage 2: Peer Ranking - always stage 2 */}
      {peerRankings.length > 0 && (
        <PeerRankingPanel
          stageNumber={2}
          rankings={peerRankings}
          aggregateRankings={aggregateRankings}
          labelToModel={labelToModel}
          isLoading={currentStage === 'peerRanking'}
          councilMembers={councilMembers}
          selectedTab={selectedRankingTab}
          onTabChange={(index) => dispatch({ type: 'SET_RANKING_TAB', index })}
        />
      )}

      {/* Stage 3: Agreement Analysis - only in full mode */}
      {flowMode === 'full' && agreements.length > 0 && (
        <AnalysisPanel
          variant="agreement"
          stageNumber={3}
          items={agreements}
          isLoading={currentStage === 'agreementAnalysis'}
          councilMembers={councilMembers}
          labelToModel={labelToModel}
          selectedTab={selectedAgreementTab}
          onTabChange={(index) => dispatch({ type: 'SET_AGREEMENT_TAB', index })}
        />
      )}

      {/* Stage 4: Disagreement Analysis - only in full mode */}
      {flowMode === 'full' && disagreements.length > 0 && (
        <AnalysisPanel
          variant="disagreement"
          stageNumber={4}
          items={disagreements}
          isLoading={currentStage === 'disagreementAnalysis'}
          councilMembers={councilMembers}
          labelToModel={labelToModel}
          selectedTab={selectedDisagreementTab}
          onTabChange={(index) => dispatch({ type: 'SET_DISAGREEMENT_TAB', index })}
        />
      )}

      {/* Consensus Metrics */}
      {consensusMetrics && (
        <ConsensusMetricsPanel
          metrics={consensusMetrics}
          showDisagreementSeverity={flowMode === 'full'}
        />
      )}

      {/* Final Synthesis - stage 3 in quick mode, stage 5 in full mode */}
      {finalSynthesis && (
        <FinalSynthesisPanel
          stageNumber={flowMode === 'quick' ? 3 : 5}
          response={finalSynthesis}
          isLoading={currentStage === 'finalSynthesis'}
        />
      )}
    </div>
  );
}
