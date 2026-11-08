import { Copy, KeyRound, RefreshCw, Shield, Trash2, UserPlus } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { request } from '../api.js';
import { Field, Loading, Notice, Section } from '../components/ui.jsx';

const roleLabels = { admin: 'Administrador', viewer: 'Consulta' };

export default function AccessPage({ canEdit, state, onSessionRevoked }) {
  const [credentials, setCredentials] = useState(null);
  const [name, setName] = useState('');
  const [role, setRole] = useState('viewer');
  const [created, setCreated] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const refresh = useCallback(async () => {
    setError('');
    try {
      const result = await request('/api/credentials');
      setCredentials(result.credentials);
    } catch (failure) { setError(failure.message); }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const create = async (event) => {
    event.preventDefault();
    setBusy(true); setError(''); setNotice(''); setCreated(null);
    try {
      const result = await request('/api/credentials', { method: 'POST', body: { name: name.trim(), role } });
      setCreated(result.credential);
      setName('');
      await refresh();
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };

  const revoke = async (credential) => {
    const detail = credential.shared
      ? 'Isso desativa essa credencial compartilhada e encerra suas sessões.'
      : 'Isso revoga a credencial e encerra todas as sessões abertas com ela.';
    if (!window.confirm(`Revogar ${credential.name}? ${detail}`)) return;
    setBusy(true); setError(''); setNotice('');
    try {
      await request(`/api/credentials?name=${encodeURIComponent(credential.name)}`, { method: 'DELETE' });
      if (credential.name === state?.accessActor) {
        onSessionRevoked?.();
        return;
      }
      setNotice(`Acesso de ${credential.name} revogado.`);
      if (created?.name === credential.name) setCreated(null);
      await refresh();
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  };

  const copyToken = async () => {
    try {
      await navigator.clipboard.writeText(created.token);
      setNotice('Token copiado. Guarde-o em local seguro.');
    } catch { setError('Não foi possível copiar automaticamente. Selecione e copie o token exibido.'); }
  };

  const adminCredentialCount = credentials?.filter((item) => item.role === 'admin').length ?? 0;

  return <>
    <div className="page-intro"><h1>Acessos</h1><p>Crie uma credencial por administrador ou pessoa de consulta e identifique quem alterou cada revisão.</p></div>
    {!canEdit && <Notice tone="error">Somente uma credencial administradora pode gerenciar acessos.</Notice>}
    {error && <Notice tone="error">{error}</Notice>}
    {notice && <Notice>{notice}</Notice>}
    {created && <Section title="Credencial criada" description="O token é exibido uma única vez. Copie e entregue somente à pessoa indicada.">
      <div className="credential-secret">
        <div><strong>{created.name}</strong><span>{roleLabels[created.role]}</span></div>
        <code>{created.token}</code>
        <div className="credential-secret-actions">
          <button className="button button-accent button-small" type="button" onClick={copyToken}><Copy size={15} />Copiar token</button>
          <button className="button button-quiet button-small" type="button" onClick={() => setCreated(null)}>Fechar</button>
        </div>
      </div>
    </Section>}
    <Section title="Nova credencial" description="Cada pessoa deve usar seu próprio token. Administradores podem alterar regras e gerenciar acessos; consulta permite visualizar o painel e os PCs.">
      <form className="credential-form" onSubmit={create}>
        <Field label="Identificador" hint="1–24 caracteres: letras sem acento, números, hífen ou sublinhado. Após revogar, esse identificador não pode ser reutilizado.">
          <input type="text" value={name} maxLength={24} pattern="[A-Za-z0-9][A-Za-z0-9_-]{0,23}" autoComplete="off" required disabled={!canEdit || busy}
            placeholder="ex.: kennedhy" onChange={(event) => setName(event.target.value)} />
        </Field>
        <Field label="Permissão">
          <select value={role} disabled={!canEdit || busy} onChange={(event) => setRole(event.target.value)}>
            <option value="viewer">Consulta</option>
            <option value="admin">Administrador</option>
          </select>
        </Field>
        <button className="button button-accent credential-create" type="submit" disabled={!canEdit || busy || !name.trim()}>
          <UserPlus size={16} />Criar credencial
        </button>
      </form>
    </Section>
    <Section title="Credenciais ativas" description="Revogar uma credencial encerra imediatamente as sessões associadas. Os tokens nunca aparecem nesta lista.">
      <div className="section-toolbar">
        <span className="field-hint">{credentials ? `${credentials.length} credenciais` : 'Carregando credenciais'}</span>
        <button className="button button-quiet button-small" type="button" disabled={busy} onClick={refresh}><RefreshCw size={15} />Atualizar</button>
      </div>
      {!credentials && !error && <Loading>Carregando credenciais…</Loading>}
      {credentials?.length === 0 && <p className="empty-state">Nenhuma credencial encontrada.</p>}
      {credentials?.length > 0 && <div className="table-wrap credential-table-wrap"><table>
        <thead><tr><th>Identificador</th><th>Permissão</th><th>Tipo</th><th /></tr></thead>
        <tbody>{credentials.map((credential) => {
          const protectedLastAdmin = credential.role === 'admin' && adminCredentialCount <= 1;
          return <tr key={credential.name}>
            <td><strong>{credential.name}</strong></td>
            <td><span className={`credential-role ${credential.role}`}>
              {credential.role === 'admin' ? <Shield size={14} /> : <KeyRound size={14} />}{roleLabels[credential.role]}
            </span></td>
            <td>{credential.shared ? 'Compartilhada' : 'Individual'}</td>
            <td><button className="button button-danger button-small" type="button" disabled={!canEdit || busy || protectedLastAdmin}
              title={protectedLastAdmin ? 'Crie outro administrador antes de desativar a credencial compartilhada.' : undefined}
              onClick={() => revoke(credential)}><Trash2 size={14} />Revogar</button></td>
          </tr>;
        })}</tbody>
      </table></div>}
    </Section>
  </>;
}
