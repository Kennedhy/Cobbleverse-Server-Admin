import { parseList } from '../api.js';
import { Field, Notice, Section, Toggle } from '../components/ui.jsx';

function ListField({ label, value, onChange, hint }) {
  return <Field label={label} hint={hint}>
    <textarea rows={3} value={(value || []).join(', ')} placeholder="cobblemon:pikachu, eevee"
      onChange={(event) => onChange(parseList(event.target.value))} />
  </Field>;
}

export default function RulesPage({ draft, update }) {
  return <>
    <div className="page-intro"><h1>Regras</h1><p>Defina exceções, formas e mecânicas especiais do servidor.</p></div>
    <Section title="Formas e mecânicas" description="As regras de geração se aplicam à espécie-base; use exceções quando precisar ajustar um caso.">
      <Toggle label="Permitir formas regionais" description="Alola, Galar, Hisui e outras formas regionais." checked={draft.regionalForms} onChange={(regionalForms) => update({ regionalForms })} />
      <Toggle label="Permitir megas e formas primais" checked={draft.megaEvolutions} onChange={(megaEvolutions) => update({ megaEvolutions })} />
    </Section>
    <Section title="Exceções de espécies" description="Use nomes internos, separados por vírgula. Exemplos: pikachu, cobblemon:mew.">
      <div className="form-grid">
        <ListField label="Permitir espécies" value={draft.allowSpecies} onChange={(allowSpecies) => update({ allowSpecies })} />
        <ListField label="Bloquear espécies" value={draft.denySpecies} onChange={(denySpecies) => update({ denySpecies })} />
      </div>
    </Section>
    <Section title="Exceções de formas" description="Formato: espécie/forma. As formas usam os nomes internos do Cobblemon.">
      <div className="form-grid">
        <ListField label="Permitir formas" value={draft.allowForms} onChange={(allowForms) => update({ allowForms })} hint="Exemplo: cobblemon:pikachu/alola" />
        <ListField label="Bloquear formas" value={draft.denyForms} onChange={(denyForms) => update({ denyForms })} hint="Exemplo: eevee/rockstar" />
      </div>
    </Section>
    <Notice>Formas regionais e megas podem ter regras próprias. Dynamax, terastalização e golpes Z seguem as regras do modpack.</Notice>
  </>;
}
