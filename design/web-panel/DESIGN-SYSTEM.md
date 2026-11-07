# Cobbleverse Control — especificação da interface web

## Objetivo e fluxo

Painel administrativo em português para configurar o servidor COBBLEVERSE. O navegador edita um rascunho local; o mod continua sendo a autoridade, valida a versão recebida, grava uma revisão pendente e aplica as regras após reiniciar. A interface abre em **Gerações** e compartilha uma barra inferior com atualizar, validar e salvar.

## Direção visual

- Fundo grafite/azul muito escuro (`#0C111B`); navegação e cabeçalho em carvão azulado (`#101722`).
- Superfícies de formulário em grafite (`#252B35`), bordas finas (`#48515F`), texto principal quase branco (`#F2F4F8`) e texto auxiliar cinza azulado (`#ACB6C5`).
- Um único acento verde-azulado (`#17CBB5`) para seleção, foco e ação principal; vermelho e amarelo só para erros e avisos reais.
- Tipografia de sistema sans-serif, títulos 24–32 px, conteúdo 14–16 px e controles nunca menores que 13 px.
- Cantos entre 4 e 8 px; sombras discretas; separadores horizontais. Sem pixels de Minecraft, ornamentos Pokémon, gráficos fictícios ou cartões aninhados.
- Ícones de linha funcionais (casa, círculo de geração, prédio de ginásio, ajustes, experiência, captura, mundo e diagnóstico), 18–20 px.

## Layout e páginas

- Barra lateral fixa de aproximadamente 224 px com marca Cobbleverse Control e oito áreas: Visão geral, Gerações, Ginásios, Regras, XP e níveis, Captura, Mundo, Diagnóstico.
- Cabeçalho compacto com estado real do servidor e revisão ativa.
- Área principal em grade de 12 colunas, largura confortável e scroll vertical em telas menores.
- Barra de ações fixa no fim do conteúdo: Atualizar, Validar configuração e Salvar alterações. Estado pendente e requisito de reinício são sempre visíveis.
- Em celular, a navegação vira menu recolhível e a barra de ações empilha os botões sem rolagem horizontal.
- Visão geral resume somente dados reais da revisão ativa, revisão pendente, jogadores conectados e amostras de desempenho obtidas quando o painel é consultado.
- Gerações usa três opções exclusivas de modo e uma grade simples de nove gerações; a busca de espécies abre uma área de pesquisa dedicada.
- Ginásios, Regras, XP e níveis, Captura e Mundo são formulários próprios, organizados por separadores e rótulos persistentes.
- XP usa tabelas editáveis para grupos e jogadores. Um ajuste individual substitui o grupo; o multiplicador global continua sendo aplicado.
- Diagnóstico mostra relatório de validação, status ativo/pendente, compatibilidade e ações de restauração/descartar.

## Interação e estados

- Todos os campos alteram apenas o rascunho do navegador até a validação e o salvamento.
- Validação roda quando solicitada; durante a análise, a UI consulta o estado somente enquanto o trabalho estiver ativo. Não existe polling em repouso.
- Salvar só fica disponível para o mesmo rascunho e a mesma revisão que receberam validação sem erros.
- Mudança concorrente de revisão solicita atualização e impede sobrescrever o administrador que salvou antes.
- Falha de rede mantém o rascunho local e identifica a revisão atual. Reinício exigido aparece junto do estado pendente.
- Confirmações, erros e avisos usam texto claro e estados semânticos; controles têm foco visível, rótulos acessíveis e suporte a `prefers-reduced-motion`.

## Estrutura do frontend

`App` coordena sessão, estado e rota selecionada. `AppShell`, `Sidebar`, `ServerHeader` e `ActionBar` são componentes compartilhados. Cada área tem seu componente de página. `SpeciesSearch` e `Login` são superfícies próprias. Chamadas HTTP e normalização de configuração ficam fora dos componentes de apresentação.

## Limites do servidor

- React/Vite só gera arquivos estáticos para o JAR; Node e dependências npm não são carregados na JVM em produção.
- Um servidor HTTP do JDK serve o painel apenas no loopback. Sessão autenticada, cookie HttpOnly/SameSite, sem CORS, checagem de origem/host e limites de corpo protegem a API.
- Estado e alterações da configuração passam pela thread principal do Minecraft; leituras pesadas de PCs e equipes continuam no serviço de validação em lotes já existente.
- Não há varredura de Pokémon, jogadores ou mundos por tick, nem dependência de navegador/Chromium embutido.
