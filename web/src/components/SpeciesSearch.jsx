import { Check, LoaderCircle, Search, ShieldBan, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { request } from '../api.js';

export default function SpeciesSearch({ draft, update, onClose }) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [ready, setReady] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const term = query.trim();
    if (term.length < 2) { setResults([]); setLoading(false); setError(''); return undefined; }
    let cancelled = false;
    const timer = setTimeout(async () => {
      setLoading(true); setError('');
      try {
        const payload = await request(`/api/species?q=${encodeURIComponent(term)}`);
        if (cancelled) return;
        setResults(payload.items || []); setReady(payload.ready !== false);
      } catch (failure) {
        if (!cancelled) setError(failure.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }, 250);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query]);

  const choose = (entry, allow) => {
    const allowSpecies = new Set(draft.allowSpecies || []);
    const denySpecies = new Set(draft.denySpecies || []);
    allowSpecies.delete(entry.id); denySpecies.delete(entry.id);
    (allow ? allowSpecies : denySpecies).add(entry.id);
    update({ allowSpecies: [...allowSpecies], denySpecies: [...denySpecies] });
  };

  return <div className="dialog-shade" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="species-dialog" role="dialog" aria-modal="true" aria-labelledby="species-dialog-title">
      <header className="dialog-head"><div><h2 id="species-dialog-title">Pesquisar espécies</h2><p>Crie uma exceção para a seleção de gerações.</p></div><button className="icon-button" aria-label="Fechar" onClick={onClose}><X size={19} /></button></header>
      <label className="search-field dialog-search"><Search size={18} /><input autoFocus value={query} placeholder="Nome interno, por exemplo: eevee" onChange={(event) => setQuery(event.target.value)} /></label>
      {!ready && <p className="empty-state">O catálogo aparece depois que os dados do servidor carregarem.</p>}
      {loading && <div className="dialog-loading"><LoaderCircle className="spin" size={17} />Buscando no catálogo carregado…</div>}
      {error && <p className="inline-error">{error}</p>}
      {!loading && !error && query.trim().length >= 2 && results.length === 0 && ready && <p className="empty-state">Nenhuma espécie encontrada para essa busca.</p>}
      {query.trim().length < 2 && <p className="empty-state">Digite pelo menos duas letras para pesquisar.</p>}
      <div className="species-results">
        {results.map((entry) => {
          const isAllowed = (draft.allowSpecies || []).includes(entry.id);
          const isDenied = (draft.denySpecies || []).includes(entry.id);
          return <div className="species-result" key={entry.id}>
            <div><strong>{entry.name}</strong><span>{entry.id} · #{entry.dex} · Geração {entry.generation}</span></div>
            <div className="species-actions">
              <button type="button" className={`mini-choice ${isAllowed ? 'selected allow' : ''}`} aria-label={`Permitir ${entry.name}`} onClick={() => choose(entry, true)}><Check size={15} /></button>
              <button type="button" className={`mini-choice ${isDenied ? 'selected deny' : ''}`} aria-label={`Bloquear ${entry.name}`} onClick={() => choose(entry, false)}><ShieldBan size={15} /></button>
            </div>
          </div>;
        })}
      </div>
      <footer className="dialog-foot"><span><Check size={14} /> Permitir <ShieldBan size={14} /> Bloquear</span><button className="button button-secondary" onClick={onClose}>Concluir</button></footer>
    </section>
  </div>;
}
