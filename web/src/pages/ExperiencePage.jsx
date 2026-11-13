import { ChevronLeft, ChevronRight, Plus, Search, Trash2 } from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { formatMultiplier, request } from '../api.js';
import { Choice, CommitTextInput, Field, Notice, NumberField, NumericInput, Section, SelectField, Toggle } from '../components/ui.jsx';

const capModes = [['rct', 'Progresso do RCT'], ['global', 'Limite global']];
const wildModes = [
  ['clamp', 'Ajustar ao limite'],
  ['block', 'Bloquear aparição'],
  ['unchanged', 'Manter nível original'],
];
const overCapBattleModes = [
  ['team', 'Bloquear a batalha inteira'],
  ['pokemon', 'Bloquear só o Pokémon acima do cap'],
];

function GroupEditor({ draft, update }) {
  const [error, setError] = useState('');
  const groups = Object.entries(draft.groupXpMultipliers || {}).sort(([a], [b]) => a.localeCompare(b));
  const countPlayers = (name) => Object.values(draft.playerXpGroups || {}).filter((group) => group === name).length;
  const rename = (oldName, rawName, multiplier) => {
    const name = rawName.trim().toLowerCase();
    if (!/^[a-z0-9_.-]{1,48}$/.test(name)) {
      setError('Use de 1 a 48 letras minúsculas, números, ponto, hífen ou sublinhado no nome do grupo.');
      return false;
    }
    if (name === oldName) { setError(''); return true; }
    if (Object.hasOwn(draft.groupXpMultipliers || {}, name)) {
      setError(`O grupo “${name}” já existe. Escolha outro nome para manter as regras e os jogadores separados.`);
      return false;
    }
    const values = { ...draft.groupXpMultipliers };
    delete values[oldName];
    values[name] = multiplier;
    const playerXpGroups = Object.fromEntries(Object.entries(draft.playerXpGroups || {}).map(([uuid, group]) => [uuid, group === oldName ? name : group]));
    update({ groupXpMultipliers: values, playerXpGroups });
    setError('');
    return true;
  };
  const changeMultiplier = (name, value) => update({ groupXpMultipliers: { ...draft.groupXpMultipliers, [name]: value } });
  const remove = (name) => {
    const groupXpMultipliers = { ...draft.groupXpMultipliers };
    delete groupXpMultipliers[name];
    const playerXpGroups = Object.fromEntries(Object.entries(draft.playerXpGroups || {}).filter(([, group]) => group !== name));
    update({ groupXpMultipliers, playerXpGroups });
  };
  const add = () => {
    let name = 'novo_grupo'; let suffix = 2;
    while (Object.hasOwn(draft.groupXpMultipliers || {}, name)) name = `novo_grupo_${suffix++}`;
    update({ groupXpMultipliers: { ...draft.groupXpMultipliers, [name]: 1 } });
  };
  return <>
    <div className="section-toolbar"><span>Crie grupos próprios e ajuste o multiplicador de cada um.</span><button className="button button-quiet button-small" type="button" onClick={add}><Plus size={15} />Adicionar grupo</button></div>
    {error && <Notice tone="error">{error}</Notice>}
    <div className="table-wrap">
      <table>
        <thead><tr><th>Grupo</th><th>Multiplicador</th><th>Jogadores</th><th aria-label="Ações" /></tr></thead>
        <tbody>
          {groups.map(([name, value]) => <tr key={name}>
            <td><CommitTextInput aria-label="Nome do grupo" value={name} maxLength={48} onCommit={(next) => rename(name, next, value)} /></td>
            <td><span className="inline-number"><NumericInput ariaLabel={`Multiplicador do grupo ${name}`} min={0} max={100} step={0.05} value={value} onCommit={(next) => changeMultiplier(name, next)} /><span>×</span></span></td>
            <td className="table-muted">{countPlayers(name)}</td>
            <td><button className="icon-button danger-hover" type="button" aria-label={`Remover grupo ${name}`} onClick={() => remove(name)}><Trash2 size={16} /></button></td>
          </tr>)}
          {groups.length === 0 && <tr><td colSpan="4" className="table-empty">Nenhum grupo criado.</td></tr>}
        </tbody>
      </table>
    </div>
  </>;
}

