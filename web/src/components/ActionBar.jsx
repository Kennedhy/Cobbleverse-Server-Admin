import { Check, LoaderCircle, Play, Save, RefreshCw } from 'lucide-react';

export function ActionBar({ onRefresh, onValidate, onSave, onApplyLive, dirty, validated, working,
  pending, applyLiveAvailable, liveGameplayRevision, message }) {
  const status = message || (pending
    ? applyLiveAvailable
      ? `XP, níveis, captura, shiny e limites lendários ainda usam a revisão ${liveGameplayRevision}; as demais regras aguardam reinício.`
      : 'XP, níveis, captura, shiny e limites lendários já correspondem ao servidor; as demais regras aguardam reinício.'
    : 'Alterações salvas entram em vigor após reiniciar o servidor.');
  return <div className="action-dock">
    <div className="action-row">
      <button className="button button-quiet" type="button" onClick={onRefresh} disabled={working}>
        <RefreshCw size={16} />Atualizar
      </button>
      <div className="action-primary">
        <button className="button button-secondary" type="button" onClick={onValidate} disabled={working || !dirty}>
          {working ? <LoaderCircle className="spin" size={16} /> : <Check size={16} />}
          {working ? 'Validando…' : 'Validar configuração'}
        </button>
        <button className="button button-accent" type="button" onClick={onSave} disabled={working || !dirty || !validated}>
          <Save size={16} />Salvar alterações
        </button>
      </div>
    </div>
    <p className={`dock-status ${pending ? 'status-warning' : validated && dirty ? 'status-success' : ''}`}>
      {status}
    </p>
    {pending && applyLiveAvailable && <div className="live-apply-row">
      <span>Aplicar XP, níveis, captura, shiny e limites lendários da revisão pendente sem reiniciar. Gerações, ginásios e mundo continuam pendentes.</span>
      <button className="button button-quiet button-small" type="button" disabled={working || dirty} onClick={onApplyLive}>
        <Play size={15} />Aplicar ao vivo
      </button>
    </div>}
  </div>;
}
