import { ChevronRight, Play, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { formatMultiplier, request } from '../api.js';
import { PixelIcon } from '../components/PixelIcon.jsx';

const trainerTones = ['g', 'r', 'b', 'p', 'a'];
const dash = '—';
const connectionMessages = {
  loading: 'Carregando jogadores…',
  restarting: 'Servidor reiniciando. Aguardando o painel voltar…',
  offline: 'Atualização indisponível. Tentando novamente…',
};

function ActiveRules({ rules, canEdit, onPage }) {
  const rows = rules ? [
    ['Gerações permitidas', 'book', rules.generations.join(', '), 'generations'],
    ['Filtro de ginásios', 'medal', rules.filterGyms ? 'Ativado' : 'Desativado', 'generations'],
    ['XP e limite de nível', 'chart', rules.manageExperience
      ? `${formatMultiplier(rules.globalXpMultiplier)}× global · ${rules.levelCapMode === 'global' ? `Cap ${rules.globalLevelCap}` : 'Cap por RCT'}`
      : 'Gerenciamento desativado', 'experience'],
    ['Nível dos selvagens', 'ball', rules.manageExperience
      ? ({ clamp: 'Ajusta ao cap', block: 'Bloqueia acima do cap' }[rules.wildLevelBehavior] ?? 'Nível original')
      : 'Nível original', 'experience'],
    ['Bloqueio em PvE', 'scroll', rules.manageExperience && rules.blockOverCapPokemonInBattles
      ? rules.overCapBattleBehavior === 'pokemon' ? 'Só Pokémon acima do cap' : 'Batalha inteira'
      : 'Desativado', 'experience'],
    ['Chance de captura', 'ball', `${formatMultiplier(rules.captureMultiplier)}× global`, 'capture'],
  ] : [];

  return <section className="dex-frame dex-panel dex-rules-panel">
    <h2 className="dex-ribbon">Regras ativas</h2>
    {rows.length ? <ul className="dex-rules-list">
      {rows.map(([label, icon, value, page]) => <li key={label} className="dex-rule">
        <PixelIcon name={icon} size={26} />
        <div><strong>{label}</strong><small>{value}</small></div>
        {(canEdit || page === 'experience') && <button className="dex-rule-link" type="button"
          onClick={() => onPage(page)} aria-label={`Abrir ${label}`}><ChevronRight size={17} /></button>}
      </li>)}
    </ul> : <p className="dex-dashboard-message">Aguardando as regras do servidor…</p>}
  </section>;
}

function formatUptime(minutes) {
  if (minutes == null) return dash;
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor(minutes / 60) % 24;
  return `${days ? `${days}d ` : ''}${hours}h ${minutes % 60}min`;
}

export default function OverviewPage({ onPage, canEdit, onNotice, dirty, busy }) {
  const [live, setLive] = useState(null);
  const [connection, setConnection] = useState('loading');
  const [restartRequested, setRestartRequested] = useState(false);

  useEffect(() => {
    let timer;
    let controller;
    let disposed = false;
    async function refresh() {
      if (document.hidden || controller) return;
      const requestController = new AbortController();
      controller = requestController;
      let retry = true;
      try {
        const signal = AbortSignal.any([requestController.signal, AbortSignal.timeout(8000)]);
        const data = await request('/api/dashboard', { signal });
        if (disposed) return;
        setLive(data);
        setConnection(data.restarting || restartRequested ? 'restarting' : 'online');
      } catch (error) {
        if (disposed) return;
        if (error.status === 401) {
          retry = false;
          window.location.reload();
        } else {
          setConnection(restartRequested ? 'restarting' : 'offline');
        }
      } finally {
        controller = null;
        if (!disposed && retry && !document.hidden) timer = setTimeout(refresh, restartRequested ? 3500 : 15000);
      }
    }
    const onVisibility = () => {
      clearTimeout(timer);
      if (!document.hidden) refresh();
    };
    refresh();
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      disposed = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [restartRequested]);

  const restart = async () => {
    const warning = dirty ? ' O rascunho local será descartado.' : '';
    if (!window.confirm('Reiniciar o servidor agora? Os jogadores serão desconectados e o mundo será salvo.' + warning)) return;
    setConnection('restarting');
    try {
      await request('/api/server/restart', { method: 'POST', signal: AbortSignal.timeout(8000) });
      setRestartRequested(true);
      onNotice({ tone: 'success', message: 'Reinício solicitado. Entre novamente quando o painel voltar.' });
    } catch (error) {
      setRestartRequested(false);
      setConnection(error.status ? 'online' : 'offline');
      onNotice({ tone: 'error', message: error.message });
    }
  };

  const players = live?.players ?? [];
  const playerCount = live ? `${live.playerCount} / ${live.maxPlayers}` : null;
  return <div className="dex-overview">
    <div className="dex-title">
      <PixelIcon name="ball" size={50} /><h1>Visão geral</h1><span className="dex-title-lines" aria-hidden="true" />
      {canEdit && <button className="dex-command dex-command-green" type="button" onClick={restart}
        disabled={!live?.restartAvailable || connection !== 'online' || busy}
        title={live?.restartAvailable === false ? 'Inicie o servidor com tools/run-server.py para habilitar o reinício.' : undefined}>
        <Play size={17} fill="currentColor" />{connection === 'restarting' ? 'Reiniciando…' : 'Reiniciar servidor'}
      </button>}
    </div>
    <div className="dex-dashboard-panels">
      <ActiveRules rules={live?.rules} canEdit={canEdit} onPage={onPage} />
      <section className="dex-frame dex-panel dex-players-panel">
        <h2 className="dex-ribbon">Jogadores online {playerCount && <span>{playerCount}</span>}</h2>
        {connection !== 'online' && <p className="dex-dashboard-message" role="status">{connectionMessages[connection]}</p>}
        {players.length > 0 ? <div className="dex-players-scroll">
          <table className="dex-players">
            <thead><tr><th>Jogador</th><th>Nível cap</th><th title="Pokémon na equipe">Equipe</th><th>Tempo online</th></tr></thead>
            <tbody>{players.map((player, index) => <tr key={player.uuid}>
              <td><button className="dex-player-link" type="button" onClick={() => onPage('pc', player)} aria-label={`Abrir PC de ${player.name}`}>
                <PixelIcon name="trainer" size={34} tone={trainerTones[index]} /><strong>{player.name}</strong>
              </button></td>
              <td>{player.levelCap ?? dash}</td><td>{player.partySize ?? dash}</td><td>{formatUptime(player.onlineMinutes)}</td>
            </tr>)}</tbody>
          </table>
        </div> : connection === 'online' && <p className="empty-state">Nenhum jogador conectado.</p>}
        <button className="dex-command dex-players-button" type="button" onClick={() => onPage('pc')}><Users size={21} />Ver todos os jogadores</button>
      </section>
    </div>
  </div>;
}