function PlayerEditor({ draft, update, state }) {
  const [query, setQuery] = useState('');
  const [offlineUuid, setOfflineUuid] = useState('');
  const [worldLookup, setWorldLookup] = useState({ term: '', players: [], nextCursor: null });
  const [lookupCursor, setLookupCursor] = useState('');
  const [lookupHistory, setLookupHistory] = useState([]);
  const [lookupLoading, setLookupLoading] = useState(false);
  const [lookupError, setLookupError] = useState('');
  const lookupSequence = useRef(0);
  const groups = Object.keys(draft.groupXpMultipliers || {}).sort();
  const lookupWorldPlayers = async (term, cursor = '') => {
    const sequence = ++lookupSequence.current;
    setLookupLoading(true);
    setLookupError('');
    try {
      const params = new URLSearchParams({ q: term });
      if (cursor) params.set('cursor', cursor);
      const result = await request(`/api/pc/players?${params}`);
      if (sequence !== lookupSequence.current) return;
      setWorldLookup({ term, players: result.players, nextCursor: result.nextCursor || null });
    } catch (error) {
      if (sequence === lookupSequence.current) setLookupError(error.message);
    } finally {
      if (sequence === lookupSequence.current) setLookupLoading(false);
    }
  };
  const searchWorld = (event) => {
    event.preventDefault();
    const term = query.trim();
    if (term.length >= 2) {
      setLookupCursor('');
      setLookupHistory([]);
      void lookupWorldPlayers(term);
    }
  };
  const nextWorldPlayers = () => {
    if (!worldLookup.nextCursor || worldLookup.term !== query.trim() || lookupLoading) return;
    setLookupHistory((history) => [...history, lookupCursor]);
    setLookupCursor(worldLookup.nextCursor);
    void lookupWorldPlayers(worldLookup.term, worldLookup.nextCursor);
  };
  const previousWorldPlayers = () => {
    if (!lookupHistory.length || worldLookup.term !== query.trim() || lookupLoading) return;
    const previousCursor = lookupHistory.at(-1);
    setLookupHistory((history) => history.slice(0, -1));
    setLookupCursor(previousCursor);
    void lookupWorldPlayers(worldLookup.term, previousCursor);
  };
  const rows = useMemo(() => {
    const index = new Map();
    const worldPlayers = worldLookup.term === query.trim() ? worldLookup.players : [];
    for (const player of [...(state?.knownPlayers || []), ...(state?.onlinePlayers || []), ...worldPlayers]) index.set(player.uuid, player);
    for (const uuid of [...Object.keys(draft.playerXpGroups || {}), ...Object.keys(draft.playerXpMultipliers || {})]) {
      if (!index.has(uuid)) index.set(uuid, { uuid, name: uuid, online: false });
    }
    const term = query.trim().toLowerCase();
    return [...index.values()].filter((player) => !term || player.name.toLowerCase().includes(term) || player.uuid.toLowerCase().includes(term));
  }, [state, draft.playerXpGroups, draft.playerXpMultipliers, query, worldLookup]);
  const visibleRows = rows.slice(0, 50);

  const setGroup = (uuid, group) => {
    const playerXpGroups = { ...draft.playerXpGroups };
    if (group) playerXpGroups[uuid] = group; else delete playerXpGroups[uuid];
    update({ playerXpGroups });
  };
  const setIndividual = (uuid, value) => {
    const playerXpMultipliers = { ...draft.playerXpMultipliers };
    if (value === '') delete playerXpMultipliers[uuid]; else playerXpMultipliers[uuid] = Number(value);
    update({ playerXpMultipliers });
  };
  const addOffline = () => {
    const uuid = offlineUuid.trim().toLowerCase();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(uuid)) return;
    if (!(uuid in (draft.playerXpGroups || {})) && !(uuid in (draft.playerXpMultipliers || {}))) {
      update({ playerXpMultipliers: { ...draft.playerXpMultipliers, [uuid]: 1 } });
    }
    setOfflineUuid('');
  };
  return <>
    <div className="player-tools">
      <form className="world-player-search" onSubmit={searchWorld}>
        <label className="search-field"><Search size={17} /><input aria-label="Buscar jogador por nome ou UUID no mundo" value={query} placeholder="Buscar jogador ou UUID" onChange={(event) => { setQuery(event.target.value); setLookupError(''); }} /></label>
        <button className="button button-quiet button-small" type="submit" disabled={query.trim().length < 2 || lookupLoading}>
          {lookupLoading ? 'Buscando…' : 'Buscar no mundo'}
        </button>
      </form>
      {lookupError && <Notice tone="error">{lookupError}</Notice>}
      {worldLookup.term === query.trim() && worldLookup.players.length > 0 && <>
        <p className="field-hint">Busca no mundo: {worldLookup.players.length} jogador(es) nesta página. Selecione o grupo ou defina o multiplicador na tabela.</p>
        <div className="pc-pagination">
          <button className="button button-quiet button-small" type="button" onClick={previousWorldPlayers} disabled={!lookupHistory.length || lookupLoading}><ChevronLeft size={15} />Anterior</button>
          <span>Página {lookupHistory.length + 1}</span>
          <button className="button button-quiet button-small" type="button" onClick={nextWorldPlayers} disabled={!worldLookup.nextCursor || lookupLoading}>Próxima<ChevronRight size={15} /></button>
        </div>
      </>}
      <div className="add-offline">
        <input value={offlineUuid} placeholder="UUID de jogador offline" aria-label="UUID de jogador offline" onChange={(event) => setOfflineUuid(event.target.value)} />
        <button className="button button-quiet button-small" type="button" disabled={!offlineUuid.trim()} onClick={addOffline}><Plus size={15} />Adicionar</button>
      </div>
    </div>
    <div className="table-wrap">
      <table className="players-table">
        <thead><tr><th>Jogador</th><th>Grupo</th><th>Multiplicador individual</th><th aria-label="Ações" /></tr></thead>
        <tbody>
          {visibleRows.map((player) => <tr key={player.uuid}>
            <td><span className={`player-state ${player.online ? 'is-online' : ''}`}><i />{player.online ? 'Conectado' : 'Offline'}</span><strong>{player.name}</strong><small>{player.uuid}</small></td>
            <td><select aria-label={`Grupo de ${player.name}`} value={draft.playerXpGroups?.[player.uuid] || ''} onChange={(event) => setGroup(player.uuid, event.target.value)}>
              <option value="">Sem grupo</option>{groups.map((group) => <option key={group} value={group}>{group}</option>)}
            </select></td>
            <td><span className="inline-number"><NumericInput ariaLabel={`Multiplicador individual de ${player.name}`} min={0} max={100} step={0.05}
              placeholder="Usar grupo" value={draft.playerXpMultipliers?.[player.uuid] ?? ''} onCommit={(value) => setIndividual(player.uuid, value)} /><span>×</span></span></td>
            <td>{!player.online && <button className="icon-button danger-hover" type="button" aria-label={`Remover configuração de ${player.name}`} onClick={() => {
              const playerXpGroups = { ...draft.playerXpGroups }; delete playerXpGroups[player.uuid];
              const playerXpMultipliers = { ...draft.playerXpMultipliers }; delete playerXpMultipliers[player.uuid];
              update({ playerXpGroups, playerXpMultipliers });
            }}><Trash2 size={15} /></button>}</td>
          </tr>)}
          {rows.length === 0 && <tr><td colSpan="4" className="table-empty">Nenhum jogador corresponde à busca.</td></tr>}
        </tbody>
      </table>
    </div>
    {rows.length > 50 && <p className="field-hint">Mostrando 50 jogadores. Use a busca para encontrar os demais.</p>}
  </>;
}

