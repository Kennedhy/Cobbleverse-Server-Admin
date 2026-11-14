import { useCallback, useEffect, useRef, useState } from 'react';
import { BadgeDollarSign, Gavel, Package, RefreshCw, Search, ShieldAlert, Store, Trash2, X } from 'lucide-react';
import { request } from '../api.js';
import { Field, Notice, NumberField, Section, Toggle } from '../components/ui.jsx';
import './market.css';

const emptySummary = { activeListings: 0, pokemonListings: 0, itemListings: 0, pendingClaims: 0 };
const defaultSettings = {
  enabled: false,
  pokemonSales: true,
  itemSales: true,
  auctions: true,
  maxListingsPerPlayer: 8,
  listingDurationHours: 72,
  auctionMinDurationMinutes: 30,
  auctionMaxDurationHours: 168,
  minBidIncrement: 1,
};
const statFields = [['hp', 'PS'], ['atk', 'ATQ'], ['def', 'DEF'], ['spa', 'ATS'], ['spd', 'DFS'], ['spe', 'VEL']];
const listingStates = { active: 'Ativo', sold: 'Vendido', removed: 'Removido', expired: 'Expirado', cancelled: 'Cancelado' };
const transactionKinds = {
  purchase: 'Compra', auction_sale: 'Leilão encerrado', bid: 'Lance reservado', seller_payout: 'Pagamento ao vendedor',
  outbid_refund: 'Reembolso de lance superado', bid_refund: 'Reembolso administrativo', admin_return: 'Devolução administrativa',
  listing_cancelled: 'Anúncio cancelado', listing_expired: 'Anúncio expirado', purchase_delivery: 'Entrega da compra',
  auction_delivery: 'Entrega do leilão',
};

function date(value) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString();
}

function ListingCard({ listing, canEdit, busy, onRemove }) {
  const pokemon = listing.type === 'pokemon';
  const title = pokemon ? (listing.nickname || listing.speciesName || listing.species || 'Pokémon') : (listing.itemName || listing.itemId || 'Item');
  const stats = listing.pokemon || listing;
  const ivs = stats.ivs || {};
  const evs = stats.evs || {};
  const hasBids = Boolean(listing.hasBids ?? (Number(listing.highestBid) > 0));
  const auctionAmount = hasBids
    ? (listing.highestBid ?? listing.currentBid ?? 0)
    : (listing.startingBid ?? listing.price ?? 0);
  return <article className="market-listing">
    <div className="market-listing-icon" aria-hidden="true">{pokemon ? '◉' : <Package size={22} />}</div>
    <div className="market-listing-main">
      <div className="market-listing-title"><strong>{title}</strong><span className={`market-kind ${pokemon ? 'is-pokemon' : 'is-item'}`}>{pokemon ? 'Pokémon' : 'Item'}</span>
        {stats.shiny && <span className="market-shiny">Shiny</span>}
        {listing.mode === 'auction' && <span className="market-auction"><Gavel size={12} /> Leilão</span>}
        <span className={`market-status market-status-${listing.status || 'unknown'}`}>{listingStates[listing.status] || listing.status || 'Estado desconhecido'}</span>
      </div>
      {pokemon ? <>
        <div className="market-listing-meta">Espécie {listing.speciesName || listing.species || '—'} · Nv. {stats.level ?? '—'} · {stats.form ? `Forma ${stats.form} · ` : ''}{stats.nature || 'Natureza —'} · Tamanho {stats.size ?? '—'} · Vendedor {listing.sellerName || listing.seller || '—'}</div>
        <div className="market-stat-line"><span>IV</span>{statFields.map(([key, label]) => <span key={key}>{label} {ivs[key] ?? '—'}</span>)}</div>
        <div className="market-stat-line"><span>EV</span>{statFields.map(([key, label]) => <span key={key}>{label} {evs[key] ?? '—'}</span>)}</div>
      </> : <div className="market-listing-meta">Quantidade {listing.count ?? listing.quantity ?? 1} · Vendedor {listing.sellerName || listing.seller || '—'}</div>}
      <div className="market-listing-meta">Criado {date(listing.createdAt)} · Expira {date(listing.expiresAt)}</div>
    </div>
    <div className="market-price"><small>{listing.mode === 'auction' ? (hasBids ? 'Maior lance' : 'Lance inicial') : 'Preço'}</small><strong><BadgeDollarSign size={16} />{listing.mode === 'auction' ? auctionAmount : listing.price ?? 0}</strong><small>CobbleDollars</small></div>
    {canEdit && listing.status === 'active' && <button className="button button-quiet button-small market-remove" type="button" disabled={busy} onClick={() => onRemove(listing)} title="Remover anúncio"><Trash2 size={15} />{busy ? 'Removendo…' : 'Remover'}</button>}
  </article>;
}

