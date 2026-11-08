import { AlertCircle, Box, Menu, Server, X } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { copy, newConfig, request, stable } from './api.js';
import { ActionBar } from './components/ActionBar.jsx';
import SpeciesSearch from './components/SpeciesSearch.jsx';
import { Sidebar } from './components/Sidebar.jsx';
import { Loading } from './components/ui.jsx';
import AccessPage from './pages/AccessPage.jsx';
import CapturePage from './pages/CapturePage.jsx';
import DiagnosticsPage from './pages/DiagnosticsPage.jsx';
import ExperiencePage from './pages/ExperiencePage.jsx';
import GenerationPage from './pages/GenerationPage.jsx';
import GymPage from './pages/GymPage.jsx';
import HistoryPage from './pages/HistoryPage.jsx';
import OverviewPage from './pages/OverviewPage.jsx';
import PlayerPcPage from './pages/PlayerPcPage.jsx';
import PresetsPage from './pages/PresetsPage.jsx';
import RulesPage from './pages/RulesPage.jsx';
import WorldPage from './pages/WorldPage.jsx';

const pages = {
  overview: ['Visão geral', OverviewPage],
  access: ['Acessos', AccessPage],
  generations: ['Gerações', GenerationPage],
  gyms: ['Ginásios', GymPage],
  rules: ['Regras', RulesPage],
  experience: ['XP e níveis', ExperiencePage],
  capture: ['Captura', CapturePage],
  pc: ['PC dos jogadores', PlayerPcPage],
  history: ['Histórico', HistoryPage],
  presets: ['Presets e arquivos', PresetsPage],
  world: ['Mundo', WorldPage],
  diagnostics: ['Diagnóstico', DiagnosticsPage],
};

function Login({ onLogin, error, busy }) {
  const [token, setToken] = useState('');
  const submit = (event) => { event.preventDefault(); onLogin(token.trim()); };
  return <main className="login-screen">
    <form className="login-panel" onSubmit={submit}>
      <div className="login-brand"><span className="brand-mark"><Server size={20} /></span><span>Cobbleverse <small>Control</small></span></div>
      <h1>Entrar no servidor</h1>
      <p>Use uma credencial individual criada em <strong>Acessos</strong> ou o token compartilhado inicial do servidor.</p>
      <label className="field"><span className="field-label">Token de acesso</span>
        <input type="password" value={token} autoComplete="current-password" autoFocus onChange={(event) => setToken(event.target.value)} placeholder="Cole o token deste servidor" />
      </label>
      {error && <p className="login-error"><AlertCircle size={15} />{error}</p>}
      <button className="button button-accent login-submit" type="submit" disabled={!token || busy}>{busy ? 'Conectando…' : 'Acessar painel'}</button>
      <details className="login-access-help">
        <summary>Como acessar de outro computador</summary>
        <div>
          <p>Execute <code>/geracoes web</code> no console para ver os caminhos das credenciais compartilhadas iniciais. Credenciais individuais ficam em <code>generation-control/web-users</code>. O comando nunca mostra tokens existentes.</p>
          <div className="login-access-option"><strong>Endereço HTTPS privado</strong><span>Se o administrador do servidor configurou Tailscale Serve ou um proxy HTTPS privado, conecte-se à rede autorizada e abra o endereço configurado em <code>web.properties</code>.</span></div>
          <div className="login-access-option"><strong>Túnel SSH no Windows</strong><span>Baixe <code>open-admin-tunnel.ps1</code> como asset separado quando ele estiver disponível na release. Se não estiver, o script também fica em <code>tools/</code> no código-fonte:</span>
            <pre><code>.\open-admin-tunnel.ps1 -Server &lt;alias-SSH&gt; -RemoteTokenPath '&lt;caminho-mostrado-pelo-comando&gt;' -CopyToken -OpenBrowser</code></pre>
            <span>Do checkout do projeto, execute <code>.\tools\open-admin-tunnel.ps1</code>. O script copia o token e mantém o túnel aberto até fechar a janela.</span>
          </div>
          <p>O painel fica preso a <code>127.0.0.1</code> no servidor e no computador administrador. Não abra a porta <code>8765</code> na internet. O helper requer OpenSSH e um alias SSH configurado ou um host como <code>usuario@servidor</code>. Para host Windows, acrescente <code>-RemoteTokenShell Windows</code>.</p>
          <details className="login-manual-access">
            <summary>Configuração manual do túnel</summary>
            <p>Mantenha este comando aberto e troque o primeiro número da opção <code>-L</code> se a porta local estiver ocupada:</p>
            <pre><code>ssh -N -T -o ExitOnForwardFailure=yes -L 127.0.0.1:18765:127.0.0.1:8765 &lt;alias-SSH&gt;</code></pre>
            <p>Abra <code>http://127.0.0.1:18765</code> no navegador e copie o token do host por SSH/SFTP.</p>
          </details>
        </div>
      </details>
      <p className="login-foot">Use o endereço HTTPS privado ou um túnel SSH. Trate o token como uma senha administrativa.</p>
    </form>
  </main>;
}

