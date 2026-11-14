import { RefreshCw, Save } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { request } from '../api.js';
import { Loading, Notice, NumberField, Section, Toggle } from '../components/ui.jsx';

function validationMessage(settings) {
  if (!settings) return '';
  const fields = [
    ['minimumAttackLevel', 1, 1000],
    ['minimumUnprovokedLevel', 1, 1000],
    ['aggressiveThreshold', 0, 1000],
    ['neutralThreshold', 0, 1000],
    ['aggressionLevelMultiplier', 0, 10],
    ['minimumAttackDamage', 0, 100],
    ['maximumAttackDamage', 0, 100],
    ['minimumRangedAttackDamage', 0, 100],
    ['maximumRangedAttackDamage', 0, 100],
  ];
  for (const [key, min, max] of fields) {
    const value = Number(settings[key]);
    if (settings[key] === '' || !Number.isFinite(value) || value < min || value > max)
      return 'Revise os números: os níveis devem ficar entre 1 e 1000, os limiares entre 0 e 1000, o multiplicador entre 0 e 10 e o dano entre 0 e 100.';
  }
  if (Number(settings.aggressiveThreshold) <= Number(settings.neutralThreshold))
    return 'O limiar para ataque espontâneo precisa ser maior que o limiar neutro.';
  if (Number(settings.minimumAttackDamage) > Number(settings.maximumAttackDamage))
    return 'O dano mínimo não pode superar o dano máximo.';
  if (Number(settings.minimumRangedAttackDamage) > Number(settings.maximumRangedAttackDamage))
    return 'O dano mínimo à distância não pode superar o dano máximo.';
  return '';
}

