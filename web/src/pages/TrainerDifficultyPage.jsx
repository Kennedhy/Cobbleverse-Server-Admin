import { useEffect, useMemo, useState } from 'react';
import { Plus, RefreshCw, Save, Search, Trash2, Swords } from 'lucide-react';
import { request, stable } from '../api.js';
import { Field, Notice, NumberField, Section, Toggle } from '../components/ui.jsx';
import '../trainer-difficulty.css';

const cleanConfig = (value) => ({
  schemaVersion: 1,
  revision: value?.revision ?? 1,
  enabled: Boolean(value?.enabled),
  defaultProfile: { levelMultiplier: 1, levelOffset: 0, minimumIv: 0, evPerStat: 0, ...value?.defaultProfile },
  trainerGroups: Array.isArray(value?.trainerGroups) ? value.trainerGroups : [],
  playerGroups: Array.isArray(value?.playerGroups) ? value.playerGroups : [],
  profiles: Array.isArray(value?.profiles) ? value.profiles : [],
});

function groupId(name, fallback) {
  const value = name.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
  return value || fallback;
}

function ProfileFields({ value, onChange, disabled = false, prefix }) {
  const profile = { levelMultiplier: 1, levelOffset: 0, minimumIv: 0, evPerStat: 0, ...value };
  return <div className="trainer-difficulty-fields">
    <NumberField label="Multiplicador de nível" value={profile.levelMultiplier} min={0.25} max={3} step={0.05} suffix="×" disabled={disabled}
      onChange={(levelMultiplier) => onChange({ ...profile, levelMultiplier })} />
    <NumberField label="Ajuste de nível" value={profile.levelOffset} min={-100} max={100} step={1} suffix="níveis" disabled={disabled}
      onChange={(levelOffset) => onChange({ ...profile, levelOffset })} />
    <NumberField label="IV mínimo" value={profile.minimumIv} min={0} max={31} step={1} disabled={disabled}
      onChange={(minimumIv) => onChange({ ...profile, minimumIv })} />
    <NumberField label="EV mínimo por atributo" value={profile.evPerStat} min={0} max={85} step={1} disabled={disabled}
      onChange={(evPerStat) => onChange({ ...profile, evPerStat })} />
    {prefix && <p className="field-hint trainer-profile-hint">Os valores só aumentam atributos do clone usado na batalha; EVs respeitam o teto total de 510.</p>}
  </div>;
}

function toggleList(values, value) {
  const current = new Set(values || []);
  if (current.has(value)) current.delete(value); else current.add(value);
  return [...current].sort();
}

