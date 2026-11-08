import { BookmarkPlus, Download, FileJson2, FolderOpen, Trash2, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { request, stable } from '../api.js';
import { Notice, Section } from '../components/ui.jsx';

const labels = {
  mode: 'Modo', selectedGeneration: 'Geração principal', generations: 'Gerações permitidas',
  automaticRegions: 'Regiões automáticas', regions: 'Regiões', initialRegion: 'Região inicial',
  regionalForms: 'Formas regionais', megaEvolutions: 'Megaevoluções', filterGyms: 'Filtro de ginásios',
  filterTrainerTeams: 'Filtro de equipes', manageExperience: 'Gerenciar XP', globalXpMultiplier: 'Multiplicador global de XP',
  groupXpMultipliers: 'Multiplicadores por grupo', playerXpMultipliers: 'Multiplicadores individuais',
  playerXpGroups: 'Grupos de jogadores', levelCapMode: 'Origem do limite', globalLevelCap: 'Limite global',
  wildLevelBehavior: 'Nível de Pokémon selvagem', captureMultiplier: 'Multiplicador de captura',
  restrictExistingPokemon: 'Restringir Pokémon existentes', rescueStarter: 'Resgate de inicial',
  allowSpecies: 'Espécies liberadas', denySpecies: 'Espécies bloqueadas', allowForms: 'Formas liberadas', denyForms: 'Formas bloqueadas',
};

function describe(value) {
  if (Array.isArray(value)) return value.length ? value.join(', ') : 'Nenhum';
  if (value && typeof value === 'object') return Object.keys(value).length ? JSON.stringify(value) : 'Nenhum';
  if (typeof value === 'boolean') return value ? 'Sim' : 'Não';
  return String(value ?? '');
}

function createPreset(draft, id) {
  const preset = { ...draft };
  if (id === 'kanto') {
    Object.assign(preset, { mode: 'single', selectedGeneration: 1, generations: [1], automaticRegions: true,
      regions: ['kanto'], initialRegion: 'kanto', regionalForms: false, megaEvolutions: false });
  } else if (id === 'johto') {
    Object.assign(preset, { mode: 'cumulative', selectedGeneration: 2, generations: [1, 2], automaticRegions: true,
      regions: ['kanto', 'johto'], initialRegion: 'kanto', regionalForms: false, megaEvolutions: false });
  } else {
    Object.assign(preset, { mode: 'custom', selectedGeneration: 9, generations: [1, 2, 3, 4, 5, 6, 7, 8, 9], automaticRegions: true,
      regions: ['kanto', 'johto', 'hoenn', 'sinnoh'], initialRegion: 'kanto', regionalForms: false, megaEvolutions: false });
  }
  return preset;
}

function presetId(name) {
  return name.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

export default function PresetsPage({ draft, update, canEdit }) {
  const [preview, setPreview] = useState(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [saved, setSaved] = useState([]);
  const [presetName, setPresetName] = useState('');
  const [presetBusy, setPresetBusy] = useState('');
  const [presetError, setPresetError] = useState('');
  const [namedNotice, setNamedNotice] = useState('');
  const changes = useMemo(() => {
    if (!preview) return [];
    return Object.keys(preview).filter((key) => key !== 'revision' && stable(preview[key]) !== stable(draft[key]))
      .map((key) => ({ key, before: draft[key], after: preview[key] }));
  }, [preview, draft]);

  const refreshSaved = async () => {
    try {
      const result = await request('/api/presets');
      setSaved(result.presets || []);
      setPresetError('');
    } catch (failure) { setPresetError(failure.message || 'Não foi possível carregar os presets salvos.'); }
  };

  useEffect(() => { refreshSaved(); }, []);

  const saveNamed = async (event) => {
    event.preventDefault();
    const name = presetName.trim();
    const id = presetId(name);
    if (saved.some((preset) => preset.id === id) && !window.confirm(`O preset “${name}” já existe. Substituir sua configuração?`)) return;
    setPresetBusy('save'); setPresetError('');
    try {
      const result = await request('/api/presets', { method: 'POST', body: { name, candidate: draft } });
      setSaved((current) => [...current.filter((preset) => preset.id !== result.preset.id), result.preset].sort((a, b) => a.name.localeCompare(b.name)));
      setNamedNotice(`Preset “${result.preset.name}” salvo neste mundo.`);
      setPresetName('');
    } catch (failure) { setPresetError(failure.message || 'Não foi possível salvar o preset.'); }
    finally { setPresetBusy(''); }
  };

  const loadNamed = async (preset) => {
    setPresetBusy(preset.id); setPresetError(''); setPreview(null);
    try {
      const config = await request(`/api/presets?id=${encodeURIComponent(preset.id)}`);
      if (config.schemaVersion !== draft.schemaVersion) throw new Error('Este preset usa uma versão de configuração incompatível.');
      setPreview({ ...config, revision: draft.revision });
      setNamedNotice(`Preset “${preset.name}” carregado para comparação. Nenhuma alteração foi aplicada ainda.`);
    } catch (failure) { setPresetError(failure.message || 'Não foi possível carregar o preset.'); }
    finally { setPresetBusy(''); }
  };

  const deleteNamed = async (preset) => {
    if (!window.confirm(`Excluir o preset “${preset.name}” deste mundo?`)) return;
    setPresetBusy(preset.id); setPresetError('');
    try {
      await request(`/api/presets?id=${encodeURIComponent(preset.id)}`, { method: 'DELETE' });
      setSaved((current) => current.filter((item) => item.id !== preset.id));
      setNamedNotice(`Preset “${preset.name}” excluído.`);
    } catch (failure) { setPresetError(failure.message || 'Não foi possível excluir o preset.'); }
    finally { setPresetBusy(''); }
  };

  const exportDraft = () => {
    const blob = new Blob([`${JSON.stringify(draft, null, 2)}\n`], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `cobbleverse-config-revision-${draft.revision}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
    setNotice('Rascunho exportado. O arquivo pode ser importado em outro servidor compatível.');
  };

  const importFile = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    setError(''); setNotice(''); setPreview(null);
    if (!file) return;
    if (file.size > 32 * 1024) { setError('O arquivo excede 32 KiB.'); return; }
    try {
      const parsed = JSON.parse(await file.text());
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('O arquivo precisa conter um objeto de configuração JSON.');
      if (parsed.schemaVersion !== draft.schemaVersion) throw new Error(`Versão de configuração incompatível. Esperada: ${draft.schemaVersion}.`);
      const unknown = Object.keys(parsed).filter((key) => !Object.hasOwn(draft, key));
      if (unknown.length) throw new Error(`Campos desconhecidos: ${unknown.slice(0, 5).join(', ')}.`);
      const candidate = { ...draft, ...parsed, revision: draft.revision };
      setPreview(candidate);
      setNotice(`Arquivo “${file.name}” carregado para comparação. Nenhuma alteração foi aplicada ainda.`);
    } catch (failure) { setError(failure.message || 'Não foi possível ler este arquivo JSON.'); }
  };

  const apply = () => {
    if (!preview || !changes.length) return;
    update(preview);
    setNotice('Configuração importada para o rascunho. Valide e salve no rodapé quando estiver pronta.');
    setPreview(null);
  };

  return <>
    <div className="page-intro"><h1>Presets e arquivos</h1><p>Prepare perfis de gerações ou transfira uma configuração completa entre servidores.</p></div>
    {!canEdit && <Notice tone="warning">Sua credencial permite listar e comparar presets. Salvar, substituir, excluir ou importar exige o token de administrador.</Notice>}
    <Section title="Presets salvos neste mundo" description="Guarde configurações nomeadas para reutilizar. Carregar sempre abre uma comparação antes de alterar o rascunho.">
      {canEdit && <form className="preset-save-form" onSubmit={saveNamed}>
        <input aria-label="Nome do preset" maxLength={48} placeholder="Ex.: Temporada Johto" value={presetName}
          onChange={(event) => setPresetName(event.target.value)} disabled={Boolean(presetBusy)} />
        <button className="button button-accent" type="submit" disabled={Boolean(presetBusy) || !presetName.trim()}><BookmarkPlus size={16} />Salvar rascunho</button>
      </form>}
      {presetError && <Notice tone="error">{presetError}</Notice>}
      {saved.length > 0 ? <div className="preset-grid">
        {saved.map((preset) => <article key={preset.id}>
          <strong>{preset.name}</strong><span>Atualizado {new Date(preset.updatedAt).toLocaleString('pt-BR')}</span>
          <div className="preset-card-actions">
            <button className="button button-quiet button-small" type="button" disabled={Boolean(presetBusy)} onClick={() => loadNamed(preset)}><FolderOpen size={15} />Comparar</button>
            {canEdit && <button className="icon-button danger-hover" type="button" aria-label={`Excluir preset ${preset.name}`} disabled={Boolean(presetBusy)} onClick={() => deleteNamed(preset)}><Trash2 size={15} /></button>}
          </div>
        </article>)}
      </div> : <p className="empty-state">Ainda não há presets nomeados neste mundo.</p>}
      {namedNotice && <Notice>{namedNotice}</Notice>}
    </Section>
    <Section title="Perfis rápidos" description="Os presets alteram somente o rascunho e preservam as exceções, regras de XP e captura atuais.">
      <div className="preset-grid">
        <article><strong>Kanto</strong><span>Somente a primeira geração, com circuito Kanto.</span>
          <button className="button button-quiet button-small" type="button" disabled={!canEdit} onClick={() => { setPreview(createPreset(draft, 'kanto')); setError(''); setNotice('Compare os campos antes de aplicar o preset.'); }}>Comparar preset</button>
        </article>
        <article><strong>Progressão Johto</strong><span>Gerações 1 e 2 liberadas, com circuitos Kanto e Johto.</span>
          <button className="button button-quiet button-small" type="button" disabled={!canEdit} onClick={() => { setPreview(createPreset(draft, 'johto')); setError(''); setNotice('Compare os campos antes de aplicar o preset.'); }}>Comparar preset</button>
        </article>
        <article><strong>Todas as gerações</strong><span>Gerações 1 a 9 e circuitos clássicos disponíveis no pack.</span>
          <button className="button button-quiet button-small" type="button" disabled={!canEdit} onClick={() => { setPreview(createPreset(draft, 'all')); setError(''); setNotice('Compare os campos antes de aplicar o preset.'); }}>Comparar preset</button>
        </article>
      </div>
    </Section>
    <Section title="Importar ou exportar JSON" description="Importar atualiza somente o rascunho deste painel. A validação do servidor continua obrigatória antes do salvamento.">
      <div className="preset-file-actions">
        <label className={`button button-quiet${canEdit ? '' : ' is-disabled'}`}>
          <Upload size={16} />Escolher arquivo JSON
          <input type="file" accept=".json,application/json" disabled={!canEdit} onChange={importFile} />
        </label>
        <button className="button button-secondary" type="button" onClick={exportDraft}><Download size={16} />Exportar rascunho atual</button>
      </div>
      <p className="field-hint">Limite de importação: 32 KiB. O arquivo precisa usar a mesma versão de schema do servidor.</p>
      {error && <Notice tone="error">{error}</Notice>}
      {notice && <Notice>{notice}</Notice>}
      {preview && <div className="preset-preview">
        <div className="preset-preview-head"><FileJson2 size={18} /><strong>Comparação do rascunho</strong><span>{changes.length} campos diferentes</span></div>
        {changes.length ? <div className="history-diff">{changes.map(({ key, before, after }) => <div className="history-diff-row" key={key}>
          <strong>{labels[key] || key}</strong><div><span>Atual</span><code>{describe(before)}</code></div><div><span>Importado</span><code>{describe(after)}</code></div>
        </div>)}</div> : <p className="empty-state">Este preset já corresponde ao rascunho atual.</p>}
        <button className="button button-accent history-restore" type="button" disabled={!canEdit || !changes.length} onClick={apply}>Aplicar ao rascunho</button>
      </div>}
    </Section>
  </>;
}