export default function WildCombatPage({ canEdit }) {
  const [status, setStatus] = useState(null);
  const [settings, setSettings] = useState(null);
  const [baseline, setBaseline] = useState('');
  const [revision, setRevision] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [restartRequired, setRestartRequired] = useState(false);
  const dirty = Boolean(settings) && JSON.stringify(settings) !== baseline;
  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;
  const invalid = useMemo(() => validationMessage(settings), [settings]);

  const load = useCallback(async (confirmDraft = false) => {
    if (confirmDraft && dirtyRef.current && !window.confirm('Atualizar vai descartar as alterações locais desta página. Deseja continuar?')) return;
    setLoading(true);
    setError('');
    setMessage('');
    try {
      const next = await request('/api/wild-combat');
      setStatus(next);
      setRestartRequired(Boolean(next.restartRequired));
      if (next.available) {
        setSettings(next.settings);
        setBaseline(JSON.stringify(next.settings));
        setRevision(next.revision || '');
      } else {
        setSettings(null);
        setBaseline('');
        setRevision('');
      }
    } catch (failure) {
      setError(failure.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const update = (key, value) => setSettings((current) => ({ ...current, [key]: value }));
  const save = async () => {
    if (!settings || !dirty || invalid || !canEdit || saving) return;
    setSaving(true);
    setError('');
    setMessage('');
    try {
      const result = await request('/api/wild-combat', {
        method: 'PUT',
        body: { settings, revision },
      });
      setSettings(result.settings);
      setBaseline(JSON.stringify(result.settings));
      setRevision(result.revision || '');
      setRestartRequired(Boolean(result.restartRequired));
      setMessage(result.restartRequired
        ? 'Configuração salva. Reinicie o servidor para aplicar as mudanças.'
        : 'A configuração já estava atualizada.');
    } catch (failure) {
      setError(failure.message);
    } finally {
      setSaving(false);
    }
  };

  return <>
    <div className="page-intro">
      <h1>Pokémon selvagens</h1>
      <p>Controle quando Pokémon selvagens atacam o jogador e quanto dano causam fora das batalhas por turnos.</p>
    </div>

    {loading && <div className="connection-banner"><Loading>Lendo configuração do servidor…</Loading></div>}
    {error && <Notice tone="error">{error}</Notice>}
    {!loading && status && !status.available && <Notice tone="warning">{status.message}</Notice>}
    {!canEdit && status?.available && <Notice>Credencial somente leitura. Você pode consultar as regras, mas não alterá-las.</Notice>}

    {settings && status?.available && <>
      <Section title="Comportamento selvagem" description="Ataques diretos acontecem fora do sistema de batalha Pokémon.">
        <Toggle label="Reagir quando provocado"
          description="Pokémon selvagens podem lutar de volta depois de um ataque ou captura frustrada."
          checked={settings.attackWhenProvoked} disabled={!canEdit || saving}
          onChange={(value) => update('attackWhenProvoked', value)} />
        <Toggle label="Atacar sem provocação"
          description="Pokémon suficientemente agressivos podem perseguir jogadores que se aproximem."
          checked={settings.attackUnprovoked} disabled={!canEdit || saving}
          onChange={(value) => update('attackUnprovoked', value)} />
        <Toggle label="Exigir pouca luz para ataques sem provocação"
          description="Quando ativo, os ataques espontâneos ficam restritos a áreas escuras."
          checked={settings.unprovokedOnlyInDark} disabled={!canEdit || saving || !settings.attackUnprovoked}
          onChange={(value) => update('unprovokedOnlyInDark', value)} />
        <Toggle label="Permitir ataques à distância"
          description="Habilita ataques selvagens de longo alcance quando o Fight or Flight oferece esse comportamento."
          checked={settings.wildPokemonRangedAttack} disabled={!canEdit || saving}
          onChange={(value) => update('wildPokemonRangedAttack', value)} />
        <div className="form-grid">
          <NumberField label="Nível mínimo para reagir" hint="Nível exigido para lutar de volta quando provocado."
            value={settings.minimumAttackLevel} min={1} max={1000} step={1} disabled={!canEdit || saving}
            onChange={(value) => update('minimumAttackLevel', value)} />
          <NumberField label="Nível mínimo para atacar sem provocação" hint="Nível exigido para começar um ataque espontâneo."
            value={settings.minimumUnprovokedLevel} min={1} max={1000} step={1} disabled={!canEdit || saving || !settings.attackUnprovoked}
            onChange={(value) => update('minimumUnprovokedLevel', value)} />
        </div>
      </Section>

      <Section title="Limiar de agressividade" description="O mod calcula a agressividade usando nível, atributos e características do Pokémon.">
        <div className="form-grid">
          <NumberField label="Limiar para atacar sem provocação" hint="Diminuir este valor faz mais Pokémon partirem para o ataque."
            value={settings.aggressiveThreshold} min={0} max={1000} step={1} disabled={!canEdit || saving}
            onChange={(value) => update('aggressiveThreshold', value)} />
          <NumberField label="Limiar neutro" hint="Abaixo deste valor, Pokémon provocados tendem a fugir em vez de lutar."
            value={settings.neutralThreshold} min={0} max={1000} step={1} disabled={!canEdit || saving}
            onChange={(value) => update('neutralThreshold', value)} />
          <NumberField label="Multiplicador de agressividade por nível" hint="Aumentar este multiplicador torna Pokémon de nível alto mais agressivos."
            value={settings.aggressionLevelMultiplier} min={0} max={10} step={0.1} disabled={!canEdit || saving}
            onChange={(value) => update('aggressionLevelMultiplier', value)} />
        </div>
      </Section>

      <Section title="Dano dos ataques selvagens" description="Ajusta o dano direto fora das batalhas por turnos, tanto corpo a corpo quanto à distância.">
        <div className="form-grid">
          <NumberField label="Corpo a corpo · dano mínimo" hint="Menor dano direto permitido para uma investida selvagem."
            value={settings.minimumAttackDamage} min={0} max={100} step={0.5} disabled={!canEdit || saving}
            onChange={(value) => update('minimumAttackDamage', value)} />
          <NumberField label="Corpo a corpo · dano máximo" hint="Teto de dano direto das investidas selvagens."
            value={settings.maximumAttackDamage} min={0} max={100} step={0.5} disabled={!canEdit || saving}
            onChange={(value) => update('maximumAttackDamage', value)} />
          <NumberField label="À distância · dano mínimo" hint="Menor dano dos ataques à distância; fica em espera enquanto esses ataques estiverem desativados."
            value={settings.minimumRangedAttackDamage} min={0} max={100} step={0.5} disabled={!canEdit || saving}
            onChange={(value) => update('minimumRangedAttackDamage', value)} />
          <NumberField label="À distância · dano máximo" hint="Teto de dano dos ataques à distância."
            value={settings.maximumRangedAttackDamage} min={0} max={100} step={0.5} disabled={!canEdit || saving}
            onChange={(value) => update('maximumRangedAttackDamage', value)} />
        </div>
      </Section>

      {dirty && invalid && <Notice tone="error">{invalid}</Notice>}
      {restartRequired && <Notice tone="warning">Há alterações salvas aguardando reinício do servidor para entrarem em vigor.</Notice>}
      {message && <Notice>{message}</Notice>}

      <div className="wild-combat-actions">
        <button className="button button-quiet" type="button" disabled={loading || saving} onClick={() => void load(true)}>
          <RefreshCw size={15} /> Atualizar
        </button>
        {canEdit && <button className="button button-accent" type="button" disabled={!dirty || Boolean(invalid) || saving || loading} onClick={() => void save()}>
          <Save size={15} /> {saving ? 'Salvando…' : 'Salvar no servidor'}
        </button>}
      </div>
    </>}
  </>;
}