export default function TrainerDifficultyPage({ canEdit, state }) {
  const [config, setConfig] = useState(null);
  const [catalog, setCatalog] = useState({ trainerTypes: {}, trainers: [], ready: false });
  const [baseline, setBaseline] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [newTrainerName, setNewTrainerName] = useState('');
  const [newPlayerName, setNewPlayerName] = useState('');
  const [trainerSearch, setTrainerSearch] = useState({});
  const [playerUuid, setPlayerUuid] = useState({});
  const [memberSearch, setMemberSearch] = useState({});
  const players = state?.knownPlayers || state?.onlinePlayers || [];
  const dirty = Boolean(config) && stable(config) !== baseline;

  const load = async () => {
    setLoading(true); setError(''); setMessage('');
    try {
      const response = await request('/api/trainer-difficulty');
      const next = cleanConfig(response.config);
      setConfig(next); setBaseline(stable(next));
      setCatalog({ trainerTypes: response.trainerTypes || {}, trainers: response.trainers || [], ready: response.catalogReady === true });
    } catch (failure) { setError(failure.message); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const update = (patch) => {
    setConfig((current) => ({ ...current, ...patch }));
    setError(''); setMessage('');
  };

  const save = async () => {
    if (!canEdit || !config || saving) return;
    setSaving(true); setError(''); setMessage('');
    try {
      const response = await request('/api/trainer-difficulty', { method: 'PUT', body: config });
      const next = cleanConfig(response.config);
      setConfig(next); setBaseline(stable(next));
      setCatalog({ trainerTypes: response.trainerTypes || {}, trainers: response.trainers || [], ready: response.catalogReady === true });
      setMessage('Dificuldade salva. A regra já vale para as próximas batalhas.');
    } catch (failure) { setError(failure.message); }
    finally { setSaving(false); }
  };

  const types = useMemo(() => Object.entries(catalog.trainerTypes || {})
    .map(([id, count]) => ({ id, count })).sort((a, b) => a.id.localeCompare(b.id)), [catalog.trainerTypes]);
  const trainerGroups = config?.trainerGroups || [];
  const playerGroups = config?.playerGroups || [];
  const trainerOptions = [{ value: '*', label: 'Qualquer grupo de treinadores' }, ...trainerGroups.map((group) => ({ value: group.id, label: group.name }))];
  const playerOptions = [{ value: '*', label: 'Qualquer grupo de jogadores' }, ...playerGroups.map((group) => ({ value: group.id, label: group.name }))];

  const addTrainerGroup = (event) => {
    event.preventDefault();
    const name = newTrainerName.trim(); if (!name) return;
    const id = groupId(name, `treinador-${trainerGroups.length + 1}`);
    if (trainerGroups.some((group) => group.id === id)) { setError('Já existe um grupo de treinadores com esse nome.'); return; }
    update({ trainerGroups: [...trainerGroups, { id, name, trainerTypes: [], trainerIds: [] }] });
    setNewTrainerName('');
  };

  const addPlayerGroup = (event) => {
    event.preventDefault();
    const name = newPlayerName.trim(); if (!name) return;
    const id = groupId(name, `jogadores-${playerGroups.length + 1}`);
    if (playerGroups.some((group) => group.id === id)) { setError('Já existe um grupo de jogadores com esse nome.'); return; }
    update({ playerGroups: [...playerGroups, { id, name, playerUuids: [] }] });
    setNewPlayerName('');
  };

  const addProfile = () => {
    const pairExists = (trainerGroup, playerGroup) => config.profiles.some((profile) => profile.trainerGroup === trainerGroup && profile.playerGroup === playerGroup);
    const pairs = [];
    for (const trainer of ['*', ...trainerGroups.map((group) => group.id)]) {
      for (const player of ['*', ...playerGroups.map((group) => group.id)]) {
        if (trainer === '*' && player === '*') continue;
        pairs.push([trainer, player]);
      }
    }
    const pair = pairs.find(([trainer, player]) => !pairExists(trainer, player));
    if (!pair) { setError('Não há outro par de grupos disponível para adicionar.'); return; }
    update({ profiles: [...config.profiles, { trainerGroup: pair[0], playerGroup: pair[1], profile: { levelMultiplier: 1, levelOffset: 0, minimumIv: 0, evPerStat: 0 } }] });
  };

  const setTrainerGroup = (id, patch) => update({ trainerGroups: trainerGroups.map((group) => group.id === id ? { ...group, ...patch } : group) });
  const setPlayerGroup = (id, patch) => update({ playerGroups: playerGroups.map((group) => group.id === id ? { ...group, ...patch } : group) });
  const changeProfileGroups = (index, patch) => {
    const next = [...config.profiles];
    next[index] = { ...next[index], ...patch };
    if (next[index].trainerGroup === '*' && next[index].playerGroup === '*') {
      setError('A combinação geral é configurada no perfil padrão.'); return;
    }
    if (next.some((profile, itemIndex) => itemIndex !== index && profile.trainerGroup === next[index].trainerGroup && profile.playerGroup === next[index].playerGroup)) {
      setError('Já existe um perfil para esse par de grupos.'); return;
    }
    update({ profiles: next });
  };

  const assignTrainer = (group, id) => {
    const normalized = id.trim().toLowerCase();
    if (!normalized) return;
    if (trainerGroups.some((other) => other.id !== group.id && other.trainerIds?.includes(normalized))) {
      setError('Esse treinador específico já está em outro grupo.'); return;
    }
    setTrainerGroup(group.id, { trainerIds: [...new Set([...(group.trainerIds || []), normalized])].sort() });
    setTrainerSearch({ ...trainerSearch, [group.id]: '' });
  };

  const assignPlayer = (group, rawUuid) => {
    let uuid;
    try { uuid = rawUuid.trim().toLowerCase(); if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(uuid)) return; }
    catch { return; }
    if (playerGroups.some((other) => other.id !== group.id && other.playerUuids?.includes(uuid))) {
      setError('Esse UUID já está em outro grupo de jogadores.'); return;
    }
    setPlayerGroup(group.id, { playerUuids: [...new Set([...(group.playerUuids || []), uuid])].sort() });
    setPlayerUuid({ ...playerUuid, [group.id]: '' });
  };

  const playerMatches = (group) => {
    const query = (memberSearch[group.id] || '').trim().toLowerCase();
    if (!query) return [];
    const assigned = new Set(playerGroups.flatMap((item) => item.playerUuids || []));
    return players.filter((player) => !assigned.has(player.uuid) &&
      (`${player.name || ''} ${player.uuid || ''}`).toLowerCase().includes(query)).slice(0, 6);
  };

  const trainerMatches = (group) => {
    const query = (trainerSearch[group.id] || '').trim().toLowerCase();
    if (!query) return [];
    const assigned = new Set(trainerGroups.flatMap((item) => item.trainerIds || []));
    return catalog.trainers.filter((trainer) => !assigned.has(trainer.id) && trainer.id.toLowerCase().includes(query)).slice(0, 6);
  };

  if (loading) return <>
    <div className="page-intro"><h1>Dificuldade de treinadores</h1><p>Perfis por grupos de treinadores e jogadores.</p></div>
    <div className="section"><p>Carregando as regras e categorias RCT…</p></div>
  </>;

  return <>
    <div className="page-intro trainer-difficulty-intro">
      <div><h1>Dificuldade de treinadores</h1><p>Monte grupos dos dois lados e ajuste as batalhas de treinadores do RCT.</p></div>
      <div className="trainer-difficulty-actions">
        {!catalog.ready && <button type="button" className="button button-quiet" disabled={loading || dirty}
          onClick={() => void load()}><RefreshCw size={15} />Atualizar catálogo</button>}
        <button type="button" className="button button-accent" disabled={!canEdit || !dirty || saving || !config}
          onClick={save}><Save size={16} />{saving ? 'Salvando…' : 'Salvar dificuldade'}</button>
      </div>
    </div>
    {error && <Notice tone="error">{error}</Notice>}
    {message && <Notice tone="success">{message}</Notice>}
    {!canEdit && <Notice>Esta credencial pode consultar as regras, mas não alterá-las.</Notice>}
    <div className="trainer-difficulty-content">
      <div className="management-toggle"><Toggle label="Aplicar dificuldade em batalhas de treinadores" description="Vale apenas para batalhas contra treinadores RCT. Selvagens e PvP não são alterados." checked={config.enabled} disabled={!canEdit}
        onChange={(enabled) => update({ enabled })} /></div>

      <Section title="Perfil padrão" description="Usado quando nenhuma regra de grupo se aplica. Os valores padrão mantêm a dificuldade do modpack.">
        <ProfileFields value={config.defaultProfile} disabled={!canEdit} prefix
          onChange={(defaultProfile) => update({ defaultProfile })} />
      </Section>

      <Section title="Grupos de treinadores" description="Agrupe pelas categorias do RCT ou escolha treinadores específicos. Treinador específico tem prioridade sobre a categoria.">
        <form className="trainer-add-group" onSubmit={addTrainerGroup}>
          <Field label="Nome do grupo"><input value={newTrainerName} maxLength={48} disabled={!canEdit} onChange={(event) => setNewTrainerName(event.target.value)} placeholder="Ex.: Líderes de ginásio" /></Field>
          <button className="button button-quiet" type="submit" disabled={!canEdit || !newTrainerName.trim()}><Plus size={15} />Criar grupo</button>
        </form>
        {!types.length && <Notice tone="warning">O catálogo RCT ainda não carregou. Os grupos existentes continuam editáveis.</Notice>}
        <div className="trainer-group-list">
          {trainerGroups.map((group) => <section className="trainer-group-card" key={group.id}>
            <div className="trainer-group-head"><Field label="Nome"><input value={group.name} maxLength={48} disabled={!canEdit} onChange={(event) => setTrainerGroup(group.id, { name: event.target.value })} /></Field>
              <button type="button" className="icon-button danger-hover" aria-label={`Remover grupo ${group.name}`} disabled={!canEdit}
                onClick={() => update({ trainerGroups: trainerGroups.filter((item) => item.id !== group.id), profiles: config.profiles.filter((item) => item.trainerGroup !== group.id) })}><Trash2 size={16} /></button></div>
            <Field label="Categorias RCT" hint="Treinadores dessas categorias entram neste grupo.">
              {types.length ? <div className="trainer-type-list">{types.map(({ id, count }) => <label className="trainer-type-option" key={id}>
                <input type="checkbox" checked={(group.trainerTypes || []).includes(id)} disabled={!canEdit}
                  onChange={() => {
                    const next = toggleList(group.trainerTypes, id);
                    if (trainerGroups.some((other) => other.id !== group.id && next.some((type) => other.trainerTypes?.includes(type)))) { setError('Cada categoria RCT só pode pertencer a um grupo.'); return; }
                    setTrainerGroup(group.id, { trainerTypes: next });
                  }} />
                <span>{id}</span><small>{count}</small>
              </label>)}</div> : <span className="field-hint">Categorias RCT serão listadas quando o servidor carregar os dados.</span>}
            </Field>
            <Field label="Treinadores específicos" hint="Digite parte do ID para pesquisar ou informe o ID completo do datapack.">
              <label className="search-field"><Search size={15} /><input value={trainerSearch[group.id] || ''} disabled={!canEdit}
                onChange={(event) => setTrainerSearch({ ...trainerSearch, [group.id]: event.target.value })} placeholder="Ex.: rctmod:brock" /></label>
              {trainerMatches(group).length > 0 && <div className="trainer-suggestions">{trainerMatches(group).map((trainer) => <button type="button" key={trainer.id} disabled={!canEdit}
                onClick={() => assignTrainer(group, trainer.id)}><span>{trainer.id}</span><small>{trainer.type}</small></button>)}</div>}
              {trainerSearch[group.id]?.trim() && !trainerMatches(group).length && <button type="button" className="button button-small button-quiet" disabled={!canEdit}
                onClick={() => assignTrainer(group, trainerSearch[group.id])}>Adicionar ID “{trainerSearch[group.id]}”</button>}
            </Field>
            <div className="trainer-chip-list">{(group.trainerIds || []).map((id) => <span className="trainer-chip" key={id}>{id}<button type="button" disabled={!canEdit} aria-label={`Remover ${id}`}
              onClick={() => setTrainerGroup(group.id, { trainerIds: group.trainerIds.filter((value) => value !== id) })}>×</button></span>)}</div>
          </section>)}
          {!trainerGroups.length && <p className="trainer-empty">Nenhum grupo de treinadores criado.</p>}
        </div>
      </Section>

      <Section title="Grupos de jogadores" description="Atribua cada UUID a um grupo. Os grupos são próprios do painel e não dependem de permissões de outro mod.">
        <form className="trainer-add-group" onSubmit={addPlayerGroup}>
          <Field label="Nome do grupo"><input value={newPlayerName} maxLength={48} disabled={!canEdit} onChange={(event) => setNewPlayerName(event.target.value)} placeholder="Ex.: Iniciantes" /></Field>
          <button className="button button-quiet" type="submit" disabled={!canEdit || !newPlayerName.trim()}><Plus size={15} />Criar grupo</button>
        </form>
        <div className="trainer-group-list">
          {playerGroups.map((group) => <section className="trainer-group-card" key={group.id}>
            <div className="trainer-group-head"><Field label="Nome"><input value={group.name} maxLength={48} disabled={!canEdit} onChange={(event) => setPlayerGroup(group.id, { name: event.target.value })} /></Field>
              <button type="button" className="icon-button danger-hover" aria-label={`Remover grupo ${group.name}`} disabled={!canEdit}
                onClick={() => update({ playerGroups: playerGroups.filter((item) => item.id !== group.id), profiles: config.profiles.filter((item) => item.playerGroup !== group.id) })}><Trash2 size={16} /></button></div>
            {players.length > 0 && <Field label="Buscar jogador conhecido"><label className="search-field"><Search size={15} /><input value={memberSearch[group.id] || ''} disabled={!canEdit}
              onChange={(event) => setMemberSearch({ ...memberSearch, [group.id]: event.target.value })} placeholder="Nome ou UUID" /></label>
              {playerMatches(group).length > 0 && <div className="trainer-suggestions">{playerMatches(group).map((player) => <button type="button" key={player.uuid} disabled={!canEdit}
                onClick={() => assignPlayer(group, player.uuid)}><span>{player.name || player.uuid}</span><small>{player.uuid}</small></button>)}</div>}
            </Field>}
            <form className="trainer-add-group trainer-add-member" onSubmit={(event) => { event.preventDefault(); assignPlayer(group, playerUuid[group.id] || ''); }}>
              <Field label="Adicionar UUID"><input value={playerUuid[group.id] || ''} disabled={!canEdit} onChange={(event) => setPlayerUuid({ ...playerUuid, [group.id]: event.target.value })} placeholder="00000000-0000-0000-0000-000000000000" /></Field>
              <button className="button button-quiet" type="submit" disabled={!canEdit || !(playerUuid[group.id] || '').trim()}><Plus size={15} />Adicionar</button>
            </form>
            <div className="trainer-chip-list">{(group.playerUuids || []).map((uuid) => {
              const name = players.find((player) => player.uuid === uuid)?.name;
              return <span className="trainer-chip" key={uuid}>{name ? `${name} · ` : ''}{uuid}<button type="button" disabled={!canEdit} aria-label={`Remover ${uuid}`}
                onClick={() => setPlayerGroup(group.id, { playerUuids: group.playerUuids.filter((value) => value !== uuid) })}>×</button></span>;
            })}</div>
          </section>)}
          {!playerGroups.length && <p className="trainer-empty">Nenhum grupo de jogadores criado.</p>}
        </div>
      </Section>

      <Section title="Perfis por par de grupos" description="Defina uma combinação específica ou uma regra para apenas um lado. O par exato vence; depois vale treinador, jogador e perfil padrão. Em cooperativo, o perfil mais difícil entre os jogadores se aplica.">
        <div className="trainer-profile-toolbar"><span>{config.profiles.length} regra(s)</span><button type="button" className="button button-quiet" disabled={!canEdit || config.profiles.length >= 256} onClick={addProfile}><Plus size={15} />Adicionar perfil</button></div>
        <div className="trainer-profile-list">
          {config.profiles.map((rule, index) => <section className="trainer-profile-card" key={`${rule.trainerGroup}-${rule.playerGroup}-${index}`}>
            <div className="trainer-profile-head">
              <Field label="Grupo de treinadores"><select value={rule.trainerGroup} disabled={!canEdit} onChange={(event) => {
                changeProfileGroups(index, { trainerGroup: event.target.value });
              }}>{trainerOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}</select></Field>
              <Swords size={18} aria-hidden="true" />
              <Field label="Grupo de jogadores"><select value={rule.playerGroup} disabled={!canEdit} onChange={(event) => {
                changeProfileGroups(index, { playerGroup: event.target.value });
              }}>{playerOptions.map((option) => <option value={option.value} key={option.value}>{option.label}</option>)}</select></Field>
              <button type="button" className="icon-button danger-hover" aria-label="Remover perfil" disabled={!canEdit}
                onClick={() => update({ profiles: config.profiles.filter((_, itemIndex) => itemIndex !== index) })}><Trash2 size={16} /></button>
            </div>
            <ProfileFields value={rule.profile} disabled={!canEdit} onChange={(profile) => {
              const next = [...config.profiles]; next[index] = { ...rule, profile }; update({ profiles: next });
            }} />
          </section>)}
          {!config.profiles.length && <p className="trainer-empty">Sem perfis específicos. O painel usa o perfil padrão.</p>}
        </div>
      </Section>

      <Notice>O nível usa o nível configurado no datapack, multiplicador e ajuste, limitado entre 1 e 100. IV e EV mínimos só são aplicados à cópia de batalha do treinador; as equipes originais do RCT permanecem intactas. Mudanças salvas entram nas próximas batalhas sem reiniciar.</Notice>
    </div>
  </>;
}