export default function ExperiencePage({ draft, update, state, canEdit }) {
  const [simulation, setSimulation] = useState(null);
  const [simulationError, setSimulationError] = useState('');
  const [simulationBusy, setSimulationBusy] = useState(false);
  const [playerUuid, setPlayerUuid] = useState('');
  const [species, setSpecies] = useState('cobblemon:pikachu');
  const [form, setForm] = useState('normal');
  const [baseXp, setBaseXp] = useState(100);
  const [remainingXp, setRemainingXp] = useState(1000);
  const players = state?.knownPlayers || state?.onlinePlayers || [];
  const simulate = async (event) => {
    event.preventDefault();
    setSimulationError(''); setSimulationBusy(true); setSimulation(null);
    try {
      const result = await request('/api/simulate', { method: 'POST', body: {
        candidate: draft, playerUuid: playerUuid || players[0]?.uuid,
        species, form, baseXp: Number(baseXp), remainingXp: Number(remainingXp),
      } });
      setSimulation(result);
    } catch (error) { setSimulationError(error.message); }
    finally { setSimulationBusy(false); }
  };
  return <>
    <div className="page-intro"><h1>XP e níveis</h1><p>Controle o ganho de experiência e o limite dos Pokémon por jogador.</p></div>
    <div inert={!canEdit}>
    <div className="management-toggle"><Toggle label="Gerenciar XP e limite de nível" checked={draft.manageExperience} onChange={(manageExperience) => update({ manageExperience })} /></div>
    <Section title="Experiência" description="O multiplicador global combina com o grupo ou com o ajuste individual.">
      <div className="form-row form-row-single">
        <NumberField label="Multiplicador global" value={draft.globalXpMultiplier} min={0} max={100} step={0.05} suffix="×"
          onChange={(globalXpMultiplier) => update({ globalXpMultiplier })} />
        <Notice>XP final = XP base × global × grupo. O ajuste individual substitui o grupo.</Notice>
      </div>
    </Section>
    <Section title="Grupos de XP" description="Grupos próprios para organizar multiplicadores de experiência.">
      <GroupEditor draft={draft} update={update} />
    </Section>
    <Section title="Jogadores" description="Valores individuais têm prioridade sobre o grupo vinculado.">
      <PlayerEditor draft={draft} update={update} state={state} />
    </Section>
    </div>
    <Section title="Simulador de regras" description="Calcula com o mesmo motor do servidor usando o rascunho atual. Não cria Pokémon nem concede XP.">
      <form className="form-grid" onSubmit={simulate}>
        <SelectField label="Jogador" value={playerUuid || players[0]?.uuid || ''} onChange={setPlayerUuid}
          options={players.map((player) => ({ value: player.uuid, label: `${player.name}${player.online ? ' · conectado' : ''}` }))} />
        <Field label="Espécie"><input value={species} maxLength={96} onChange={(event) => setSpecies(event.target.value)} /></Field>
        <Field label="Forma"><input value={form} maxLength={64} onChange={(event) => setForm(event.target.value)} /></Field>
        <Field label="XP base"><input type="number" min="0" max="1000000" value={baseXp} onChange={(event) => setBaseXp(event.target.value)} /></Field>
        <Field label="XP restante até o próximo nível"><input type="number" min="0" max="10000000" value={remainingXp} onChange={(event) => setRemainingXp(event.target.value)} /></Field>
        <button className="button button-accent" type="submit" disabled={simulationBusy || !players.length || !species.trim()}>{simulationBusy ? 'Calculando…' : 'Simular'}</button>
      </form>
      {!players.length && <Notice tone="warning">A lista de jogadores ainda não está disponível. Atualize o painel quando houver jogadores registrados.</Notice>}
      {simulationError && <Notice tone="error">{simulationError}</Notice>}
      {simulation && <div className="report-counts">
        <span><strong>{simulation.allowed ? 'Permitida' : 'Bloqueada'}</strong> · {simulation.reason}</span>
        <span><strong>{simulation.generation || 'Especial'}</strong> geração</span>
        <span><strong>{simulation.xpManaged ? `${formatMultiplier(simulation.totalMultiplier)}×` : 'Desativado'}</strong> XP total ({simulation.xpSource}{simulation.xpGroup ? `: ${simulation.xpGroup}` : ''})</span>
        <span><strong>{simulation.awardedXp ?? '—'}</strong> XP após limite restante</span>
        <span><strong>{simulation.levelCap ?? '—'}</strong> limite ({simulation.levelCapSource})</span>
        <span><strong>{simulation.wildLevelBehavior}</strong> comportamento selvagem</span>
        <span><strong>{simulation.blockOverCapPokemonInBattles
          ? (simulation.overCapBattleBehavior === 'pokemon' ? 'Só o Pokémon acima do cap' : 'Batalha inteira')
          : 'Permitido'}</strong> em PvE · PvP sempre permitido</span>
      </div>}
    </Section>
    <div inert={!canEdit}>
    <Section title="Nível dos Pokémon" description="Configure o cap usado por XP, encontros selvagens e, opcionalmente, batalhas.">
      <div className="cap-layout">
        <div><span className="field-label">Origem do limite</span><div className="choice-grid cap-choice-grid" role="group" aria-label="Origem do limite de nível">
          {capModes.map(([mode, label]) => <Choice key={mode} selected={draft.levelCapMode === mode} onClick={() => update({ levelCapMode: mode })}>{label}</Choice>)}
        </div></div>
        <NumberField label="Limite global" value={draft.globalLevelCap} min={1} max={100} step={1}
          onChange={(globalLevelCap) => update({ globalLevelCap })} />
        <SelectField label="Selvagens acima do limite" value={draft.wildLevelBehavior}
          options={wildModes.map(([value, label]) => ({ value, label }))} onChange={(wildLevelBehavior) => update({ wildLevelBehavior })} />
      </div>
      <div className="page-notes compact-notes">
        <p>Com vários jogadores próximos, vale o maior limite.</p>
        <p>Sem jogador com progresso carregado, o nível original é mantido.</p>
        <p>A regra é aplicada na aparição do Pokémon e afeta quem encontrar o mesmo selvagem.</p>
      </div>
      <div className="form-row form-row-single">
        <Toggle label="Bloquear batalha com Pokémon acima do cap" checked={Boolean(draft.blockOverCapPokemonInBattles)}
          disabled={!draft.manageExperience} onChange={(blockOverCapPokemonInBattles) => update({ blockOverCapPokemonInBattles })} />
        <SelectField label="Comportamento em PvE" value={draft.overCapBattleBehavior || 'team'}
          disabled={!draft.manageExperience || !draft.blockOverCapPokemonInBattles}
          options={overCapBattleModes.map(([value, label]) => ({ value, label }))}
          onChange={(overCapBattleBehavior) => update({ overCapBattleBehavior })} />
        <Notice>Esta regra vale contra Pokémon selvagens e treinadores. PvP nunca é bloqueado pelo cap. No modo de batalha inteira, qualquer Pokémon acima do limite impede o início; no modo individual, só esse Pokémon fica impedido de entrar ou ser enviado. Com o cap RCT ainda carregando, o servidor não bloqueia por engano.</Notice>
      </div>
    </Section>
    <Notice>O modo RCT acompanha o progresso individual. O limite global usa o valor definido acima. Depois de validar e salvar, use Aplicar ao vivo no rodapé para ativar XP e limites sem reiniciar.</Notice>
    </div>
  </>;
}
