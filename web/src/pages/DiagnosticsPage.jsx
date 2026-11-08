import { AlertTriangle, CheckCircle2, RotateCcw, Trash2 } from 'lucide-react';
import { Notice, Section } from '../components/ui.jsx';

function ReportList({ title, items, tone }) {
  if (!items?.length) return null;
  return <div className={`report-list report-${tone}`}><h3>{title}</h3><ul>{items.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul></div>;
}

export default function DiagnosticsPage({ state, onRestore, onDiscard }) {
  const report = state.job?.report;
  const tick = Number(state.averageTickMs || 0);
  return <>
    <div className="page-intro"><h1>Diagnóstico</h1><p>Consulte o estado atual, valide integrações e reverta alterações pendentes.</p></div>
    <Section title="Estado do servidor" description={`Mundo ${state.world} · consulta em ${new Date(state.generatedAt).toLocaleTimeString('pt-BR')}`}>
      <div className="diagnostic-table">
        <div><span>Revisão ativa</span><strong>{state.active.revision}</strong></div>
        <div><span>Revisão pendente</span><strong>{state.pending ? state.pending.revision : 'Nenhuma'}</strong></div>
        <div><span>Tick médio</span><strong>{tick.toFixed(2)} ms</strong></div>
        <div><span>Memória Java</span><strong>{state.heapUsedMb} / {state.heapMaxMb} MB</strong></div>
        <div><span>Jogadores conectados</span><strong>{state.onlinePlayers?.length || 0}</strong></div>
        <div><span>Arquivos de dados sobrepostos</span><strong>{state.transformedFiles}</strong></div>
        <div><span>Equipes RCT adaptadas</span><strong>{state.transformedTeams}</strong></div>
      </div>
      <p className="field-hint">O painel mostra MSPT e heap quando esta página é consultada. Para TPS, CPU, GC e perfil de threads, use <a href="https://spark.lucko.me/docs/Command-Usage" target="_blank" rel="noreferrer">spark</a>; o gerenciador não coleta métricas em segundo plano.</p>
    </Section>
    {state.job && <Section title="Última validação" description={state.job.message}>
      <div className={`job-status job-${state.job.status}`}>
        {state.job.status === 'validated' || state.job.status === 'saved' ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}
        <div><strong>{state.job.status === 'running' ? 'Análise em andamento' : state.job.status === 'validated' ? 'Configuração válida' : state.job.status === 'saved' ? 'Revisão pendente salva' : 'Validação encontrou problemas'}</strong>
          <span>Revisão analisada: {state.job.revision} · {new Date(state.job.startedAt).toLocaleString('pt-BR')}</span></div>
      </div>
      {report && <>
        <div className="report-counts">
          <span><strong>{report.allowedSpecies}</strong> espécies disponíveis</span>
          <span><strong>{report.trainerTeams}</strong> equipes analisadas</span>
          <span><strong>{report.adaptedTeams}</strong> equipes adaptadas</span>
          <span><strong>{report.inspectedPlayers}</strong> jogadores analisados</span>
          <span><strong>{report.affectedPokemon}</strong> Pokémon afetados</span>
          <span><strong>{report.reservePokemon}</strong> Pokémon na reserva</span>
        </div>
        <ReportList title="Erros" items={report.errors} tone="error" />
        <ReportList title="Avisos" items={report.warnings} tone="warning" />
        <ReportList title="Compatibilidade" items={report.integrations} tone="neutral" />
        {report.affectedPlayerNames?.length > 0 && <p className="field-hint">Jogadores afetados: {report.affectedPlayerNames.join(', ')}</p>}
      </>}
    </Section>}
    {state.startupWarning && <Notice tone="warning">{state.startupWarning}</Notice>}
    <Section title="Recuperação" description="Estas ações também ficam pendentes até reiniciar o servidor.">
      <div className="recovery-actions">
        <button className="button button-quiet" type="button" disabled={!state.hasPrevious} onClick={onRestore}><RotateCcw size={16} />Restaurar revisão anterior</button>
        <button className="button button-danger" type="button" disabled={!state.pending} onClick={onDiscard}><Trash2 size={16} />Descartar alterações pendentes</button>
      </div>
      {!state.hasPrevious && !state.pending && <p className="empty-state">Não há uma revisão anterior ou alteração pendente para recuperar.</p>}
    </Section>
  </>;
}
