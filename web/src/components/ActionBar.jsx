import { Check, LoaderCircle, Save, RefreshCw } from 'lucide-react';

export function ActionBar({ onRefresh, onValidate, onSave, dirty, validated, working, pending, message }) {
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
      {message || (pending ? 'Há alterações pendentes. Reinicie o servidor para aplicá-las.' : 'Alterações salvas entram em vigor após reiniciar o servidor.')}
    </p>
  </div>;
}
