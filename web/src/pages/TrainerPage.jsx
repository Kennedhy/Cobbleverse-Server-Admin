import { useState } from 'react';
import { Notice } from '../components/ui.jsx';
import TrainerDifficultyPage from './TrainerDifficultyPage.jsx';
import TrainerOperationsPage from './TrainerOperationsPage.jsx';

export default function TrainerPage({ state, canEdit, dirty }) {
  const [section, setSection] = useState('operations');

  return <>
    <div className="trainer-page-tabs" role="group" aria-label="Configurações de treinadores">
      <button type="button" className={`button ${section === 'operations' ? 'button-accent' : 'button-quiet'}`}
        aria-pressed={section === 'operations'} onClick={() => setSection('operations')}>
        Aparições e recompensas
      </button>
      <button type="button" className={`button ${section === 'difficulty' ? 'button-accent' : 'button-quiet'}`}
        aria-pressed={section === 'difficulty'} onClick={() => setSection('difficulty')}>
        Dificuldade por grupos
      </button>
    </div>
    {dirty && <Notice tone="warning">Há um rascunho não salvo nas regras gerais. Volte à página correspondente para validar e salvar.</Notice>}
    {section === 'operations'
      ? <TrainerOperationsPage state={state} />
      : <TrainerDifficultyPage state={state} canEdit={canEdit} />}
  </>;
}
