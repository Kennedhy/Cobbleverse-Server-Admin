import { ArrowRight, Clock3, Cpu, Users } from 'lucide-react';
import { Notice, Section } from '../components/ui.jsx';

const formatMs = (value) => `${Number(value || 0).toFixed(1).replace('.', ',')} ms/tick`;

export default function OverviewPage({ state, onPage }) {
  const active = state.active;
  const pending = state.pending;
  const runtime = state.liveGameplaySettings || active;
  const liveMatchesPending = pending && !state.pendingGameplaySettingsDiffer;
  const wildBehavior = { unchanged: 'Sem alteração', clamp: 'Ajustar ao limite', block: 'Bloquear aparição' }[runtime.wildLevelBehavior] || 'Sem alteração';
  return <>
    <div className="page-intro"><h1>Visão geral</h1><p>Estado da configuração e do serviço no mundo atual.</p></div>
    {state.startupWarning && <Notice tone="warning">{state.startupWarning}</Notice>}
    {pending && <Notice tone="warning">A revisão {pending.revision} está pendente: gerações, ginásios e mundo continuam na revisão {active.revision} até reiniciar. XP, limites e captura {liveMatchesPending ? 'já correspondem à revisão pendente.' : `ainda usam a revisão em execução ${state.liveGameplayRevision ?? active.revision}.`}</Notice>}
    <Section title="Servidor" description={`Mundo ${state.world || 'atual'} · revisão ativa ${active.revision}`}>
      <div className="overview-facts">
        <div><span className="fact-icon"><Clock3 size={18} /></span><div><strong>{formatMs(state.averageTickMs)}</strong><span>Tempo médio recente de processamento do tick</span></div></div>
        <div><span className="fact-icon"><Cpu size={18} /></span><div><strong>{state.heapUsedMb} / {state.heapMaxMb} MB</strong><span>Memória Java usada no momento da consulta</span></div></div>
        <div><span className="fact-icon"><Users size={18} /></span><div><strong>{state.onlinePlayers?.length || 0}</strong><span>Jogadores conectados agora</span></div></div>
      </div>
    </Section>
    <Section title="Regras ativas" description="A revisão em uso no servidor.">
      <div className="summary-list">
        <div><span>Gerações</span><strong>{active.generations.join(', ')}</strong><button className="text-button" onClick={() => onPage('generations')}>Editar <ArrowRight size={14} /></button></div>
        <div><span>Circuitos</span><strong>{active.regions.length ? active.regions.join(', ') : 'Exploração livre'}</strong><button className="text-button" onClick={() => onPage('gyms')}>Editar <ArrowRight size={14} /></button></div>
        <div><span>Experiência em execução</span><strong>{runtime.manageExperience ? `${runtime.globalXpMultiplier}× global` : 'Gerenciamento desligado'}</strong><button className="text-button" onClick={() => onPage('experience')}>Editar <ArrowRight size={14} /></button></div>
        <div><span>Limite em execução</span><strong>{runtime.levelCapMode === 'global' ? `Global · nível ${runtime.globalLevelCap}` : 'Progresso RCT'} · {wildBehavior}</strong><button className="text-button" onClick={() => onPage('experience')}>Editar <ArrowRight size={14} /></button></div>
        <div><span>Captura em execução</span><strong>{runtime.captureMultiplier}× global</strong><button className="text-button" onClick={() => onPage('capture')}>Editar <ArrowRight size={14} /></button></div>
      </div>
    </Section>
    <Section title="Jogadores conectados" description="Consulta sob demanda; o painel não monitora a lista em segundo plano.">
      {state.onlinePlayers?.length ? <div className="online-list">{state.onlinePlayers.map((player) => <div key={player.uuid}><span className="online-dot" /><strong>{player.name}</strong><code>{player.uuid}</code></div>)}</div>
        : <p className="empty-state">Nenhum jogador conectado neste momento.</p>}
    </Section>
    <div className="overview-footer"><span>Última consulta: {new Date(state.generatedAt).toLocaleTimeString('pt-BR')}</span><button className="text-button" onClick={() => onPage('diagnostics')}>Abrir diagnóstico <ArrowRight size={14} /></button></div>
  </>;
}