function TransactionTable({ rows }) {
  if (!rows.length) return <p className="empty-state">Nenhuma transação registrada.</p>;
  return <div className="table-wrap market-table-wrap"><table><thead><tr><th>Data</th><th>Operação</th><th>Participante</th><th>Contraparte</th><th>Bem</th><th>Valor</th></tr></thead><tbody>
    {rows.map((row) => <tr key={row.id}><td>{date(row.completedAt || row.createdAt)}</td><td>{transactionKinds[row.kind] || row.kind || 'Operação'}</td><td>{row.actorName || '—'}</td><td>{row.counterpartyName || '—'}</td><td>{row.displayName || row.speciesName || row.itemName || (row.type === 'pokemon' ? 'Pokémon' : 'Item')}</td><td>{row.price ?? '—'} CobbleDollars</td></tr>)}
  </tbody></table></div>;
}

function PendingOperationsTable({ rows }) {
  if (!rows.length) return <p className="empty-state">Nenhuma operação aguardando conclusão.</p>;
  const kinds = {
    deposit: 'Depósito em custódia', purchase: 'Compra', bid: 'Lance', seller_payout: 'Pagamento ao vendedor',
    outbid_refund: 'Reembolso de lance', bid_refund: 'Reembolso de lance', admin_return: 'Devolução administrativa',
    listing_cancelled: 'Anúncio cancelado', listing_expired: 'Anúncio expirado', purchase_delivery: 'Entrega de compra',
    auction_delivery: 'Entrega de leilão',
  };
  const statuses = {
    deposit_pending: 'Depósito pendente', buyer_debit_pending: 'Débito em reconciliação',
    cash_pending: 'Pagamento pendente', cash_claim_pending: 'Pagamento em reconciliação',
    claim_pending: 'Retirada pendente', asset_claim_pending: 'Entrega em reconciliação',
  };
  return <div className="table-wrap market-table-wrap"><table><thead><tr><th>Desde</th><th>Operação</th><th>Bem</th><th>Jogador</th><th>Valor</th><th>Estado</th><th>Referência e saldo</th></tr></thead><tbody>
    {rows.map((row) => {
      const balance = row.balanceExpected
        ? `Antes ${row.balanceBefore} · Esperado ${row.balanceExpected} · Atual ${row.balanceObserved || 'offline'}`
        : '';
      const balanceWarning = row.balanceState === 'conflict' ? ' · saldo divergente; conferir antes de intervir' : '';
      return <tr key={row.id}><td>{date(row.createdAt)}</td><td>{kinds[row.kind] || row.kind}</td><td>{row.displayName || '—'}</td><td>{row.actorName || '—'}</td><td>{row.amount || '—'}</td><td>{statuses[row.status] || row.status}</td><td>{balance || row.reason || row.id?.slice(0, 8) || '—'}{balanceWarning}</td></tr>;
    })}
  </tbody></table></div>;
}

