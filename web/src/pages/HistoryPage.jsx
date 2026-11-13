import { History, RefreshCw, RotateCcw } from 'lucide-react';
import { useEffect, useState } from 'react';
import { request } from '../api.js';
import { Loading, Notice, Section } from '../components/ui.jsx';

const labels = {
  selectedGeneration: 'Geração selecionada', generations: 'Gerações permitidas', mode: 'Modo de geração',
  manageGyms: 'Gerenciar ginásios', manageExperience: 'Gerenciar XP e níveis', globalXpMultiplier: 'Multiplicador global de XP',
  globalLevelCap: 'Limite global de nível', levelCapMode: 'Origem do limite de nível', wildLevelBehavior: 'Comportamento do nível selvagem',
  blockOverCapPokemonInBattles: 'Bloquear batalhas acima do limite', overCapBattleBehavior: 'Regra acima do cap em PvE', captureMultiplier: 'Multiplicador de captura',
  regions: 'Regiões permitidas', initialRegion: 'Região inicial', excludedSpecies: 'Espécies excluídas', includedSpecies: 'Exceções permitidas',
  playerXpGroups: 'Grupos de XP por jogador', groupXpMultipliers: 'Multiplicadores dos grupos', playerXpMultipliers: 'Multiplicadores individuais',
  automaticRegions: 'Regiões automáticas', regionalForms: 'Formas regionais', megaEvolutions: 'Megaevoluções',
  restrictExistingPokemon: 'Restringir Pokémon já capturados', rescueStarter: 'Resgate de inicial',
  filterGyms: 'Filtrar ginásios', filterTrainerTeams: 'Filtrar equipes de treinadores',
  allowSpecies: 'Espécies liberadas', denySpecies: 'Espécies bloqueadas', allowForms: 'Formas liberadas', denyForms: 'Formas bloqueadas',
};

function displayValue(value) {
  if (value == null || value === '(ausente)') return '—';
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.join(', ') || 'Nenhum';
    if (parsed && typeof parsed === 'object') return JSON.stringify(parsed);
  } catch { /* Plain string or number. */ }
  return value === 'true' ? 'Ativado' : value === 'false' ? 'Desativado' : value;
}

export default function HistoryPage({ state, onRestoreRevision, onRestore, onDiscard, canEdit, busy }) {
  const [refreshKey, setRefreshKey] = useState(0);
  const [entries, setEntries] = useState(null);
  const [entryError, setEntryError] = useState('');
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [detailError, setDetailError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setEntries(null); setEntryError('');
    request('/api/history', { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setEntries(result.entries);
    }).catch((error) => {
      if (!controller.signal.aborted) setEntryError(error.message);
    });
    return () => controller.abort();
  }, [refreshKey]);

  useEffect(() => {
    if (selected == null) { setDetail(null); setDetailError(''); return undefined; }
    const controller = new AbortController();
    setDetail(null); setDetailError('');
    request(`/api/history?revision=${selected}`, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setDetail(result);
    }).catch((error) => {
      if (!controller.signal.aborted) setDetailError(error.message);
    });
    return () => controller.abort();
  }, [selected]);

  return <>
    <div className="page-intro"><h1>Histórico</h1><p>Compare alterações salvas e prepare uma revisão anterior para o próximo reinício.</p></div>
    {state.startupWarning && <Notice tone="warning">{state.startupWarning}</Notice>}
    {canEdit && (state.hasPrevious || state.pending) && <Section title="Recuperação">
      <div className="recovery-actions">
        {state.hasPrevious && <button className="button button-quiet" type="button" disabled={busy} onClick={onRestore}>Restaurar revisão anterior</button>}
        {state.pending && <button className="button button-danger" type="button" disabled={busy} onClick={onDiscard}>Descartar alterações pendentes</button>}
      </div>
    </Section>}
    <Section title="Revisões recentes" description="Até 50 alterações. O histórico é somente leitura; restaurar cria uma nova revisão pendente.">
      <div className="section-toolbar history-toolbar">
        <span className="field-hint">Revisão ativa {state.active.revision}{state.pending && ` · pendente ${state.pending.revision}`}</span>
        <button className="button button-quiet button-small" type="button" onClick={() => { setSelected(null); setRefreshKey((key) => key + 1); }}>
          <RefreshCw size={15} />Atualizar
        </button>
      </div>
      {entryError && <Notice tone="error">{entryError}</Notice>}
      {!entries && !entryError && <Loading>Carregando histórico…</Loading>}
      {entries?.length > 0 && <div className="table-wrap history-table-wrap"><table>
        <thead><tr><th>Revisão</th><th>Data</th><th>Administrador</th><th>Campos</th><th /></tr></thead>
        <tbody>{entries.map((entry) => <tr key={entry.revision}>
          <td><strong>{entry.revision}</strong><small>Base ativa {entry.basedOnRevision}</small></td>
          <td>{entry.time ? new Date(entry.time).toLocaleString('pt-BR') : '—'}</td>
          <td>{entry.author}</td><td>{entry.changedFields}</td>
          <td><button className="button button-quiet button-small" type="button" aria-expanded={selected === entry.revision}
            onClick={() => setSelected(selected === entry.revision ? null : entry.revision)}>
            <History size={15} />{selected === entry.revision ? 'Fechar' : 'Comparar'}
          </button></td>
        </tr>)}</tbody>
      </table></div>}
      {entries?.length === 0 && <Notice>Nenhuma alteração salva foi encontrada ainda.</Notice>}
    </Section>
    {selected != null && <Section title={`Comparação da revisão ${selected}`} description={detail ? `Compara a configuração anterior à revisão ${detail.revision} com a configuração salva nesta revisão.` : undefined}>
      {detailError && <Notice tone="error">{detailError}</Notice>}
      {!detail && !detailError && <Loading>Carregando comparação…</Loading>}
      {detail && <>
        {detail.changes.length === 0 ? <Notice>Esta revisão não alterou campos da configuração.</Notice> :
          <div className="history-diff">{detail.changes.map((change) => <div className="history-diff-row" key={change.field}>
            <strong>{labels[change.field] || change.field}</strong>
            <div><span>Antes</span><code>{displayValue(change.before)}</code></div>
            <div><span>Depois</span><code>{displayValue(change.after)}</code></div>
          </div>)}</div>}
        <button className="button button-quiet history-restore" type="button" disabled={!canEdit || !detail.changes.length || Boolean(state.pending)}
          onClick={() => onRestoreRevision(detail.revision)}>
          <RotateCcw size={16} />Preparar esta revisão
        </button>
        {!canEdit && <p className="field-hint">Esta sessão tem permissão de consulta. Use o token de administrador para restaurar uma revisão.</p>}
        {canEdit && state.pending && <p className="field-hint">Descarte a revisão pendente antes de preparar uma revisão do histórico.</p>}
      </>}
    </Section>}
  </>;
}
