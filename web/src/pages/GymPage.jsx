import { Choice, Notice, Section, SelectField, Toggle } from '../components/ui.jsx';

const regions = [
  ['kanto', 'Kanto'], ['johto', 'Johto'], ['hoenn', 'Hoenn'], ['sinnoh', 'Sinnoh'],
];

export default function GymPage({ draft, update }) {
  const setRegion = (region) => {
    const selected = new Set(draft.regions || []);
    selected.has(region) ? selected.delete(region) : selected.add(region);
    const regions = [...selected];
    const initialRegion = regions.includes(draft.initialRegion) ? draft.initialRegion : regions[0] || 'freeroam';
    update({ regions, initialRegion });
  };
  const options = (draft.regions || []).map((region) => ({
    value: region,
    label: regions.find(([id]) => id === region)?.[1] || region,
  }));
  const initialRegion = options.some((option) => option.value === draft.initialRegion) ? draft.initialRegion : options[0]?.value || 'freeroam';
  return <>
    <div className="page-intro"><h1>Ginásios</h1><p>Associe circuitos às gerações e escolha a ordem inicial do servidor.</p></div>
    <Section title="Circuitos disponíveis" description="A região inicial é compartilhada. Progresso e insígnias continuam individuais por jogador.">
      <Toggle label="Associar circuitos às gerações selecionadas automaticamente" checked={draft.automaticRegions}
        onChange={(automaticRegions) => update({ automaticRegions })} />
      <div className="region-grid">
        {regions.map(([id, label]) => <Choice key={id} selected={(draft.regions || []).includes(id)} onClick={() => setRegion(id)} disabled={draft.automaticRegions}>
          {label}
        </Choice>)}
      </div>
      <div className="form-row form-row-single">
        <SelectField label="Circuito inicial" value={initialRegion} options={options.length ? options : [{ value: 'freeroam', label: 'Exploração livre' }]}
          disabled={draft.automaticRegions || options.length === 0} onChange={(initialRegion) => update({ initialRegion })} />
      </div>
    </Section>
    <Section title="Aparição dos ginásios" description="Afeta estruturas novas e os times dos treinadores associados.">
      <Toggle label="Filtrar ginásios e líderes" description="Regiões fora da seleção deixam de gerar novos ginásios." checked={draft.filterGyms} onChange={(filterGyms) => update({ filterGyms })} />
      <Toggle label="Adaptar equipes dos treinadores" description="Substitui espécies indisponíveis por opções permitidas." checked={draft.filterTrainerTeams} onChange={(filterTrainerTeams) => update({ filterTrainerTeams })} />
    </Section>
    <Notice>Estruturas que já foram construídas permanecem no terreno. A configuração nova exige reinício.</Notice>
  </>;
}
