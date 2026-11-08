import { AlertTriangle, CheckCircle2, RotateCcw, Trash2, XCircle } from 'lucide-react';
import { useEffect, useState } from 'react';
import { request } from '../api.js';
import { Notice, Section } from '../components/ui.jsx';

function ReportList({ title, items, tone }) {
  if (!items?.length) return null;
  return <div className={`report-list report-${tone}`}><h3>{title}</h3><ul>{items.map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul></div>;
}

function MetricChart({ title, samples, field, unit, color, threshold, maxField }) {
  const chartSamples = samples.filter((sample) => sample[field] != null && Number.isFinite(Number(sample[field])) && Number(sample[field]) >= 0);
  if (!chartSamples.length) return <div className="metric-chart-empty">{samples.length ? 'Esta métrica não está disponível nesta JVM.' : 'A primeira amostra está sendo coletada.'}</div>;
  const values = chartSamples.map((sample) => Number(sample[field]));
  const maxValue = maxField
    ? Math.max(...chartSamples.map((sample) => Number(sample[maxField]) || 0))
    : Math.max(threshold || 0, ...values);
  const axisMax = Math.max(maxValue, 1);
  const width = 720; const height = 220;
  const left = 54; const right = 18; const top = 14; const bottom = 34;
  const chartWidth = width - left - right; const chartHeight = height - top - bottom;
  const y = (value) => top + chartHeight * (1 - Math.max(0, Math.min(value, axisMax)) / axisMax);
  const points = values.map((value, index) => `${left + chartWidth * (values.length === 1 ? 0.5 : index / (values.length - 1))},${y(value)}`).join(' ');
  const gridLevels = [0, 0.5, 1];
  const firstTime = new Date(chartSamples[0].time).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  const lastTime = new Date(chartSamples[chartSamples.length - 1].time).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
  const latest = values[values.length - 1];
  return <div className="metric-chart">
    <div className="metric-chart-head"><h3>{title}</h3><strong>{latest.toFixed(field === 'tickMs' ? 1 : 0)} {unit}</strong></div>
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${title}: ${latest.toFixed(1)} ${unit}, ${samples.length} amostras`}>
      {gridLevels.map((level) => {
        const gridY = top + chartHeight * (1 - level);
        return <g key={level}>
          <line x1={left} x2={width - right} y1={gridY} y2={gridY} className="metric-gridline" />
          <text x={left - 8} y={gridY + 4} className="metric-axis-label" textAnchor="end">{(axisMax * level).toFixed(field === 'tickMs' ? 0 : 0)}</text>
        </g>;
      })}
      {threshold != null && threshold <= axisMax && <g>
        <line x1={left} x2={width - right} y1={y(threshold)} y2={y(threshold)} className="metric-threshold" />
        <text x={width - right - 2} y={y(threshold) - 5} className="metric-threshold-label" textAnchor="end">limite {threshold} ms</text>
      </g>}
      <polyline points={points} fill="none" stroke={color} strokeWidth="2.5" strokeLinejoin="round" strokeLinecap="round" />
      <text x={left} y={height - 8} className="metric-axis-label">{firstTime}</text>
      <text x={width - right} y={height - 8} className="metric-axis-label" textAnchor="end">{lastTime}</text>
    </svg>
  </div>;
}

export default function DiagnosticsPage({ state, onRestore, onDiscard, onCancelValidation, canEdit }) {
  const [metricHistory, setMetricHistory] = useState([]);
  const [metricsError, setMetricsError] = useState('');
  const report = state.job?.report;
  const progress = state.job?.progress;
  const persistence = state.persistenceQueue || {};
  const tick = Number(state.averageTickMs || 0);
  const latestSample = metricHistory.at(-1);
  useEffect(() => {
    let active = true;
    let timer;
    let controller;
    const collect = async () => {
      controller = new AbortController();
      try {
        const result = await request('/api/metrics', { signal: controller.signal });
        if (active) { setMetricHistory(result.samples || []); setMetricsError(''); }
      } catch (failure) {
        if (active) setMetricsError(failure.message);
      }
      if (active) timer = setTimeout(collect, 15_000);
    };
    collect();
    return () => { active = false; clearTimeout(timer); controller?.abort(); };
  }, []);
  const phases = { 'species-validation': 'catálogo de espécies', 'team-validation': 'equipes', 'player-enumeration': 'lista de jogadores', 'player-scan': 'PCs dos jogadores',
    'player-store-recheck': 'store de Pokémon alterado', 'consistency-check': 'consistência dos dados', 'offline-file-check': 'arquivos offline' };
  return <>
    <div className="page-intro"><h1>Diagnóstico</h1><p>Consulte o estado atual, valide integrações e reverta alterações pendentes.</p></div>
    <Section title="Estado do servidor" description={`Mundo ${state.world} · consulta em ${new Date(state.generatedAt).toLocaleTimeString('pt-BR')}`}>
      <div className="diagnostic-table">
        <div><span>Revisão ativa</span><strong>{state.active.revision}</strong></div>
        <div><span>Revisão pendente</span><strong>{state.pending ? state.pending.revision : 'Nenhuma'}</strong></div>
        <div><span>Tick médio</span><strong>{tick.toFixed(2)} ms</strong></div>
        <div><span>TPS estimado pelo MSPT</span><strong>{Number(state.estimatedTps || 0).toFixed(1)} / 20</strong></div>
        <div><span>Memória Java</span><strong>{state.heapUsedMb} / {state.heapMaxMb} MB</strong></div>
        <div><span>CPU do processo Java</span><strong>{latestSample?.processCpuPercent == null ? 'Indisponível' : `${latestSample.processCpuPercent.toFixed(1)}%`}</strong></div>
        <div><span>GC desde a amostra anterior</span><strong>{latestSample ? `${latestSample.gcCollectionDelta} coleções · ${latestSample.gcTimeDeltaMs} ms` : 'Aguardando amostra'}</strong></div>
        <div><span>Jogadores conectados</span><strong>{state.onlinePlayers?.length || 0}</strong></div>
        <div><span>Arquivos de dados sobrepostos</span><strong>{state.transformedFiles}</strong></div>
        <div><span>Equipes RCT adaptadas</span><strong>{state.transformedTeams}</strong></div>
        <div><span>Fila de persistência</span><strong>{persistence.active || 0} ativa · {persistence.queued || 0}/{persistence.capacity || 2} aguardando</strong></div>
        <div><span>Recusas desde a inicialização</span><strong>{persistence.rejected || 0} gravações · {state.rejectedValidations || 0} validações</strong></div>
      </div>
      <p className="field-hint">O TPS é uma estimativa derivada do MSPT; a CPU vem da JVM e pode não estar disponível em todas as máquinas. Para perfis de threads e análise detalhada, use <a href="https://spark.lucko.me/docs/Command-Usage" target="_blank" rel="noreferrer">spark</a>. O histórico abaixo é coletado somente enquanto esta página fica aberta.</p>
    </Section>
    <Section title="Histórico recente" description={`Amostras a cada 15 segundos, limitadas aos últimos 30 minutos · ${metricHistory.length} amostras em memória`}>
      {metricsError && <Notice tone="warning">Não foi possível atualizar o histórico: {metricsError}</Notice>}
      <div className="metrics-grid">
        <MetricChart title="Tick médio (MSPT)" samples={metricHistory} field="tickMs" unit="ms" color="#20cbb6" threshold={50} />
        <MetricChart title="Heap usado" samples={metricHistory} field="heapUsedMb" maxField="heapMaxMb" unit="MB" color="#80a8ff" />
        <MetricChart title="CPU do processo Java" samples={metricHistory} field="processCpuPercent" unit="%" color="#c587ff" />
        <MetricChart title="Tempo de GC entre amostras" samples={metricHistory} field="gcTimeDeltaMs" unit="ms" color="#f1b35b" />
      </div>
      <p className="field-hint">As amostras ficam apenas na memória e são apagadas quando o servidor reinicia. O painel não faz consultas quando esta página está fechada.</p>
    </Section>
    {state.job && <Section title="Última validação" description={state.job.message}>
      <div className={`job-status job-${state.job.status}`}>
        {['validated', 'saved', 'completed'].includes(state.job.status) ? <CheckCircle2 size={18} /> : <AlertTriangle size={18} />}
        <div><strong>{state.job.status === 'running' ? state.job.action === 'save' ? 'Salvando revisão' : ['restore', 'restoreRevision', 'discard'].includes(state.job.action) ? 'Processando alteração administrativa' : 'Análise em andamento' : state.job.status === 'validated' ? 'Configuração válida' : state.job.status === 'saved' ? 'Revisão pendente salva' : state.job.status === 'completed' ? 'Alteração administrativa concluída' : state.job.status === 'cancelled' ? 'Análise cancelada' : 'Validação encontrou problemas'}</strong>
          <span>Revisão analisada: {state.job.revision} · {new Date(state.job.startedAt).toLocaleString('pt-BR')}</span></div>
      </div>
      {state.job.status === 'running' && state.job.action === 'validate' && progress && <p className="field-hint validation-progress">
        Fase: {phases[progress.phase] || progress.phase} · {((progress.elapsedMs || 0) / 1000).toFixed(1)} s
        {progress.speciesScanned > 0 && ` · Espécies verificadas ${progress.speciesScanned}`}
        {progress.totalPlayers != null && ` · Jogadores ${progress.playersScanned}/${progress.totalPlayers}`}
        {progress.teamsTotal > 0 && ` · Equipes adaptadas ${progress.teamsAdapted}/${progress.teamsTotal}`}
      </p>}
      {canEdit && state.job.status === 'running' && state.job.action === 'validate' && <button className="button button-quiet" type="button" onClick={onCancelValidation}>
        <XCircle size={16} />Cancelar análise
      </button>}
      {report && <>
        {report.playerImpactStale && <Notice tone="warning">{report.playerImpactMessage || 'As contagens de impacto não estão disponíveis nesta validação.'}</Notice>}
        <div className="report-counts">
          <span><strong>{report.allowedSpecies}</strong> espécies disponíveis</span>
          <span><strong>{report.trainerTeams}</strong> equipes analisadas</span>
          <span><strong>{report.adaptedTeams}</strong> equipes adaptadas</span>
          <span><strong>{report.playerImpactStale ? '—' : report.inspectedPlayers}</strong> jogadores analisados</span>
          <span><strong>{report.playerImpactStale ? '—' : report.affectedPokemon}</strong> Pokémon afetados</span>
          <span><strong>{report.playerImpactStale ? '—' : report.reservePokemon}</strong> Pokémon na reserva</span>
        </div>
        <ReportList title="Erros" items={report.errors} tone="error" />
        <ReportList title="Avisos" items={report.warnings} tone="warning" />
        <ReportList title="Compatibilidade" items={report.integrations} tone="neutral" />
        {report.affectedPlayerNames?.length > 0 && <p className="field-hint">Jogadores afetados: {report.affectedPlayerNames.join(', ')}</p>}
      </>}
    </Section>}
    {state.startupWarning && <Notice tone="warning">{state.startupWarning}</Notice>}
    <Section title="Recuperação" description="Restaurar prepara uma revisão para o próximo reinício. Descartar remove a revisão pendente e reverte XP, limites e captura já aplicados ao estado ativo.">
      {canEdit ? <>
      <div className="recovery-actions">
        <button className="button button-quiet" type="button" disabled={!state.hasPrevious} onClick={onRestore}><RotateCcw size={16} />Restaurar revisão anterior</button>
        <button className="button button-danger" type="button" disabled={!state.pending} onClick={onDiscard}><Trash2 size={16} />Descartar alterações pendentes</button>
      </div>
      {!state.hasPrevious && !state.pending && <p className="empty-state">Não há uma revisão anterior ou alteração pendente para recuperar.</p>}
      </> : <Notice>Esta sessão tem permissão de consulta. Use o token de administrador para restaurar ou descartar alterações.</Notice>}
    </Section>
  </>;
}
