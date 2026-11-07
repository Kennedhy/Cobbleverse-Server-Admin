import { Search } from 'lucide-react';
import { Choice, Notice, Section } from '../components/ui.jsx';

const names = ['Kanto', 'Johto', 'Hoenn', 'Sinnoh', 'Unova', 'Kalos', 'Alola', 'Galar / Hisui', 'Paldea'];
const modes = [
  ['single', 'Somente uma geração'],
  ['cumulative', 'Até a geração'],
  ['custom', 'Personalizado'],
];

function included(config, generation) {
  if (config.mode === 'single') return config.selectedGeneration === generation;
  if (config.mode === 'cumulative') return generation <= config.selectedGeneration;
  return (config.generations || []).includes(generation);
}

export default function GenerationPage({ draft, update, onSearch }) {
  const changeGeneration = (generation) => {
    if (draft.mode === 'custom') {
      const current = new Set(draft.generations || []);
      current.has(generation) ? current.delete(generation) : current.add(generation);
      update({ generations: [...current].sort((a, b) => a - b) });
    } else update({ selectedGeneration: generation });
  };

  return <>
    <div className="page-intro">
      <h1>Gerações</h1>
      <p>Escolha quais gerações de espécies ficam disponíveis no servidor.</p>
    </div>
    <div className="choice-grid mode-grid" role="group" aria-label="Modo das gerações">
      {modes.map(([mode, label]) => <Choice key={mode} selected={draft.mode === mode} onClick={() => update({ mode })}>{label}</Choice>)}
    </div>
    <div className="generation-grid" role="group" aria-label="Selecionar gerações">
      {names.map((name, index) => {
        const number = index + 1;
        return <Choice key={number} selected={included(draft, number)} onClick={() => changeGeneration(number)} className="generation-choice">
          <span className="generation-number">{number}</span><span>{number} — {name}</span>
        </Choice>;
      })}
    </div>
    <button className="species-search-trigger" type="button" onClick={onSearch}>
      <Search size={19} />Pesquisar espécies e criar exceções
    </button>
    <div className="page-notes">
      <p>A geração é a de introdução da espécie. Formas e megas têm regras próprias.</p>
      <p>Gerações 5–9 não incluem novos circuitos de ginásio neste modpack.</p>
    </div>
    <Section title="Exceções de espécies" description="As exceções valem sobre a seleção geral de gerações.">
      <div className="exception-overview">
        <span><strong>{draft.allowSpecies?.length || 0}</strong> permitidas</span>
        <span><strong>{draft.denySpecies?.length || 0}</strong> bloqueadas</span>
        <button className="text-button" type="button" onClick={onSearch}>Gerenciar exceções</button>
      </div>
    </Section>
  </>;
}
