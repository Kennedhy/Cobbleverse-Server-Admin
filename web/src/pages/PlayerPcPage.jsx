import { ChevronLeft, ChevronRight, RefreshCw, Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { request } from '../api.js';
import { Loading, Notice, Section } from '../components/ui.jsx';

function readableId(id) {
  return id.split(':').at(-1).replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function PcBox({ box, selected, onSelect }) {
  const slots = new Map(box.slots.map((pokemon) => [pokemon.position, pokemon]));
  return <ol className="pc-grid" aria-label="Espaços da caixa">
    {Array.from({ length: 30 }, (_, position) => {
      const pokemon = slots.get(position);
      return <li className={pokemon ? 'pc-slot pc-slot-filled' : 'pc-slot'} key={position}>
        <span className="pc-slot-number">{position + 1}</span>
        {pokemon ? <button className={`pc-slot-button${selected === position ? ' is-selected' : ''}`} type="button"
          aria-pressed={selected === position} onClick={() => onSelect(pokemon)}>
          <strong>{pokemon.species ? readableId(pokemon.species) : 'Espécie desconhecida'}</strong>
          <span>Nv. {pokemon.level}{pokemon.shiny && <span className="pc-shiny"> · Shiny</span>}</span>
          {pokemon.form && !['normal', 'cobblemon:normal'].includes(pokemon.form.toLowerCase()) && <span>{readableId(pokemon.form)}</span>}
        </button> : <span>Vazio</span>}
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
    <div className="pc-details-heading"><span>Pokémon selecionado</span><strong>{readableId(pokemon.species)} · Nv. {pokemon.level}</strong></div>
    <div className="pc-details-grid">
      <div className="pc-detail-stat"><span>Natureza</span><strong>{pokemon.nature || 'Sem dados'}</strong></div>
      <div className="pc-detail-stat"><span>Habilidade</span><strong>{pokemon.ability ? readableId(pokemon.ability) : 'Sem dados'}</strong></div>
      <div className="pc-detail-stat pc-detail-wide"><span>Golpes</span><strong>{pokemon.moves?.length ? pokemon.moves.map(readableId).join(' · ') : 'Sem dados'}</strong></div>
      <StatList title="IVs" values={pokemon.ivs} />
      <StatList title="EVs" values={pokemon.evs} />
    </div>
  </div>;
}

export default function PlayerPcPage() {
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState({ value: '' });
  const [cursor, setCursor] = useState('');
  const [cursorHistory, setCursorHistory] = useState([]);
  const [directory, setDirectory] = useState(null);
  const [directoryError, setDirectoryError] = useState('');
  const [selection, setSelection] = useState(null);
  const [selectedPokemon, setSelectedPokemon] = useState(null);
  const [box, setBox] = useState(null);
  const [boxError, setBoxError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ q: search.value });
    if (cursor) params.set('cursor', cursor);
    request('/api/pc/players?' + params, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setDirectory(result);
    }).catch((error) => {
      if (!controller.signal.aborted) setDirectoryError(error.message);
    });
    return () => controller.abort();
  }, [search, cursor]);

  useEffect(() => {
    if (!selection) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ player: selection.player.uuid, box: String(selection.index) });
    request('/api/pc/box?' + params, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setBox(result);
    }).catch((error) => {
      if (!controller.signal.aborted) setBoxError(error.message);
    });
    return () => controller.abort();
  }, [selection]);

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
    setSelection({ player, index });
  };
  const selectPokemon = (pokemon) => setSelectedPokemon(pokemon);
  const boxLoading = selection && !box && !boxError;

  return <>
    <div className="page-intro"><h1>PC dos jogadores</h1><p>Consulte os Pokémon guardados nas caixas do servidor. A consulta é somente leitura.</p></div>
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
              <td><button className="button button-quiet button-small" type="button" disabled={Boolean(boxLoading)}
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
    {selection && <Section title={`PC de ${selection.player.name}`}
      description={box?.source === 'live' ? 'Conteúdo em memória no momento da consulta.' : box?.source === 'saved' ? 'Conteúdo do último arquivo salvo.' : undefined}>
      {boxLoading && <Loading>Carregando caixa…</Loading>}
      {boxError && <Notice tone="error">{boxError}</Notice>}
      {!boxLoading && <div className="section-toolbar pc-controls">
        {box?.boxCount > 0 && <div className="pc-pagination">
          <button className="icon-button" aria-label="Caixa anterior" type="button" disabled={box.index === 0}
            onClick={() => open(selection.player, box.index - 1)}><ChevronLeft size={18} /></button>
          <strong>Caixa {box.index + 1} de {box.boxCount}{box.name && ` · ${box.name}`}</strong>
          <button className="icon-button" aria-label="Próxima caixa" type="button" disabled={box.index + 1 >= box.boxCount}
            onClick={() => open(selection.player, box.index + 1)}><ChevronRight size={18} /></button>
        </div>}
        <button className="button button-quiet button-small" type="button" onClick={() => open(selection.player, box?.index ?? selection.index)}>
          <RefreshCw size={15} />Atualizar
        </button>
      </div>}
      {box?.boxCount > 0 && <>
        <PcBox box={box} selected={selectedPokemon?.position} onSelect={selectPokemon} />
        {selectedPokemon && <PokemonDetails pokemon={selectedPokemon} />}
      </>}
      {box?.boxCount === 0 && <Notice>{box.source === 'missing' ? 'Nenhum PC salvo foi encontrado para este jogador.' : 'Este PC ainda não tem caixas.'}</Notice>}
    </Section>}
  </>;
}
