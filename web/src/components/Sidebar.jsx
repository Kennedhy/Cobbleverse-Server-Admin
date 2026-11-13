import { Globe2, X } from 'lucide-react';
import { PixelIcon } from './PixelIcon.jsx';

const items = [
  ['overview', 'Visão geral', 'ball'],
  ['generations', 'Gerações e ginásios', 'book'],
  ['rules', 'Regras', 'scroll'],
  ['experience', 'XP e níveis', 'chart'],
  ['capture', 'Captura', 'ball'],
  ['trainers', 'Treinadores', 'trainer'],
  ['pc', 'PC dos jogadores', 'monitor'],
  ['history', 'Histórico', 'clock'],
  ['world', 'Mundo', Globe2],
];

export function Sidebar({ page, onPage, open, onClose }) {
  return <>
    {open && <button className="mobile-scrim" aria-label="Fechar navegação" onClick={onClose} />}
    <aside className={`sidebar ${open ? 'sidebar-open' : ''}`}>
      <button className="icon-button sidebar-close" onClick={onClose} aria-label="Fechar navegação"><X size={18} /></button>
      <nav aria-label="Administração do servidor">
        {items.map(([id, label, Icon]) => <button key={id} type="button" className={`nav-item ${page === id ? 'active' : ''}`}
          aria-current={page === id ? 'page' : undefined} onClick={() => { onPage(id); onClose(); }}>
          {typeof Icon === 'string' ? <PixelIcon name={Icon} size={29} /> : <Icon size={29} strokeWidth={2.4} aria-hidden="true" />}<span>{label}</span>
        </button>)}
      </nav>
      <div className="dex-sidebar-end" aria-hidden="true"><PixelIcon name="ball" size={35} /></div>
    </aside>
  </>;
}
