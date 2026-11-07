import { ChevronLeft, ChevronRight, RefreshCw, Search } from 'lucide-react';
import { useEffect, useState } from 'react';
import { request } from '../api.js';
import { Loading, Notice, Section } from '../components/ui.jsx';

function readableId(id) {
  return id.split(':').at(-1).replaceAll('_', ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function PcBox({ box }) {
  const slots = new Map(box.slots.map((pokemon) => [pokemon.position, pokemon]));
  return <ol className="pc-grid" aria-label="Espaços da caixa">
    {Array.from({ length: 30 }, (_, position) => {
      const pokemon = slots.get(position);
      return <li className={pokemon ? 'pc-slot pc-slot-filled' : 'pc-slot'} key={position}>
        <span className="pc-slot-number">{position + 1}</span>
        {pokemon ? <>
          <strong>{pokemon.species ? readableId(pokemon.species) : 'Espécie desconhecida'}</strong>
          <span>Nv. {pokemon.level}{pokemon.shiny && <span className="pc-shiny"> · Shiny</span>}</span>
          {pokemon.form && !['normal', 'cobblemon:normal'].includes(pokemon.form.toLowerCase()) && <span>{readableId(pokemon.form)}</span>}
        </> : <span>Vazio</span>}
      </li>;
    })}
  </ol>;
}

export default function PlayerPcPage() {
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState({ value: '' });
  const [directory, setDirectory] = useState(null);
  const [directoryError, setDirectoryError] = useState('');
  const [selection, setSelection] = useState(null);
  const [box, setBox] = useState(null);
  const [boxError, setBoxError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ q: search.value });
    request('/api/pc/players?' + params, { signal: controller.signal }).then((result) => {
      if (!controller.signal.aborted) setDirectory(result.players);
    }).catch((error) => {
      if (!controller.signal.aborted) setDirectoryError(error.message);
    });
    return () => controller.abort();
  }, [search]);

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
    setSearch({ value: query.trim() });
  };
  const open = (player, index = 0) => {
    setBox(null);
    setBoxError('');
    setSelection({ player, index });
  };
  const boxLoading = selection && !box && !boxError;

  return <>
    <div className="page-intro"><h1>PC dos jogadores</h1><p>Consulte os Pokémon guardados nas caixas do servidor. A consulta é somente leitura.</p></div>
    <Section title="Jogadores" description="Busque por nome ou UUID. Cada consulta mostra até 40 jogadores.">
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
            {directory.map((player) => <tr key={player.uuid}>
              <td><strong>{player.name}</strong>{player.name !== player.uuid && <small>{player.uuid}</small>}</td>
              <td><span className={`player-state ${player.online ? 'is-online' : ''}`}><i />{player.online ? 'Conectado' : 'Offline'}</span></td>
              <td><button className="button button-quiet button-small" type="button" disabled={Boolean(boxLoading)}
                aria-pressed={selection?.player.uuid === player.uuid} onClick={() => open(player)}>Ver PC</button></td>
            </tr>)}
            {directory.length === 0 && <tr><td className="table-empty" colSpan={3}>Nenhum jogador encontrado.</td></tr>}
          </tbody>
        </table>
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
      {box?.boxCount > 0 && <PcBox box={box} />}
      {box?.boxCount === 0 && <Notice>{box.source === 'missing' ? 'Nenhum PC salvo foi encontrado para este jogador.' : 'Este PC ainda não tem caixas.'}</Notice>}
    </Section>}
  </>;
}
