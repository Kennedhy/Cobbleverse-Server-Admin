import { ChevronLeft, ChevronRight, LoaderCircle, Pencil, Plus, RefreshCw, Search, Trash2, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { request } from '../api.js';
import { Loading, Notice, Section } from '../components/ui.jsx';

const statFields = [
  ['hp', 'PS'], ['atk', 'Ataque'], ['def', 'Defesa'],
  ['spa', 'At. Esp.'], ['spd', 'Def. Esp.'], ['spe', 'Velocidade'],
];

function readableId(id = '') {
  return id.split(':').at(-1).replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function statPayload(values, fallback) {
  const hasValue = statFields.some(([key]) => values[key] !== '');
  if (!hasValue) return null;
  return Object.fromEntries(statFields.map(([key]) => [key, values[key] === '' ? fallback : Number(values[key])]));
}

function PokemonEditorDialog({ action, player, box, position, pokemon, onSave, onClose }) {
  const [draft, setDraft] = useState(() => ({
    species: pokemon?.species || '',
    form: pokemon?.form || '',
    level: String(pokemon?.level ?? 1),
    shiny: Boolean(pokemon?.shiny),
    nickname: pokemon?.nickname || '',
    nature: '',
    ability: '',
    moves: '',
    ivs: Object.fromEntries(statFields.map(([key]) => [key, pokemon?.ivs?.[key] === undefined ? '' : String(pokemon.ivs[key])])),
    evs: Object.fromEntries(statFields.map(([key]) => [key, pokemon?.evs?.[key] === undefined ? '' : String(pokemon.evs[key])])),
  }));
  const [speciesQuery, setSpeciesQuery] = useState(pokemon?.species ? readableId(pokemon.species) : '');
  const [speciesResults, setSpeciesResults] = useState([]);
  const [speciesReady, setSpeciesReady] = useState(true);
  const [speciesLoading, setSpeciesLoading] = useState(false);
  const [failure, setFailure] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const term = speciesQuery.trim();
    if (term.length < 2) {
      setSpeciesResults([]);
      setSpeciesLoading(false);
      return undefined;
    }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      setSpeciesLoading(true);
      try {
        const result = await request(`/api/species?q=${encodeURIComponent(term)}`, { signal: controller.signal });
        setSpeciesResults(result.items || []);
        setSpeciesReady(result.ready !== false);
      } catch (error) {
        if (!controller.signal.aborted) setFailure(error.message);
      } finally {
        if (!controller.signal.aborted) setSpeciesLoading(false);
      }
    }, 250);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [speciesQuery]);

  const update = (key, value) => setDraft((current) => ({ ...current, [key]: value }));
  const selectSpecies = (entry) => {
    setDraft((current) => ({ ...current, species: entry.id, form: current.species === entry.id ? current.form : '' }));
    setSpeciesQuery(entry.name);
    setFailure('');
  };
  const updateStat = (kind, key, value) => setDraft((current) => ({
    ...current,
    [kind]: { ...current[kind], [key]: value },
  }));

  const submit = async (event) => {
    event.preventDefault();
    if (!draft.species) { setFailure('Pesquise e selecione uma espécie do catálogo.'); return; }
    const level = Number(draft.level);
    if (!Number.isInteger(level) || level < 1 || level > 100) { setFailure('O nível deve ficar entre 1 e 100.'); return; }
    const body = {
      action,
      player: player.uuid,
      box,
      position,
      expectedPokemonUuid: pokemon?.pokemonUuid || null,
      species: draft.species,
      form: draft.form.trim(),
      level,
      shiny: draft.shiny,
      nickname: draft.nickname,
      nature: draft.nature.trim() || null,
      ability: draft.ability.trim() || null,
      moves: draft.moves.trim() ? draft.moves.split(',').map((move) => move.trim()).filter(Boolean) : null,
      ivs: statPayload(draft.ivs, 31),
      evs: statPayload(draft.evs, 0),
    };
    setSaving(true);
    setFailure('');
    try {
      await onSave(body);
      onClose();
    } catch (error) {
      setFailure(error.message);
      setSaving(false);
    }
  };

  return <div className="dialog-shade" role="presentation" onMouseDown={(event) => {
    if (!saving && event.target === event.currentTarget) onClose();
  }}>
    <section className="species-dialog pc-editor-dialog" role="dialog" aria-modal="true" aria-labelledby="pc-editor-title">
      <header className="dialog-head">
        <div><h2 id="pc-editor-title">{action === 'add' ? 'Adicionar Pokémon' : 'Editar Pokémon'}</h2>
          <p>{player.name} · Caixa {box + 1} · Espaço {position + 1}</p></div>
        <button className="icon-button" type="button" aria-label="Fechar" disabled={saving} onClick={onClose}><X size={19} /></button>
      </header>
      <form className="pc-editor-form" onSubmit={submit}>
        <label className="field"><span className="field-label">Espécie</span>
          <span className="search-field"><Search size={17} aria-hidden="true" />
            <input autoFocus value={speciesQuery} maxLength={64} placeholder="Pesquise por nome ou ID, por exemplo pikachu"
              onChange={(event) => {
                const value = event.target.value;
                setSpeciesQuery(value);
                setDraft((current) => ({ ...current, species: '', form: '' }));
              }} />
          </span>
          {draft.species && <span className="field-hint">Selecionado: {draft.species}</span>}
        </label>
        {speciesLoading && <div className="dialog-loading"><LoaderCircle className="spin" size={17} />Buscando no catálogo carregado…</div>}
        {!speciesReady && <p className="empty-state">O catálogo aparece depois que os dados do servidor carregarem.</p>}
        {!speciesLoading && speciesQuery.trim().length >= 2 && speciesResults.length > 0 && <div className="pc-species-results">
          {speciesResults.map((entry) => <button className={`pc-species-option${draft.species === entry.id ? ' is-selected' : ''}`} type="button" key={entry.id}
            onClick={() => selectSpecies(entry)}><strong>{entry.name}</strong><span>{entry.id} · #{entry.dex} · Geração {entry.generation}</span></button>)}
        </div>}
        {!speciesLoading && speciesQuery.trim().length >= 2 && speciesReady && speciesResults.length === 0 && <p className="empty-state">Nenhuma espécie encontrada.</p>}

        <div className="pc-editor-primary-grid">
          <label className="field"><span className="field-label">Nível</span>
            <input type="number" min="1" max="100" value={draft.level} onChange={(event) => update('level', event.target.value)} />
          </label>
          <label className="field"><span className="field-label">Apelido</span>
            <input maxLength="64" value={draft.nickname} placeholder="Sem apelido" onChange={(event) => update('nickname', event.target.value)} />
          </label>
          <label className="field"><span className="field-label">Forma (ID)</span>
            <input maxLength="64" value={draft.form} placeholder="Padrão" onChange={(event) => update('form', event.target.value)} />
            <span className="field-hint">Ex.: alola, galar ou uma forma específica do modpack.</span>
          </label>
          <label className="pc-editor-check"><input type="checkbox" checked={draft.shiny} onChange={(event) => update('shiny', event.target.checked)} /> Pokémon shiny</label>
        </div>

        <details className="pc-editor-advanced">
          <summary>Natureza, habilidade, golpes, IVs e EVs</summary>
          <div className="pc-editor-advanced-content">
            <div className="pc-editor-primary-grid">
              <label className="field"><span className="field-label">Natureza</span>
                <input maxLength="64" value={draft.nature} placeholder={pokemon?.nature || 'jolly'} onChange={(event) => update('nature', event.target.value)} />
                <span className="field-hint">Deixe vazio para manter a natureza atual.</span>
              </label>
              <label className="field"><span className="field-label">Habilidade</span>
                <input maxLength="64" value={draft.ability} placeholder={pokemon?.ability || 'static'} onChange={(event) => update('ability', event.target.value)} />
                <span className="field-hint">Deixe vazio para manter a habilidade atual.</span>
              </label>
            </div>
            <label className="field"><span className="field-label">Golpes</span>
              <input maxLength="260" value={draft.moves} placeholder={pokemon?.moves?.join(', ') || 'tackle, thunderbolt'} onChange={(event) => update('moves', event.target.value)} />
              <span className="field-hint">Separe até quatro IDs por vírgula. Deixe vazio para manter os golpes atuais.</span>
            </label>
            <div className="pc-editor-stat-group"><strong>IVs · de 0 a 31</strong>
              <div className="pc-editor-stats">{statFields.map(([key, label]) => <label className="field" key={`iv-${key}`}>
                <span className="field-label">{label}</span><input type="number" min="0" max="31" value={draft.ivs[key]}
                  onChange={(event) => updateStat('ivs', key, event.target.value)} /></label>)}</div>
            </div>
            <div className="pc-editor-stat-group"><strong>EVs · até 252 por atributo e 510 no total</strong>
              <div className="pc-editor-stats">{statFields.map(([key, label]) => <label className="field" key={`ev-${key}`}>
                <span className="field-label">{label}</span><input type="number" min="0" max="252" value={draft.evs[key]}
                  onChange={(event) => updateStat('evs', key, event.target.value)} /></label>)}</div>
            </div>
          </div>
        </details>

        {failure && <Notice tone="error">{failure}</Notice>}
        <footer className="dialog-foot pc-editor-footer">
          <button className="button button-quiet" type="button" disabled={saving} onClick={onClose}>Cancelar</button>
          <button className="button button-accent" type="submit" disabled={saving || !draft.species}>
            {saving ? <><LoaderCircle className="spin" size={16} />Salvando…</> : action === 'add' ? 'Adicionar ao PC' : 'Salvar alterações'}
          </button>
        </footer>
      </form>
    </section>
  </div>;
}

function PcBox({ box, selected, canEdit, onSelect, onAdd }) {
  const slots = new Map(box.slots.map((pokemon) => [pokemon.position, pokemon]));
  return <ol className="pc-grid" aria-label="Espaços da caixa">
    {Array.from({ length: 30 }, (_, position) => {
      const pokemon = slots.get(position);
      return <li className={pokemon ? 'pc-slot pc-slot-filled' : 'pc-slot'} key={position}>
        <span className="pc-slot-number">{position + 1}</span>
        {pokemon ? <button className={`pc-slot-button${selected === position ? ' is-selected' : ''}`} type="button"
          aria-pressed={selected === position} onClick={() => onSelect(pokemon)}>
          <strong>{pokemon.nickname || (pokemon.species ? readableId(pokemon.species) : 'Espécie desconhecida')}</strong>
          <span>Nv. {pokemon.level}{pokemon.shiny && <span className="pc-shiny"> · Shiny</span>}</span>
          {pokemon.form && !['normal', 'cobblemon:normal'].includes(pokemon.form.toLowerCase()) && <span>{readableId(pokemon.form)}</span>}
        </button> : canEdit ? <button className="pc-slot-add" type="button" onClick={() => onAdd(position)}><Plus size={14} />Adicionar</button> : <span>Vazio</span>}
      </li>;
    })}
  </ol>;
}

function StatList({ title, values }) {
  const labels = { hp: 'PS', atk: 'Ataque', def: 'Defesa', spa: 'At. Esp.', spd: 'Def. Esp.', spe: 'Velocidade' };
  const entries = Object.entries(values || {});
  return <div className="pc-detail-stat"><span>{title}</span>
    <strong>{entries.length ? entries.map(([key, value]) => {
      const stat = key.toLowerCase().split(':').at(-1);
      return `${labels[stat] || readableId(stat)} ${value}`;
    }).join(' · ') : 'Sem dados'}</strong>
  </div>;
}

function PokemonDetails({ pokemon }) {
  return <div className="pc-details" aria-live="polite">
    <div className="pc-details-heading"><span>Pokémon selecionado</span><strong>{pokemon.nickname || readableId(pokemon.species)} · Nv. {pokemon.level}</strong></div>
    <div className="pc-details-grid">
      <div className="pc-detail-stat"><span>Espécie / forma</span><strong>{readableId(pokemon.species)}{pokemon.form ? ` · ${readableId(pokemon.form)}` : ''}</strong></div>
      <div className="pc-detail-stat"><span>Natureza</span><strong>{pokemon.nature || 'Sem dados'}</strong></div>
      <div className="pc-detail-stat"><span>Habilidade</span><strong>{pokemon.ability ? readableId(pokemon.ability) : 'Sem dados'}</strong></div>
      <div className="pc-detail-stat pc-detail-wide"><span>Golpes</span><strong>{pokemon.moves?.length ? pokemon.moves.map(readableId).join(' · ') : 'Sem dados'}</strong></div>
      <StatList title="IVs" values={pokemon.ivs} />
      <StatList title="EVs" values={pokemon.evs} />
    </div>
  </div>;
}

export default function PlayerPcPage({ canEdit = false, initialPlayer = null }) {
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState({ value: '' });
  const [cursor, setCursor] = useState('');
  const [cursorHistory, setCursorHistory] = useState([]);
  const [directory, setDirectory] = useState(null);
  const [directoryError, setDirectoryError] = useState('');
  const [directoryReady, setDirectoryReady] = useState(false);
  const [selection, setSelection] = useState(() => initialPlayer ? { player: initialPlayer, index: 0 } : null);
  const [progress, setProgress] = useState(null);
  const [progressError, setProgressError] = useState('');
  const [progressSeries, setProgressSeries] = useState('');
  const [progressDefeated, setProgressDefeated] = useState([]);
  const [progressSaving, setProgressSaving] = useState(false);
  const [progressMessage, setProgressMessage] = useState('');
  const [selectedPokemon, setSelectedPokemon] = useState(null);
  const [box, setBox] = useState(null);
  const [boxError, setBoxError] = useState('');
  const [editor, setEditor] = useState(null);
  const [mutationBusy, setMutationBusy] = useState(false);
  const [mutationMessage, setMutationMessage] = useState('');
  const [mutationError, setMutationError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ q: search.value });
    if (cursor) params.set('cursor', cursor);
    request('/api/pc/players?' + params, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setDirectory(result);
    }).catch((error) => {
      if (!controller.signal.aborted) setDirectoryError(error.message);
    }).finally(() => {
      if (!controller.signal.aborted) setDirectoryReady(true);
    });
    return () => controller.abort();
  }, [search, cursor]);

  useEffect(() => {
    if (!selection || !directoryReady) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ player: selection.player.uuid, box: String(selection.index) });
    request('/api/pc/box?' + params, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setBox(result);
    }).catch((error) => {
      if (!controller.signal.aborted) setBoxError(error.message);
    });
    return () => controller.abort();
  }, [selection, directoryReady]);

  const find = (event) => {
    event.preventDefault();
    setDirectory(null);
    setDirectoryError('');
    setCursor('');
    setCursorHistory([]);
    setSearch({ value: query.trim() });
  };
  const nextPlayers = () => {
    if (!directory?.nextCursor) return;
    setDirectory(null);
    setDirectoryError('');
    setCursorHistory((history) => [...history, cursor]);
    setCursor(directory.nextCursor);
  };
  const previousPlayers = () => {
    if (!cursorHistory.length) return;
    setDirectory(null);
    setDirectoryError('');
    setCursor(cursorHistory.at(-1));
    setCursorHistory((history) => history.slice(0, -1));
  };
  const open = (player, index = 0) => {
    setBox(null);
    setBoxError('');
    setSelectedPokemon(null);
    setMutationMessage('');
    setMutationError('');
    setSelection({ player, index });
  };
  const selectPokemon = (pokemon) => setSelectedPokemon(pokemon);
  const boxLoading = selection && !box && !boxError;

  const loadProgress = async () => {
    if (!selection) return;
    setProgress(null);
    setProgressError('');
    setProgressMessage('');
    try {
      const result = await request(`/api/player/progress?player=${encodeURIComponent(selection.player.uuid)}`);
      const initialSeries = result.seriesOptions?.some((option) => option.id === result.series)
        ? result.series : result.seriesOptions?.[0]?.id || '';
      setProgress({ player: selection.player.uuid, ...result });
      setProgressSeries(initialSeries);
      setProgressDefeated(result.savedProgressBySeries?.[initialSeries]
        || (initialSeries === result.series ? result.defeated : []));
    } catch (error) {
      setProgressError(error.message);
    }
  };

  const chooseProgressSeries = (series) => {
    setProgressSeries(series);
    setProgressDefeated(progress?.savedProgressBySeries?.[series] || []);
    setProgressMessage('');
  };

  const toggleTrainerDefeat = (trainerId) => setProgressDefeated((current) => (
    current.includes(trainerId) ? current.filter((id) => id !== trainerId) : [...current, trainerId].sort()
  ));

  const saveProgress = async () => {
    if (!selection || !progress?.progressRevision) return;
    const currentSeriesDefeats = progress.savedProgressBySeries?.[progressSeries] || [];
    const removed = currentSeriesDefeats.filter((id) => !progressDefeated.includes(id));
    if ((removed.length || progressSeries !== progress.series) && !window.confirm(
      `Aplicar o progresso de ${progressSeries} para ${selection.player.name}? O histórico atual será arquivado antes da alteração.`
    )) return;
    setProgressSaving(true);
    setProgressError('');
    setProgressMessage('');
    try {
      const updated = await request('/api/player/progress/edit', {
        method: 'POST',
        body: {
          playerUuid: selection.player.uuid,
          expectedProgressRevision: progress.progressRevision,
          series: progressSeries,
          defeatedTrainerIds: progressDefeated,
        },
      });
      setProgress({ player: selection.player.uuid, ...updated });
      setProgressSeries(updated.series);
      setProgressDefeated(updated.defeated || []);
      setProgressMessage('Progresso individual atualizado e sincronizado com o jogador.');
    } catch (error) {
      setProgressError(error.message);
    } finally {
      setProgressSaving(false);
    }
  };

  const savePokemon = async (payload) => {
    setMutationBusy(true);
    setMutationMessage('');
    setMutationError('');
    try {
      await request('/api/pc/edit', { method: 'POST', body: payload });
      const player = selection.player;
      open(player, payload.box);
      setMutationMessage(payload.action === 'add' ? 'Pokémon adicionado ao PC.' : 'Alterações do Pokémon salvas.');
    } catch (error) {
      setMutationError(error.message);
      throw error;
    } finally {
      setMutationBusy(false);
    }
  };

  const deletePokemon = async () => {
    if (!selectedPokemon || !selection || !box) return;
    const name = selectedPokemon.nickname || readableId(selectedPokemon.species);
    if (!window.confirm(`Excluir ${name} do PC de ${selection.player.name}? Essa ação não pode ser desfeita.`)) return;
    setMutationBusy(true);
    setMutationMessage('');
    setMutationError('');
    try {
      await request('/api/pc/edit', {
        method: 'POST',
        body: {
          action: 'delete', player: selection.player.uuid, box: box.index,
          position: selectedPokemon.position, expectedPokemonUuid: selectedPokemon.pokemonUuid,
        },
      });
      const player = selection.player;
      open(player, box.index);
      setMutationMessage('Pokémon excluído do PC.');
    } catch (error) {
      setMutationError(error.message);
    } finally {
      setMutationBusy(false);
    }
  };

  return <>
    <div className="page-intro"><h1>PC dos jogadores</h1><p>Consulte os Pokémon das caixas salvas no servidor. Administradores podem adicionar, editar e excluir Pokémon.</p></div>
    <Section title="Jogadores" description="Busque por nome ou UUID e percorra os resultados em páginas de até 40 jogadores.">
      <form className="player-tools" onSubmit={find}>
        <label className="search-field"><Search size={17} aria-hidden="true" /><input aria-label="Nome ou UUID do jogador"
          placeholder="Nome ou UUID" value={query} maxLength={64} onChange={(event) => setQuery(event.target.value)} /></label>
        <button className="button button-quiet" type="submit" disabled={!directory && !directoryError || boxLoading}>Buscar</button>
      </form>
      {directoryError && <Notice tone="error">{directoryError}</Notice>}
      {!directory && !directoryError && <Loading>Carregando jogadores…</Loading>}
      {directory && <div className="table-wrap pc-players">
        <table>
          <thead><tr><th>Jogador</th><th>Situação</th><th aria-label="Ações" /></tr></thead>
          <tbody>
            {directory.players.map((player) => <tr key={player.uuid}>
              <td><strong>{player.name}</strong>{player.name !== player.uuid && <small>{player.uuid}</small>}</td>
              <td><span className={`player-state ${player.online ? 'is-online' : ''}`}><i />{player.online ? 'Conectado' : 'Offline'}</span></td>
              <td><button className="button button-quiet button-small" type="button" disabled={Boolean(boxLoading) || mutationBusy}
                aria-pressed={selection?.player.uuid === player.uuid} onClick={() => open(player)}>Ver PC</button></td>
            </tr>)}
            {directory.players.length === 0 && <tr><td className="table-empty" colSpan={3}>Nenhum jogador encontrado.</td></tr>}
          </tbody>
        </table>
      </div>}
      {directory && <div className="pc-pagination pc-player-pagination">
        <button className="button button-quiet button-small" type="button" disabled={!cursorHistory.length}
          onClick={previousPlayers}><ChevronLeft size={16} />Anterior</button>
        <span>Página {cursorHistory.length + 1}</span>
        <button className="button button-quiet button-small" type="button" disabled={!directory.nextCursor}
          onClick={nextPlayers}>Próxima<ChevronRight size={16} /></button>
      </div>}
    </Section>
    {selection && <Section title={`Progresso de ${selection.player.name}`} description="O limite RCT é individual e acompanha o circuito e as vitórias do jogador. Consulte e edite essa progressão com o jogador conectado.">
      <button className="button button-quiet" type="button" onClick={loadProgress} disabled={progressSaving}>
        {progress ? 'Atualizar progresso RCT' : 'Consultar progresso RCT'}
      </button>
      {progressError && <Notice tone="error">{progressError}</Notice>}
      {progressMessage && <Notice>{progressMessage}</Notice>}
      {progress?.player === selection.player.uuid && <div className="page-notes">
        <div className="pc-progress-summary">
          <span><small>Circuito ativo</small><strong>{progress.series || '—'}</strong></span>
          <span><small>Limite individual atual</small><strong>Nv. {progress.levelCap}</strong></span>
          <span><small>Vitórias neste circuito</small><strong>{progress.defeated.length} / {progress.trainerIdsBySeries?.[progress.series]?.length || 0}</strong></span>
        </div>
        <p>Circuitos concluídos: {Object.entries(progress.completedSeries).map(([name, count]) => `${name} (${count})`).join(', ') || 'Nenhum'}</p>
        {canEdit && <div className="pc-progress-editor">
          <div className="pc-progress-toolbar">
            <label className="field"><span className="field-label">Circuito do jogador</span>
              <select value={progressSeries} onChange={(event) => chooseProgressSeries(event.target.value)} disabled={progressSaving}>
                {(progress.seriesOptions || []).map((option) => <option value={option.id} key={option.id}>
                  {option.id === 'freeroam' ? 'Livre' : readableId(option.id)} · {option.trainerCount} treinadores
                </option>)}
              </select>
            </label>
            <div className="pc-progress-count"><small>Vitórias salvas</small><strong>{progressDefeated.length}</strong></div>
            <div className="pc-progress-actions">
              <button className="button button-quiet button-small" type="button" disabled={progressSaving}
                onClick={() => setProgressDefeated([...(progress.trainerIdsBySeries?.[progressSeries] || [])].sort())}>Marcar todos</button>
              <button className="button button-quiet button-small" type="button" disabled={progressSaving}
                onClick={() => setProgressDefeated([])}>Limpar circuito</button>
            </div>
          </div>
          {progressSeries === 'freeroam' ? <p className="field-hint">O modo livre não possui vitórias de ginásio para marcar.</p> : <div className="pc-progress-trainers">
            {(progress.trainerIdsBySeries?.[progressSeries] || []).map((trainerId) => <label className="pc-progress-trainer" key={trainerId}>
              <input type="checkbox" checked={progressDefeated.includes(trainerId)} disabled={progressSaving}
                onChange={() => toggleTrainerDefeat(trainerId)} />
              <span>{readableId(trainerId)}</span>
              <small>{trainerId}</small>
            </label>)}
            {!progress.trainerIdsBySeries?.[progressSeries]?.length && <p className="empty-state">Este circuito não possui treinadores configurados.</p>}
          </div>}
          <div className="pc-progress-save-row">
            <span className="field-hint">Alterações recalculam o cap individual e ficam salvas no progresso oficial do RCT.</span>
            <button className="button button-accent" type="button" onClick={saveProgress} disabled={progressSaving || !progressSeries || !progress.progressRevision}>
              {progressSaving ? <><LoaderCircle className="spin" size={16} />Salvando…</> : 'Salvar progresso'}
            </button>
          </div>
        </div>}
      </div>}
    </Section>}
    {selection && <Section title={`PC de ${selection.player.name}`}
      description={box?.source === 'live' ? 'Conteúdo atual em memória no servidor.' : box?.source === 'saved' ? 'Conteúdo do último arquivo salvo.' : undefined}>
      {boxLoading && <Loading>Carregando caixa…</Loading>}
      {boxError && <Notice tone="error">{boxError}</Notice>}
      {mutationError && <Notice tone="error">{mutationError}</Notice>}
      {mutationMessage && <Notice>{mutationMessage}</Notice>}
      {!boxLoading && <div className="section-toolbar pc-controls">
        {box?.boxCount > 0 && <div className="pc-pagination">
          <button className="icon-button" aria-label="Caixa anterior" type="button" disabled={box.index === 0 || mutationBusy}
            onClick={() => open(selection.player, box.index - 1)}><ChevronLeft size={18} /></button>
          <strong>Caixa {box.index + 1} de {box.boxCount}{box.name && ` · ${box.name}`}</strong>
          <button className="icon-button" aria-label="Próxima caixa" type="button" disabled={box.index + 1 >= box.boxCount || mutationBusy}
            onClick={() => open(selection.player, box.index + 1)}><ChevronRight size={18} /></button>
        </div>}
        {box && <button className="button button-quiet button-small" type="button" disabled={mutationBusy}
          onClick={() => open(selection.player, box.index)}><RefreshCw size={15} />Atualizar</button>}
      </div>}
      {box?.boxCount > 0 && <>
        <PcBox box={box} selected={selectedPokemon?.position} canEdit={canEdit && !mutationBusy}
          onSelect={selectPokemon} onAdd={(position) => setEditor({ action: 'add', box: box.index, position, pokemon: null })} />
        {selectedPokemon && <>
          <PokemonDetails pokemon={selectedPokemon} />
          {canEdit && <div className="pc-edit-actions">
            <button className="button button-secondary" type="button" disabled={mutationBusy}
              onClick={() => setEditor({ action: 'edit', box: box.index, position: selectedPokemon.position, pokemon: selectedPokemon })}>
              <Pencil size={15} />Editar Pokémon
            </button>
            <button className="button button-danger" type="button" disabled={mutationBusy} onClick={deletePokemon}>
              <Trash2 size={15} />Excluir Pokémon
            </button>
          </div>}
        </>}
      </>}
      {box?.boxCount === 0 && <Notice>{box.source === 'missing' ? 'Nenhum PC salvo foi encontrado para este jogador.' : 'Este PC ainda não tem caixas.'}</Notice>}
      {canEdit && box?.boxCount === 0 && <div className="pc-create-actions">
        <button className="button button-accent" type="button" disabled={mutationBusy}
          onClick={() => setEditor({ action: 'add', box: 0, position: 0, pokemon: null })}><Plus size={16} />Criar PC e adicionar Pokémon</button>
      </div>}
    </Section>}
    {editor && selection && <PokemonEditorDialog key={`${editor.action}:${editor.box}:${editor.position}:${editor.pokemon?.pokemonUuid || ''}`}
      action={editor.action} player={selection.player} box={editor.box} position={editor.position} pokemon={editor.pokemon}
      onSave={savePokemon} onClose={() => setEditor(null)} />}
  </>;
}
