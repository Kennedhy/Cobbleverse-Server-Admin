import { RefreshCw, Save } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { request, stable } from '../api.js';
import { Notice, NumberField, Section, Toggle } from '../components/ui.jsx';

const endpoint = '/api/trainer-operations';

function hydrate(value) {
  return JSON.parse(JSON.stringify(value));
}

export default function TrainerOperationsPage({ state }) {
  const [draft, setDraft] = useState(null);
  const [baseline, setBaseline] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const canEdit = state?.accessRole !== 'viewer';
  const dirty = Boolean(draft) && stable(draft) !== baseline;
  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;

  const load = useCallback(async (discardChanges = false) => {
    if (dirtyRef.current && !discardChanges && !window.confirm('Atualizar substituirá as alterações locais desta página. Deseja continuar?')) return;
    setLoading(true);
    setError('');
    try {
      const next = hydrate(await request(endpoint));
      setDraft(next);
      setBaseline(stable(next));
      setMessage('');
    } catch (failure) {
      setError(failure.message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load(true);
  }, [load]);

  const updateSpawning = (patch) => setDraft((current) => ({ ...current, spawning: { ...current.spawning, ...patch } }));
  const updateRewards = (patch) => setDraft((current) => ({ ...current, rewards: { ...current.rewards, ...patch } }));

  const save = async () => {
    if (!draft || !dirty || !canEdit || saving) return;
    setSaving(true);
    setError('');
    setMessage('');
    try {
      const previous = JSON.parse(baseline);
      const spawning = Object.fromEntries(Object.entries(draft.spawning).filter(([key, value]) => !Object.is(value, previous.spawning?.[key])));
      const rewards = Object.fromEntries(['earnCobbleDollarsFromNPC', 'cobbleDollarsIncomeMultiplier']
        .filter((key) => !Object.is(draft.rewards?.[key], previous.rewards?.[key]))
        .map((key) => [key, draft.rewards[key]]));
      const next = hydrate(await request(endpoint, {
        method: 'PUT',
        body: {
          spawning,
          ...(draft.rewards?.available && Object.keys(rewards).length ? { rewards } : {}),
        },
      }));
      setDraft(next);
      setBaseline(stable(next));
      setMessage('Configurações salvas e aplicadas ao vivo pelos mods integrados.');
    } catch (failure) {
      setError(failure.message);
    } finally {
      setSaving(false);
    }
  };

  if (loading && !draft) return <>
    <div className="page-intro"><h1>Treinadores e recompensas</h1><p>Configurações do RCT e do CobbleDollars neste servidor.</p></div>
    <Notice>Carregando as configurações dos mods integrados…</Notice>
  </>;

  if (!draft) return <>
    <div className="page-intro"><h1>Treinadores e recompensas</h1><p>Configurações do RCT e do CobbleDollars neste servidor.</p></div>
    <Notice tone="error">{error || 'Não foi possível carregar as configurações.'}</Notice>
    <div className="section-toolbar"><span>Verifique se os mods RCT e CobbleDollars estão carregados no servidor.</span><button className="button button-quiet button-small" type="button" onClick={() => void load(true)}><RefreshCw size={15} />Tentar novamente</button></div>
  </>;

  const spawning = draft.spawning || {};
  const rewards = draft.rewards || { available: false };

  return <>
    <div className="page-intro"><h1>Treinadores e recompensas</h1><p>Controle a quantidade, a frequência e o alcance dos treinadores que aparecem ao redor dos jogadores.</p></div>

    {error && <Notice tone="error">{error}</Notice>}
    {message && <Notice tone="success">{message}</Notice>}
    {!canEdit && <Notice tone="warning">Acesso de consulta: estas configurações podem ser visualizadas, mas só um administrador pode alterá-las.</Notice>}

    <Section title="Aparição de treinadores" description="O RCT aplica estas regras por jogador e já controla a distribuição conforme o limite de entidades configurado.">
      <div className="form-grid">
        <NumberField label="Chance base de aparição (0 a 1)" value={spawning.globalSpawnChance} min={0} max={1} step={0.01} disabled={!canEdit}
          onChange={(globalSpawnChance) => updateSpawning({ globalSpawnChance })} hint={`Use 0 para desativar aparições naturais. O valor atual equivale a ${(Number(spawning.globalSpawnChance) * 100).toFixed(1)}%.`} />
        <NumberField label="Chance mínima perto do limite (0 a 1)" value={spawning.globalSpawnChanceMinimum} min={0} max={1} step={0.01} disabled={!canEdit}
          onChange={(globalSpawnChanceMinimum) => updateSpawning({ globalSpawnChanceMinimum })} hint="Controla a chance quando o jogador já está perto de atingir o limite local; 0,15 equivale a 15%." />
        <NumberField label="Intervalo inicial" value={spawning.spawnIntervalTicks} min={20} max={2147483646} step={20} suffix="ticks" disabled={!canEdit}
          onChange={(spawnIntervalTicks) => updateSpawning({ spawnIntervalTicks })} hint="Mínimo recomendado de 20 ticks (aproximadamente 1 segundo) para evitar tentativas contínuas." />
        <NumberField label="Intervalo máximo" value={spawning.spawnIntervalTicksMaximum} min={0} max={2147483646} step={20} suffix="ticks" disabled={!canEdit}
          onChange={(spawnIntervalTicksMaximum) => updateSpawning({ spawnIntervalTicksMaximum })} hint="Use 0 para não aumentar o intervalo conforme os treinadores se acumulam." />
      </div>
      <div className="form-grid">
        <NumberField label="Máximo por jogador" value={spawning.maxTrainersPerPlayer} min={0} max={128} disabled={!canEdit}
          onChange={(maxTrainersPerPlayer) => updateSpawning({ maxTrainersPerPlayer })} hint="0 impede aparições naturais para esse jogador. O painel mantém um teto de 128." />
        <NumberField label="Máximo total no servidor" value={spawning.maxTrainersTotal} min={0} max={512} disabled={!canEdit}
          onChange={(maxTrainersTotal) => updateSpawning({ maxTrainersTotal })} hint="0 impede aparições naturais no servidor. O painel mantém um teto de 512." />
        <NumberField label="Distância horizontal mínima" value={spawning.minHorizontalDistanceToPlayers} min={1} max={256} suffix="blocos" disabled={!canEdit}
          onChange={(minHorizontalDistanceToPlayers) => updateSpawning({ minHorizontalDistanceToPlayers })} />
        <NumberField label="Distância horizontal máxima" value={spawning.maxHorizontalDistanceToPlayers} min={1} max={256} suffix="blocos" disabled={!canEdit}
          onChange={(maxHorizontalDistanceToPlayers) => updateSpawning({ maxHorizontalDistanceToPlayers })} />
        <NumberField label="Distância vertical máxima" value={spawning.maxVerticalDistanceToPlayers} min={1} max={256} suffix="blocos" disabled={!canEdit}
          onChange={(maxVerticalDistanceToPlayers) => updateSpawning({ maxVerticalDistanceToPlayers })} />
        <NumberField label="Raio de identidade única" value={spawning.uniqueTrainerRadius} min={0} max={4096} suffix="blocos" disabled={!canEdit}
          onChange={(uniqueTrainerRadius) => updateSpawning({ uniqueTrainerRadius })} hint="0 mantém a verificação global do RCT; acima de 0 limita a busca a este raio." />
        <NumberField label="Diferença máxima de nível para spawn" value={spawning.maxLevelDiff} min={0} max={100} disabled={!canEdit}
          onChange={(maxLevelDiff) => updateSpawning({ maxLevelDiff })} hint="Afeta quais treinadores podem aparecer para o jogador." />
        <NumberField label="Tempo sem ser visto antes de sumir" value={spawning.despawnTicksIfUnseen} min={-1} max={2147483646} step={20} suffix="ticks" disabled={!canEdit}
          onChange={(despawnTicksIfUnseen) => updateSpawning({ despawnTicksIfUnseen })} hint="-1 desativa esta regra de remoção." />
      </div>
      <Toggle label="Exigir cartão de treinador" checked={spawning.spawningRequiresTrainerCard} disabled={!canEdit}
        onChange={(spawningRequiresTrainerCard) => updateSpawning({ spawningRequiresTrainerCard })} description="Somente afeta aparições naturais; spawners continuam funcionando." />
      <Toggle label="Permitir NPC de associação" checked={spawning.spawnTrainerAssociation} disabled={!canEdit}
        onChange={(spawnTrainerAssociation) => updateSpawning({ spawnTrainerAssociation })} description="Permite a aparição do NPC de associação do RCT." />
    </Section>

    <Section title="Dinheiro e recompensas" description="O pack usa CobbleDollars para recompensas de batalhas contra NPCs.">
      {rewards.available ? <>
        <Toggle label="Pagar ao vencer treinadores NPC" checked={rewards.earnCobbleDollarsFromNPC} disabled={!canEdit}
          onChange={(earnCobbleDollarsFromNPC) => updateRewards({ earnCobbleDollarsFromNPC })} description="Ativa ou desativa a renda de batalhas contra NPCs no CobbleDollars." />
        <div className="form-grid">
          <NumberField label="Multiplicador global de renda" value={rewards.cobbleDollarsIncomeMultiplier} min={0} max={100} step={0.05} suffix="×" disabled={!canEdit}
            onChange={(cobbleDollarsIncomeMultiplier) => updateRewards({ cobbleDollarsIncomeMultiplier })} hint="O CobbleDollars aplica este fator à renda de NPCs e Pokémon selvagens; não há ajuste individual por treinador nesta versão do mod." />
          <Toggle label="Renda de Pokémon selvagens" checked={rewards.earnCobbleDollarsFromWildPokemon} disabled
            description="Este painel só informa a configuração atual; ela não é alterada aqui." />
        </div>
        <Notice>O RCT não oferece valores de prêmio configuráveis por treinador. O multiplicador respeita o valor de recompensa calculado pelo CobbleDollars, mas também afeta a renda de Pokémon selvagens.</Notice>
      </> : <Notice tone="warning">{rewards.message || 'CobbleDollars não está instalado. As recompensas em dinheiro não podem ser configuradas.'}</Notice>}
    </Section>

    <div className="section-toolbar trainer-settings-actions">
      <span>{dirty ? 'Há alterações ainda não salvas.' : 'As configurações mostradas correspondem ao servidor.'}</span>
      <button className="button button-quiet button-small" type="button" disabled={loading || saving} onClick={() => void load()}><RefreshCw size={15} />Atualizar</button>
      <button className="button button-accent button-small" type="button" disabled={!canEdit || !dirty || saving} onClick={() => void save()}><Save size={15} />{saving ? 'Salvando…' : 'Salvar e aplicar'}</button>
    </div>
  </>;
}