export default function MarketPage({ state }) {
  const canEdit = state?.accessRole !== 'viewer';
  const [tab, setTab] = useState('listings');
  const [filters, setFilters] = useState({ q: '', type: 'all', status: 'active' });
  const [appliedFilters, setAppliedFilters] = useState({ q: '', type: 'all', status: 'active' });
  const [listings, setListings] = useState([]);
  const [transactions, setTransactions] = useState([]);
  const [pendingOperations, setPendingOperations] = useState([]);
  const [summary, setSummary] = useState(emptySummary);
  const [economyAvailable, setEconomyAvailable] = useState(null);
  const [cursor, setCursor] = useState(null);
  const [transactionCursor, setTransactionCursor] = useState(null);
  const [pendingCursor, setPendingCursor] = useState(null);
  const [settings, setSettings] = useState(null);
  const [baseline, setBaseline] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState('');
  const [removeTarget, setRemoveTarget] = useState(null);
  const [removeReason, setRemoveReason] = useState('Removido pela administração');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const dirty = Boolean(settings) && JSON.stringify(settings) !== baseline;
  const readController = useRef(null);
  const beginRead = useCallback(() => {
    readController.current?.abort();
    const controller = new AbortController();
    readController.current = controller;
    return controller;
  }, []);

  const loadListings = useCallback(async ({ append = false, nextCursor = null } = {}) => {
    const controller = beginRead();
    setLoading(true); setError('');
    if (!append) {
      setListings([]); setSummary(emptySummary); setEconomyAvailable(null); setCursor(null);
    }
    try {
      const params = new URLSearchParams({ q: appliedFilters.q.trim(), type: appliedFilters.type, status: appliedFilters.status });
      if (nextCursor) params.set('cursor', nextCursor);
      const data = await request(`/api/market/listings?${params}`, { signal: controller.signal });
      if (controller.signal.aborted) return;
      setListings((current) => append ? [...current, ...(data.listings || [])] : (data.listings || []));
      setSummary({ ...emptySummary, ...(data.summary || {}) });
      setEconomyAvailable(data.economyAvailable ?? null);
      setCursor(data.nextCursor || null);
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (!append) {
        setListings([]); setSummary(emptySummary); setEconomyAvailable(null); setCursor(null);
      }
      setError(failure.message);
    }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }, [appliedFilters, beginRead]);

  const loadTransactions = useCallback(async ({ append = false, nextCursor = null, appendPending = false, nextPendingCursor = null } = {}) => {
    const controller = beginRead();
    setLoading(true); setError('');
    if (!append && !appendPending) {
      setTransactions([]); setPendingOperations([]); setTransactionCursor(null); setPendingCursor(null);
    }
    try {
      const params = new URLSearchParams();
      if (nextCursor) params.set('cursor', nextCursor);
      if (nextPendingCursor) params.set('pendingCursor', nextPendingCursor);
      const data = await request(`/api/market/transactions${params.size ? `?${params}` : ''}`, { signal: controller.signal });
      if (controller.signal.aborted) return;
      if (!appendPending) {
        setTransactions((current) => append ? [...current, ...(data.transactions || [])] : (data.transactions || []));
        setTransactionCursor(data.nextCursor || null);
      }
      if (!append && !appendPending) setPendingOperations(data.pendingOperations || []);
      else if (appendPending) setPendingOperations((current) => [...current, ...(data.pendingOperations || [])]);
      if (!append || appendPending) setPendingCursor(data.nextPendingCursor || null);
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (!append && !appendPending) {
        setTransactions([]); setPendingOperations([]); setTransactionCursor(null); setPendingCursor(null);
      }
      setError(failure.message);
    }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }, [beginRead]);

  const loadSettings = useCallback(async () => {
    const controller = beginRead();
    setLoading(true); setError('');
    try {
      const data = await request('/api/market/settings', { signal: controller.signal });
      if (controller.signal.aborted) return;
      const next = { ...defaultSettings, ...data };
      setSettings(next); setBaseline(JSON.stringify(next));
    } catch (failure) {
      if (controller.signal.aborted) return;
      setSettings(null); setBaseline(''); setError(failure.message);
    }
    finally { if (!controller.signal.aborted) setLoading(false); }
  }, [beginRead]);

  useEffect(() => {
    if (tab === 'listings') void loadListings();
    if (tab === 'transactions') void loadTransactions();
    if (tab === 'settings') void loadSettings();
    return () => readController.current?.abort();
  }, [tab, loadListings, loadTransactions, loadSettings]);

  const removeListing = async () => {
    const listing = removeTarget;
    const reason = removeReason.trim();
    if (!listing || !reason || reason.length > 160 || busyId) return;
    setBusyId(listing.id); setError(''); setMessage('');
    try {
      await request(`/api/market/listings/${encodeURIComponent(listing.id)}/remove`, { method: 'POST', body: { reason } });
      setMessage('Anúncio removido. A devolução do bem foi registrada pelo servidor.');
      setRemoveTarget(null);
      await loadListings();
    } catch (failure) { setError(failure.message); }
    finally { setBusyId(''); }
  };

  const openRemoveDialog = (listing) => {
    setError('');
    setRemoveReason('Removido pela administração');
    setRemoveTarget(listing);
  };

  const saveSettings = async () => {
    if (!settings || !dirty || !canEdit || saving) return;
    setSaving(true); setError(''); setMessage('');
    try {
      const data = await request('/api/market/settings', { method: 'PUT', body: settings });
      const next = { ...defaultSettings, ...data };
      setSettings(next); setBaseline(JSON.stringify(next)); setMessage('Configurações do mercado salvas.');
    } catch (failure) { setError(failure.message); }
    finally { setSaving(false); }
  };

  const submitFilters = (event) => {
    event.preventDefault();
    const unchanged = filters.q.trim() === appliedFilters.q.trim()
      && filters.type === appliedFilters.type
      && filters.status === appliedFilters.status;
    if (unchanged) void loadListings();
    else setAppliedFilters({ ...filters });
  };

  return <>
      <div className="page-intro market-intro"><div><h1>Mercado Pokémon</h1><p>GTS, leilões e transações do servidor.</p></div><button className="button button-quiet button-small" type="button" disabled={loading} onClick={() => tab === 'listings' ? void loadListings() : tab === 'transactions' ? void loadTransactions() : void loadSettings()}><RefreshCw size={15} />Atualizar</button></div>
    <div className="market-tabs" role="group" aria-label="Seções do mercado">
      <button type="button" aria-pressed={tab === 'listings'} className={tab === 'listings' ? 'is-active' : ''} onClick={() => setTab('listings')}><Store size={16} />Anúncios</button>
      <button type="button" aria-pressed={tab === 'transactions'} className={tab === 'transactions' ? 'is-active' : ''} onClick={() => setTab('transactions')}><BadgeDollarSign size={16} />Transações</button>
      <button type="button" aria-pressed={tab === 'settings'} className={tab === 'settings' ? 'is-active' : ''} onClick={() => setTab('settings')}><ShieldAlert size={16} />Configuração</button>
    </div>
    {error && <Notice tone="error">{error}</Notice>}
    {message && <Notice tone="success">{message}</Notice>}
    {economyAvailable === false && <Notice tone="warning">CobbleDollars não foi detectado. Anúncios, compras e lances ficam indisponíveis até o mod de economia carregar.</Notice>}
    {!canEdit && <Notice tone="warning">Credencial de consulta. Você pode inspecionar anúncios, sem moderar ou alterar regras.</Notice>}

    {tab === 'listings' && <>
      <div className="market-summary">
        <div><Store size={17} /><span><strong>{summary.activeListings}</strong>Anúncios ativos</span></div>
        <div><span className="market-summary-pokeball">◉</span><span><strong>{summary.pokemonListings}</strong>Pokémon</span></div>
        <div><Package size={17} /><span><strong>{summary.itemListings}</strong>Itens</span></div>
        <div><BadgeDollarSign size={17} /><span><strong>{summary.pendingClaims}</strong>Retiradas pendentes</span></div>
      </div>
      <Section title="Anúncios" description="Informações de Pokémon, itens, preços e estado da custódia.">
        <form className="market-filters" onSubmit={submitFilters}>
          <label className="search-field"><Search size={16} /><input aria-label="Pesquisar anúncio" placeholder="Pesquisar espécie, item ou jogador" value={filters.q} onChange={(event) => setFilters((current) => ({ ...current, q: event.target.value }))} /></label>
          <Field label="Tipo"><select value={filters.type} onChange={(event) => setFilters((current) => ({ ...current, type: event.target.value }))}><option value="all">Todos</option><option value="pokemon">Pokémon</option><option value="item">Itens</option></select></Field>
          <Field label="Estado"><select value={filters.status} onChange={(event) => setFilters((current) => ({ ...current, status: event.target.value }))}><option value="active">Ativos</option><option value="all">Todos</option><option value="sold">Vendidos</option><option value="removed">Removidos</option><option value="expired">Expirados</option><option value="cancelled">Cancelados</option></select></Field>
          <button className="button button-accent button-small" type="submit" disabled={loading}><Search size={15} />Filtrar</button>
        </form>
        <div className="market-listings">{listings.map((listing) => <ListingCard key={listing.id} listing={listing} canEdit={canEdit} busy={busyId === listing.id} onRemove={openRemoveDialog} />)}</div>
        {!loading && !listings.length && <p className="empty-state">Nenhum anúncio encontrado.</p>}
        {loading && <p className="market-loading">Carregando anúncios…</p>}
        {cursor && <div className="market-more"><button className="button button-quiet button-small" type="button" disabled={loading} onClick={() => void loadListings({ append: true, nextCursor: cursor })}>Carregar mais</button></div>}
      </Section>
    </>}

    {tab === 'transactions' && <>
      <Section title="Operações pendentes" description="Custódias, pagamentos e entregas que aguardam confirmação ou reconciliação após uma interrupção.">
        <PendingOperationsTable rows={pendingOperations} />
        {pendingCursor && <div className="market-more"><button className="button button-quiet button-small" type="button" disabled={loading} onClick={() => void loadTransactions({ appendPending: true, nextPendingCursor: pendingCursor })}>Carregar mais operações</button></div>}
      </Section>
      <Section title="Transações concluídas" description="Compras e encerramentos de leilão registrados pelo servidor.">
        <TransactionTable rows={transactions} />
        {loading && <p className="market-loading">Carregando transações…</p>}
        {transactionCursor && <div className="market-more"><button className="button button-quiet button-small" type="button" disabled={loading} onClick={() => void loadTransactions({ append: true, nextCursor: transactionCursor })}>Carregar mais</button></div>}
      </Section>
    </>}

    {tab === 'settings' && settings && <>
      <Section title="Recursos do mercado" description="Ative os tipos de anúncio disponíveis para os jogadores.">
          <Toggle label="Mercado habilitado" checked={settings.enabled} disabled={!canEdit} onChange={(enabled) => setSettings((value) => ({ ...value, enabled }))} description="Fechar bloqueia novas vendas, compras e lances. Consultas, cancelamentos sem lance e retiradas continuam disponíveis." />
        <Toggle label="Venda de Pokémon" checked={settings.pokemonSales} disabled={!canEdit} onChange={(pokemonSales) => setSettings((value) => ({ ...value, pokemonSales }))} />
        <Toggle label="Venda de itens" checked={settings.itemSales} disabled={!canEdit} onChange={(itemSales) => setSettings((value) => ({ ...value, itemSales }))} />
        <Toggle label="Leilões" checked={settings.auctions} disabled={!canEdit} onChange={(auctions) => setSettings((value) => ({ ...value, auctions }))} />
      </Section>
      <Section title="Limites e duração" description="Estes limites são aplicados no servidor ao criar ou renovar anúncios.">
        <div className="form-grid">
          <NumberField label="Máximo de anúncios por jogador" value={settings.maxListingsPerPlayer} min={1} max={50} disabled={!canEdit} onChange={(maxListingsPerPlayer) => setSettings((value) => ({ ...value, maxListingsPerPlayer }))} />
          <NumberField label="Duração de anúncio fixo" value={settings.listingDurationHours} min={1} max={720} suffix="horas" disabled={!canEdit} onChange={(listingDurationHours) => setSettings((value) => ({ ...value, listingDurationHours }))} />
          <NumberField label="Duração mínima de leilão" value={settings.auctionMinDurationMinutes} min={5} max={10080} suffix="minutos" disabled={!canEdit} onChange={(auctionMinDurationMinutes) => setSettings((value) => ({ ...value, auctionMinDurationMinutes }))} />
          <NumberField label="Duração máxima de leilão" value={settings.auctionMaxDurationHours} min={1} max={720} suffix="horas" disabled={!canEdit} onChange={(auctionMaxDurationHours) => setSettings((value) => ({ ...value, auctionMaxDurationHours }))} />
          <NumberField label="Incremento mínimo de lance" value={settings.minBidIncrement} min={1} max={1000000000} disabled={!canEdit} onChange={(minBidIncrement) => setSettings((value) => ({ ...value, minBidIncrement }))} />
        </div>
        <p className="market-limit-note">Limite de segurança global: {settings.maxGlobalListings || 100} registros de anúncio. Anúncios encerrados antigos são removidos quando necessário.</p>
      </Section>
      <Notice><Gavel size={17} />O servidor mantém Pokémon, itens e saldo reservados enquanto uma transação está em andamento. Pendências com saldo divergente ficam bloqueadas para evitar entrega ou crédito sem confirmação; confira os valores antes de intervir.</Notice>
      <div className="section-toolbar market-settings-actions"><span>{dirty ? 'Há alterações ainda não salvas.' : 'Configurações sincronizadas com o servidor.'}</span><button className="button button-accent button-small" type="button" disabled={!canEdit || !dirty || saving} onClick={() => void saveSettings()}>{saving ? 'Salvando…' : 'Salvar configurações'}</button></div>
    </>}
    {removeTarget && <div className="dialog-shade" role="presentation">
      <section className="species-dialog market-remove-dialog" role="dialog" aria-modal="true" aria-labelledby="market-remove-title">
        <header className="dialog-head"><div><h2 id="market-remove-title">Remover anúncio</h2><p>{removeTarget.assetName || removeTarget.speciesName || removeTarget.itemName || 'Anúncio do mercado'}</p></div><button className="icon-button" type="button" aria-label="Fechar" disabled={Boolean(busyId)} onClick={() => setRemoveTarget(null)}><X size={19} /></button></header>
        <Notice tone="warning">O bem será devolvido ao vendedor. Se o leilão tiver lance, o reembolso será registrado para o maior lance atual.</Notice>
        {error && <Notice tone="error">{error}</Notice>}
        <label className="market-remove-reason">Motivo para o histórico<textarea autoFocus maxLength={160} rows={3} value={removeReason} onChange={(event) => setRemoveReason(event.target.value)} /></label>
        <footer className="dialog-foot market-remove-actions"><button className="button button-quiet" type="button" disabled={Boolean(busyId)} onClick={() => setRemoveTarget(null)}>Voltar</button><button className="button button-danger" type="button" disabled={!removeReason.trim() || Boolean(busyId)} onClick={() => void removeListing()}><Trash2 size={15} />{busyId ? 'Removendo…' : 'Confirmar remoção'}</button></footer>
      </section>
    </div>}
  </>;
}
