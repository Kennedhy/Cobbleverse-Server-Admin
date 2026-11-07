import { Notice, NumberField, Section } from '../components/ui.jsx';

export default function CapturePage({ draft, update }) {
  return <>
    <div className="page-intro"><h1>Captura</h1><p>Ajuste globalmente a chance final calculada pelo Cobblemon.</p></div>
    <Section title="Chance de captura" description="O multiplicador é aplicado depois dos fatores padrão da tentativa.">
      <div className="capture-control">
        <NumberField label="Multiplicador global da chance" value={draft.captureMultiplier} min={0} max={100} step={0.05} suffix="×"
          onChange={(captureMultiplier) => update({ captureMultiplier })} hint="1,00× mantém a chance original; 0 desativa capturas comuns." />
        <div className="capture-explainer">
          <p><strong>As Poké Bolas continuam fazendo diferença.</strong></p>
          <p>Os efeitos próprios de cada bola participam do cálculo antes do multiplicador global.</p>
        </div>
      </div>
    </Section>
    <Notice tone="warning">Master Ball continua garantida. Esta regra cobre o calculador padrão do Cobblemon; outros mods podem usar um cálculo próprio.</Notice>
  </>;
}
