import { Activity, BadgeCheck, CircleDot, Crosshair, FileJson2, Globe2, History, House, KeyRound, Monitor, Settings2, Shield, X } from 'lucide-react';

const items = [
  ['overview', 'Visão geral', House],
  ['access', 'Acessos', KeyRound],
  ['generations', 'Gerações', CircleDot],
  ['gyms', 'Ginásios', BadgeCheck],
  ['rules', 'Regras', Settings2],
  ['experience', 'XP e níveis', Activity],
  ['capture', 'Captura', Crosshair],
  ['pc', 'PC dos jogadores', Monitor],
  ['history', 'Histórico', History],
  ['presets', 'Presets e arquivos', FileJson2],
  ['world', 'Mundo', Globe2],
  ['diagnostics', 'Diagnóstico', Shield],
];

export function Sidebar({ page, onPage, open, onClose, canEdit }) {
  const visibleItems = canEdit ? items : items.filter(([id]) => id !== 'access');
  return <>
    {open && <button className="mobile-scrim" aria-label="Fechar navegação" onClick={onClose} />}
    <aside className={`sidebar ${open ? 'sidebar-open' : ''}`}>
      <button className="icon-button sidebar-close" onClick={onClose} aria-label="Fechar navegação"><X size={18} /></button>
      <nav aria-label="Administração do servidor">
        {visibleItems.map(([id, label, Icon]) => <button key={id} type="button" className={`nav-item ${page === id ? 'active' : ''}`}
          aria-current={page === id ? 'page' : undefined} onClick={() => { onPage(id); onClose(); }}>
          <Icon size={18} strokeWidth={1.8} aria-hidden="true" /><span>{label}</span>
        </button>)}
      </nav>
      <div className="sidebar-foot"><span className="online-dot" />Painel local do servidor</div>
    </aside>
  </>;
}
