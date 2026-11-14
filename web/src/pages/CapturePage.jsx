import { RefreshCw, Search } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { request } from '../api.js';
import { Notice, NumberField, Section, Toggle } from '../components/ui.jsx';

const generations = [
  { value: 'all', label: 'Todas as gerações' },
  { value: '1', label: '1 — Kanto' }, { value: '2', label: '2 — Johto' },
  { value: '3', label: '3 — Hoenn' }, { value: '4', label: '4 — Sinnoh' },
  { value: '5', label: '5 — Unova' }, { value: '6', label: '6 — Kalos' },
  { value: '7', label: '7 — Alola' }, { value: '8', label: '8 — Galar e Hisui' },
  { value: '9', label: '9 — Paldea' },
];

function normalized(value) {
  return String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
}

export default function CapturePage({ draft, update, canEdit, state }) {
  const [world, setWorld] = useState(null);
  const [loadError, setLoadError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const [generation, setGeneration] = useState('all');
  const [search, setSearch] = useState('');
  const liveRevision = state.liveGameplayRevision;

  useEffect(() => {
    const controller = new AbortController();
    setLoadError('');
    request('/api/legendary-limits', { signal: controller.signal }).then((data) => {
      if (!controller.signal.aborted) setWorld(data);
    }).catch((error) => {
      if (!controller.signal.aborted) setLoadError(error.message);
    });
    return () => controller.abort();
  }, [refreshKey, liveRevision]);

  const entries = useMemo(() => {
    const term = normalized(search);
    return (world?.entries || []).filter((entry) =>
      (generation === 'all' || String(entry.generation) === generation)
      && (!term || normalized(`${entry.name} ${entry.species} ${entry.form || ''} ${entry.generationName}`).includes(term)));
  }, [world, generation, search]);

  const changeLimit = (key, value) => update({ legendaryLimits: { ...(draft.legendaryLimits || {}), [key]: value } });

  return <>
    <div className="page-intro"><h1>Captura e lendários</h1><p>Configure a chance de captura, a taxa de shiny e a quantidade global de cada lendário neste mundo.</p></div>
    <Section title="Chance de captura" description="Os fatores do Cobblemon e os efeitos próprios de cada Poké Bola continuam participando do cálculo.">
      <div className="capture-control">
        <NumberField label="Multiplicador global da chance" value={draft.captureMultiplier} min={0} max={100} step={0.05} suffix="×"
          onChange={(captureMultiplier) => update({ captureMultiplier })} disabled={!canEdit} hint="1,00× mantém a chance original; 0 desativa capturas comuns." />
        <NumberField label="Multiplicador para lendários" value={draft.legendaryCaptureMultiplier ?? 1} min={0} max={100} step={0.05} suffix="×"
          onChange={(legendaryCaptureMultiplier) => update({ legendaryCaptureMultiplier })} disabled={!canEdit} hint="Multiplica também o valor global. Ex.: 0,5× reduz pela metade a chance lendária." />
        <div className="capture-explainer">
          <p><strong>As Poké Bolas continuam fazendo diferença.</strong></p>
          <p>O multiplicador lendário é adicional ao global. As diferenças entre bolas, status, vida e demais fatores permanecem ativas.</p>
        </div>
      </div>
    </Section>
    <Notice tone="warning">O limite global impede capturas lendárias quando a quantidade do mundo é alcançada, inclusive com Master Ball. É necessário usar o calculador padrão do Cobblemon; outro calculador é recusado na validação.</Notice>
    <Section title="Limites lendários do mundo" description="O total é compartilhado por todos os jogadores e inclui Pokémon em equipes, PCs offline e custódia do GTS.">
      <div className="legendary-toolbar">
        <div className="legendary-search"><Search size={16} aria-hidden="true" /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Buscar Pokémon ou geração" aria-label="Buscar lendário" /></div>
        <select value={generation} onChange={(event) => setGeneration(event.target.value)} aria-label="Filtrar por geração">
          {generations.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
        <button className="button button-quiet button-small" type="button" onClick={() => setRefreshKey((value) => value + 1)}>
          <RefreshCw size={14} />Atualizar contagem
        </button>
      </div>
      {loadError && <Notice tone="error">{loadError}</Notice>}
      {world?.status === 'scanning' && <Notice tone="warning">{world.message} {world.scannedPlayers} de {world.totalPlayers} jogadores indexados.</Notice>}
      {world?.status === 'failed' && <Notice tone="error">{world.message}</Notice>}
      {world?.status === 'ready' && <div className="legendary-index-ready"><span className="status-dot" />Contagem do mundo atualizada por eventos do Cobblemon.</div>}
      {!world && !loadError && <p className="field-hint">Carregando a contagem atual do mundo…</p>}
      {world?.entries?.length > 0 && <div className="table-wrap legendary-table-wrap"><table className="legendary-table">
        <thead><tr><th>Pokémon</th><th>Geração</th><th>No mundo (teto ativo)</th><th>Limite editável</th></tr></thead>
        <tbody>{entries.map((entry) => {
          const count = world.counts?.[entry.key] || 0;
          const rawLimit = draft.legendaryLimits?.[entry.key] ?? entry.defaultLimit;
          const activeLimit = Number(world.activeLimits?.[entry.key] ?? entry.defaultLimit);
          return <tr key={entry.key}>
            <td><strong>{entry.name}</strong>{entry.form && <small>Forma regional</small>}</td>
            <td>{entry.generation} — {entry.generationName}</td>
            <td><span className={`legendary-count${world.ready && activeLimit >= 0 && count >= activeLimit ? ' is-at-limit' : ''}`}>{world.ready ? count : '—'}<small>{activeLimit >= 0 ? ` / ${activeLimit}` : ' / Vários'}</small></span></td>
            <td><label className="legendary-limit-input"><input type="number" min="-1" max="1000" step="1" aria-label={`Limite global de ${entry.name}`}
              value={rawLimit} disabled={!canEdit} onChange={(event) => {
                const text = event.target.value;
                changeLimit(entry.key, text === '' || text === '-' ? text : Number(text));
              }} /><span>{rawLimit === -1 || rawLimit === '-1' ? 'Vários' : 'Pokémon'}</span></label></td>
          </tr>;
        })}</tbody>
      </table>{entries.length === 0 && <div className="empty-state">Nenhum lendário corresponde à busca.</div>}</div>}
      <p className="field-hint legendary-help">Use <strong>-1</strong> para permitir vários. O painel conta Pokémon guardados e em custódia; Pokémon selvagens soltos no mundo não entram no total.</p>
    </Section>
    <Section title="Taxa de shiny" description="Defina a chance usada pelo Cobblemon para gerar Pokémon shiny.">
      <Toggle label="Definir taxa personalizada" checked={draft.shinyRate != null}
        disabled={!canEdit} onChange={(enabled) => update({ shinyRate: enabled ? 2048 : null })} />
      {draft.shinyRate != null && <NumberField label="Chance de 1 em" value={draft.shinyRate} min={1} max={1000000} step={1}
        onChange={(shinyRate) => update({ shinyRate })} disabled={!canEdit} hint="Por exemplo, 2048 corresponde a uma chance em 2048." />}
      <p className="field-hint">A taxa também vale para outros sistemas que utilizam a configuração global de shiny do Cobblemon.</p>
    </Section>
  </>;
}