function Toast({ toast, onClose }) {
  if (!toast) return null;
  return <div className={`toast toast-${toast.tone || 'info'}`} role="status"><span>{toast.message}</span><button className="icon-button" aria-label="Fechar aviso" onClick={onClose}><X size={16} /></button></div>;
}

export default function App() {
  const [auth, setAuth] = useState('checking');
  const [authBusy, setAuthBusy] = useState(false);
  const [authError, setAuthError] = useState('');
  const [state, setState] = useState(null);
  const [canEdit, setCanEdit] = useState(true);
  const [draft, setDraft] = useState(null);
  const [baseline, setBaseline] = useState('');
  const baselineRef = useRef('');
  const draftRef = useRef(null);
  const [baseRevision, setBaseRevision] = useState(0);
  const [validated, setValidated] = useState(null);
  const [working, setWorking] = useState('');
  const [dockMessage, setDockMessage] = useState('');
  const [toast, setToast] = useState(null);
  const [page, setPage] = useState('overview');
  const [mobileNav, setMobileNav] = useState(false);
  const [speciesOpen, setSpeciesOpen] = useState(false);
  draftRef.current = draft;

  const adopt = useCallback((next, candidate = newConfig(next)) => {
    setState(next);
    setCanEdit(next.accessRole !== 'viewer');
    const snapshot = stable(candidate);
    setDraft(candidate);
    baselineRef.current = snapshot;
    setBaseline(snapshot);
    setBaseRevision(next.editRevision);
    setValidated(null);
    setDockMessage(next.pending
      ? next.pendingGameplaySettingsDiffer
        ? `XP, limites e captura da revisão ${next.pending.revision} ainda não estão aplicados ao vivo.`
        : 'XP, limites e captura já correspondem ao servidor; as demais regras aguardam reinício.'
      : '');
  }, []);

  const loadState = useCallback(async (force = false) => {
    const next = await request('/api/state');
    setState(next);
    if (force || !draftRef.current || !baselineRef.current) {
      adopt(next, newConfig(next));
    } else if (next.editRevision !== baseRevision) {
      setValidated(null);
      setDockMessage('A revisão mudou no servidor. Atualize para continuar com segurança.');
    }
    return next;
  }, [adopt, baseRevision]);

  useEffect(() => {
    let alive = true;
    request('/api/state').then((next) => {
      if (!alive) return;
      setAuth(true);
      adopt(next, newConfig(next));
    }).catch((failure) => {
      if (!alive) return;
      if (failure.status === 401) { setAuth(false); return; }
      setAuthError(failure.message);
      setAuth('offline');
    });
    return () => { alive = false; };
  }, [adopt]);

  const login = async (token) => {
    setAuthBusy(true); setAuthError('');
    try {
      await request('/api/session', { method: 'POST', body: { token } });
      const next = await request('/api/state');
      setAuth(true); adopt(next, newConfig(next));
    } catch (failure) { setAuthError(failure.message); setAuth(false); }
    finally { setAuthBusy(false); }
  };

  const update = (patch) => {
    if (!canEdit) return;
    setDraft((current) => {
      if (!current) return current;
      const next = { ...current, ...patch };
      const generationChanged = Object.hasOwn(patch, 'mode') || Object.hasOwn(patch, 'selectedGeneration') || Object.hasOwn(patch, 'generations');
      if (next.automaticRegions && (generationChanged || patch.automaticRegions === true)) {
        const selected = next.mode === 'single' ? [next.selectedGeneration]
          : next.mode === 'cumulative' ? Array.from({ length: next.selectedGeneration }, (_, index) => index + 1)
            : next.generations;
        next.regions = [...new Set(selected.filter((generation) => generation <= 4).map((generation) => ['kanto', 'johto', 'hoenn', 'sinnoh'][generation - 1]))];
        if (!next.regions.length) next.initialRegion = 'freeroam';
        else if (!next.regions.includes(next.initialRegion)) next.initialRegion = next.regions[0];
      }
      return next;
    });
    setValidated(null);
    setDockMessage('Rascunho alterado. Valide antes de salvar.');
  };

  const dirty = Boolean(draft) && stable(draft) !== baseline;
  const signatureMatches = Boolean(validated && draft) && validated.revision === baseRevision && validated.snapshot === stable(draft);

  const refresh = async () => {
    if (dirty && !window.confirm('Atualizar substituirá seu rascunho local. Deseja continuar?')) return;
    setWorking('refresh');
    try {
      const next = await loadState(true);
      setToast({ tone: 'success', message: `Estado atualizado. Revisão ativa ${next.active.revision}.` });
    } catch (failure) { setToast({ tone: 'error', message: failure.message }); }
    finally { setWorking(''); }
  };

  const waitForJob = async (jobId) => {
    let busyAttempts = 0;
    while (true) {
      await new Promise((resolve) => setTimeout(resolve, 1800 * (busyAttempts + 1)));
      let next;
      try {
        next = await request('/api/job');
      } catch (failure) {
        if (failure.status !== 503 || ++busyAttempts >= 3) throw failure;
        continue;
      }
      busyAttempts = 0;
      if (!next.job || next.job.id !== jobId) throw new Error('A validação não está mais disponível. Atualize o painel.');
      if (next.editRevision !== baseRevision && next.job.status === 'running' && next.job.action !== 'save') throw new Error('A revisão do servidor mudou durante a operação. Atualize o painel.');
      setState((current) => current ? { ...current, job: next.job } : current);
      if (next.job.status !== 'running') return next.job;
    }
  };

  const validate = async () => {
    if (!draft || !dirty) return;
    const numericValues = [
      ['Multiplicador global de XP', draft.globalXpMultiplier, 0, 100],
      ['Limite global de nível', draft.globalLevelCap, 1, 100],
      ['Multiplicador de captura', draft.captureMultiplier, 0, 100],
      ...Object.entries(draft.groupXpMultipliers || {}).map(([name, value]) => [`Grupo ${name}`, value, 0, 100]),
      ...Object.entries(draft.playerXpMultipliers || {}).map(([uuid, value]) => [`XP individual ${uuid}`, value, 0, 100]),
    ];
    const invalid = numericValues.find(([, value, min, max]) => value === '' || value == null || !Number.isFinite(Number(value)) || Number(value) < min || Number(value) > max);
    if (invalid) {
      setDockMessage(`${invalid[0]} precisa estar entre ${invalid[2]} e ${invalid[3]}.`);
      setToast({ tone: 'error', message: `${invalid[0]} precisa estar entre ${invalid[2]} e ${invalid[3]}.` });
      return;
    }
    const submitted = copy(draft);
    const submittedSnapshot = stable(submitted);
    setWorking('validate'); setValidated(null); setDockMessage('Validando o rascunho no servidor…');
    try {
      const response = await request('/api/action', { method: 'POST', body: { action: 'validate', expectedRevision: baseRevision, candidate: submitted } });
      const job = response.job.status === 'running' ? await waitForJob(response.job.id) : response.job;
      if (job.status === 'validated' && job.revision === baseRevision && stable(draftRef.current) === submittedSnapshot) {
        setDraft(job.candidate);
        setValidated({ fingerprint: job.fingerprint, revision: job.revision, snapshot: stable(job.candidate) });
        setDockMessage('Validação concluída sem erros. Salve para criar uma revisão pendente.');
        setToast({ tone: 'success', message: 'Rascunho validado. Você pode salvar a revisão.' });
      } else if (job.status === 'validated') {
        setDockMessage('O rascunho mudou durante a validação. Valide novamente.');
        setToast({ tone: 'warning', message: 'A validação corresponde a outra versão do rascunho.' });
      } else {
        setDockMessage(job.message || 'A validação encontrou problemas. Consulte o Diagnóstico.');
        setToast({ tone: 'error', message: job.message || 'A validação encontrou problemas.' });
        setPage('diagnostics');
      }
    } catch (failure) {
      setDockMessage(failure.message); setToast({ tone: 'error', message: failure.message });
      if (failure.status === 401) { setAuth(false); setAuthError('Sua sessão expirou. Entre novamente.'); }
    } finally { setWorking(''); }
  };

  const save = async () => {
    if (!signatureMatches) return;
    setWorking('save');
    try {
      const result = await request('/api/action', { method: 'POST', body: {
        action: 'save', expectedRevision: baseRevision, candidate: copy(draft), fingerprint: validated.fingerprint,
      } });
      const job = result.job.status === 'running' ? await waitForJob(result.job.id) : result.job;
      if (job.status !== 'saved' || !job.state) throw new Error(job.message || 'O salvamento não foi confirmado. Atualize o painel para conferir o estado.');
      adopt(job.state, newConfig(job.state));
      setToast({ tone: 'success', message: job.message });
      setPage('diagnostics');
    } catch (failure) {
      setValidated(null); setDockMessage(failure.message); setToast({ tone: 'error', message: failure.message });
      if (failure.status === 401) { setAuth(false); setAuthError('Sua sessão expirou. Entre novamente.'); }
    } finally { setWorking(''); }
  };

  const applyLiveSettings = async () => {
    if (dirty || !state?.pending || !state.pendingGameplaySettingsDiffer) return;
    const revision = state.pending.revision;
    if (!window.confirm(`Aplicar agora XP, limites de nível e captura da revisão ${revision}? Gerações, ginásios e regras do mundo continuarão aguardando o reinício.`)) return;
    setWorking('applyLiveSettings');
    try {
      const next = await request('/api/action', { method: 'POST', body: {
        action: 'applyLiveSettings', expectedRevision: baseRevision,
      } });
      setState(next);
      setDockMessage(`XP, limites e captura da revisão ${revision} foram aplicados ao vivo. As demais regras continuam pendentes até reiniciar.`);
      setToast({ tone: 'success', message: `XP, limites e captura da revisão ${revision} estão ativos.` });
    } catch (failure) {
      setDockMessage(failure.message); setToast({ tone: 'error', message: failure.message });
      if (failure.status === 401) { setAuth(false); setAuthError('Sua sessão expirou. Entre novamente.'); }
    } finally { setWorking(''); }
  };

  const cancelValidation = async () => {
    setWorking('cancelValidation');
    try {
      const result = await request('/api/action', { method: 'POST', body: {
        action: 'cancelValidation', expectedRevision: baseRevision,
      } });
      setState((current) => current ? { ...current, job: result.job } : current);
      setToast({ tone: 'success', message: result.job.message });
    } catch (failure) {
      setToast({ tone: 'error', message: failure.message });
      if (failure.status === 401) { setAuth(false); setAuthError('Sua sessão expirou. Entre novamente.'); }
    } finally { setWorking(''); }
  };

  const recoveryAction = async (action, targetRevision) => {
    const prompt = action === 'restore'
      ? 'Preparar a revisão anterior para o próximo reinício?'
      : action === 'restoreRevision'
        ? `Preparar a revisão ${targetRevision} para o próximo reinício${pending ? '? Isso substituirá a configuração pendente atual.' : '?'} `
        : 'Descartar a configuração pendente do próximo reinício?';
    if (!window.confirm(prompt)) return;
    setWorking(action);
    try {
      const result = await request('/api/action', { method: 'POST', body: { action, expectedRevision: baseRevision, targetRevision } });
      const job = result.job.status === 'running' ? await waitForJob(result.job.id) : result.job;
      if (job.status !== 'completed' || !job.state) throw new Error(job.message || 'A operação não foi confirmada. Atualize o painel para conferir o estado.');
      adopt(job.state, newConfig(job.state));
      setToast({ tone: 'success', message: job.message });
    } catch (failure) { setToast({ tone: 'error', message: failure.message }); }
    finally { setWorking(''); }
  };

  const logout = async () => {
    try { await request('/api/session', { method: 'DELETE' }); } catch { /* The session is removed when the server stops. */ }
    setState(null); setDraft(null); setAuth(false); setValidated(null);
  };

  const currentCredentialRevoked = () => {
    setState(null); setDraft(null); setValidated(null);
    setAuthError('Sua credencial foi revogada. Entre novamente com outra conta.');
    setAuth(false);
  };

  const Page = pages[page]?.[1] || GenerationPage;
  const pageTitle = pages[page]?.[0] || 'Gerações';
  const pending = Boolean(state?.pending);

  if (auth === 'checking') return <main className="loading-screen"><Loading>Conectando ao servidor…</Loading></main>;
  if (auth !== true) return <Login onLogin={login} error={authError} busy={authBusy} />;
  if (!state || !draft) return <main className="loading-screen"><Loading>Carregando configurações…</Loading></main>;

  return <div className={`app-shell${canEdit ? '' : ' app-read-only'}`}>
    <header className="topbar">
      <div className="top-brand"><span className="brand-mark"><Box size={22} strokeWidth={1.7} /></span><span>Cobbleverse Control</span></div>
      <button className="icon-button mobile-menu" aria-label="Abrir navegação" onClick={() => setMobileNav(true)}><Menu size={19} /></button>
      <div className="topbar-context"><span className="server-led" /><strong>Servidor ativo</strong><span className="topbar-separator" />
        <span>Mundo {state.world}</span><span className="topbar-separator" /><span>Revisão ativa {state.active.revision}</span>
        {pending && <span className="pending-pill">Pendente {state.pending.revision}</span>}
        <span className="account-pill">Acesso: {state.accessActor}</span>
        {!canEdit && <span className="role-pill">Somente consulta</span>}
      </div>
      <button className="text-button logout-button" onClick={logout}>Sair</button>
    </header>
    <Sidebar page={page} onPage={setPage} open={mobileNav} onClose={() => setMobileNav(false)} canEdit={canEdit} />
    <div className="main-column">
      <main className="workspace" inert={!canEdit && !['overview', 'pc', 'history', 'presets', 'diagnostics', 'experience'].includes(page)}>
        {working === 'refresh' && <div className="connection-banner"><Loading>Atualizando estado do servidor…</Loading></div>}
        {dirty && <div className="workspace-head"><span className="draft-marker">Rascunho não salvo · {pageTitle}</span></div>}
        <Page draft={draft} update={update} state={state}
          canEdit={canEdit}
          onSessionRevoked={currentCredentialRevoked}
          onSearch={() => setSpeciesOpen(true)} onPage={setPage}
          onRestore={() => recoveryAction('restore')} onDiscard={() => recoveryAction('discard')}
          onRestoreRevision={(revision) => recoveryAction('restoreRevision', revision)}
          onCancelValidation={cancelValidation} />
        {canEdit && <ActionBar onRefresh={refresh} onValidate={validate} onSave={save} dirty={dirty}
          onApplyLive={applyLiveSettings} applyLiveAvailable={state.pendingGameplaySettingsDiffer}
          liveGameplayRevision={state.liveGameplayRevision}
          validated={signatureMatches} working={Boolean(working)} pending={pending} message={dockMessage} />}
      </main>
    </div>
    {speciesOpen && <SpeciesSearch draft={draft} update={update} onClose={() => setSpeciesOpen(false)} />}
    <Toast toast={toast} onClose={() => setToast(null)} />
  </div>;
}
