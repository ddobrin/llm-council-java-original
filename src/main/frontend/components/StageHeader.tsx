import './StageHeader.css';

interface StageHeaderProps {
  stageNumber: number;
  title: string;
  isLoading: boolean;
  loadingText: string;
  variant?: 'default' | 'agreement' | 'disagreement';
}

const variantClass: Record<string, string> = {
  default: 'stage-number',
  agreement: 'stage-number stage-number-agreement',
  disagreement: 'stage-number stage-number-disagreement',
};

export default function StageHeader({
  stageNumber,
  title,
  isLoading,
  loadingText,
  variant = 'default',
}: StageHeaderProps) {
  return (
    <header className="stage-header">
      <span className={variantClass[variant]}>{stageNumber}</span>
      <h3 className="stage-title">{title}</h3>
      {isLoading && (
        <span className="stage-loading">
          <span className="loading-spinner" />
          {loadingText}
        </span>
      )}
    </header>
  );
}
