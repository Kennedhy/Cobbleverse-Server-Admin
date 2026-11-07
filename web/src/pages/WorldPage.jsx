import { Notice, Section, Toggle } from '../components/ui.jsx';

export default function WorldPage({ draft, update }) {
  return <>
    <div className="page-intro"><h1>Mundo</h1><p>Gerencie como as regras novas afetam Pokémon e estruturas existentes.</p></div>
    <Section title="Pokémon já capturados" description="A política restringe o uso de Pokémon fora das regras atuais.">
      <Toggle label="Restringir Pokémon existentes que ficaram indisponíveis" checked={draft.restrictExistingPokemon}
        onChange={(restrictExistingPokemon) => update({ restrictExistingPokemon })} />
      <Toggle label="Permitir inicial de resgate único" description="Jogadores afetados podem receber um inicial válido sem perder os Pokémon atuais." checked={draft.rescueStarter}
        onChange={(rescueStarter) => update({ rescueStarter })} />
    </Section>
    <Section title="Mudanças no terreno" description="As regras novas afetam os próximos carregamentos e gerações do mundo.">
      <div className="world-facts">
        <div><span className="fact-marker" /><p><strong>Construções existentes</strong><br />Ginásios já construídos permanecem no terreno.</p></div>
        <div><span className="fact-marker" /><p><strong>Configuração pendente</strong><br />Reinicie o servidor para carregar novas regras de recursos e mundo.</p></div>
      </div>
    </Section>
    <Notice>Pokémon existentes não são apagados quando as regras mudam.</Notice>
  </>;
}
