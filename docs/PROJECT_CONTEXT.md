# Contexto e decisões do projeto

Este documento transfere para o repositório os objetivos e decisões confirmados pelo usuário nas conversas do Codex. Ele complementa os documentos técnicos; README.md e docs/TESTING.md descrevem o estado implementado e a evidência disponível. Não trate itens de ideias futuras como requisitos já aprovados.

## Objetivo

Criar o Cobbleverse Server Admin, um gerenciador leve para servidores Cobbleverse/Cobblemon. O administrador configura regras de Pokémon num painel web privado, vê os jogadores online, administra seus PCs e progressão, e reinicia o servidor pelo supervisor. O servidor aplica as regras usando integrações compatíveis com Cobblemon, RCT e o conteúdo do modpack.

O usuário pediu explicitamente para remover a interface de configuração dentro do jogo e organizar o gerenciamento em uma interface web. O addon deve continuar server-only; jogadores não devem precisar instalar este JAR.

## Decisões confirmadas pelo usuário

### Gerações, espécies e ginásios

- Permitir três políticas: somente uma geração, até uma geração e seleção personalizada.
- Disponibilizar as gerações 1–9 e filtrar espécies pela geração em que estrearam.
- Permitir pesquisar espécies e criar exceções. Formas, megaevoluções e casos especiais podem ter regras próprias.
- Gerenciar conteúdo de ginásios/circuitos que já existe no modpack. A seleção de geração não deve prometer criar circuitos que o pack não inclui.
- Mudanças de geração, ginásio e mundo podem exigir reinício; a interface deve explicar quando uma regra ainda não está ativa.

### XP e progressão

- Oferecer multiplicador global de XP e multiplicadores por jogador ou grupo.
- Grupos são criados pelo administrador no painel; o jogador também pode receber um ajuste individual.
- Disponibilizar os dois modos de limite de nível já definidos pelo produto: global e derivado do progresso RCT.
- Quando mais de um jogador estiver perto de um Pokémon selvagem, calcular o limite usando o maior limite aplicável entre esses jogadores.
- Configurar no painel o comportamento para cada caso em que um Pokémon selvagem exceda o limite do jogador. Não fixar uma única resposta sem opção administrativa.
- Opcionalmente bloquear em PvE batalhas com Pokémon acima do limite global ou RCT atual, com opção de bloquear a equipe inteira ou somente impedir que o Pokémon acima do cap entre. PvP sempre ignora o bloqueio de batalha por nível. A regra é desligada por padrão; com o progresso RCT carregando, não deve bloquear por engano.

### Captura

- O controle confirmado para a primeira versão é um multiplicador global da chance de captura.
- Aplicar o multiplicador preservando os efeitos e diferenças de cada Poké Bola; não substituir a lógica de captura individual das bolas.

### Treinadores, dificuldade e recompensas

- Gerenciar pelo painel as configurações de spawn de treinadores que o RCT realmente expõe, incluindo chance, intervalo, limites, distâncias, cartão e NPC de associação.
- Configurar perfis de dificuldade por grupos criados pelo administrador nos dois lados: jogadores e treinadores. Jogadores são atribuídos por UUID; treinadores por categoria RCT e/ou ID específico.
- Permitir perfis por combinação de grupos, por um dos lados e um padrão geral, com prioridade de par exato, grupo de treinador, grupo de jogador e padrão.
- Aplicar ajustes de nível, IV mínimo e EV às cópias de combate dos NPCs RCT. Não alterar equipes originais do datapack; não aplicar a Pokémon selvagens ou batalhas PvP.
- Administrar o pagamento de NPCs do CobbleDollars e seu multiplicador de renda. O multiplicador desse mod também afeta Pokémon selvagens; não há configuração de prêmio por treinador individual.
- Shiny é controlado somente pela taxa global de aparição exposta pelo Cobblemon; explicar que outros sistemas que usam essa taxa global também podem ser afetados.

### PC dos jogadores

- Permitir que administradores consultem o PC dos jogadores para gestão e suporte.
- Permitir que administradores adicionem, editem e excluam Pokémon diretamente nas caixas do PC pelo painel.
- Restringir qualquer alteração a credenciais de administrador; credenciais de consulta continuam somente leitura.
- Confirmar exclusões e evitar alterar um espaço cujo Pokémon tenha mudado desde que foi carregado.
- Aplicar mutações pelo armazenamento oficial do Cobblemon e serializar operações do painel, sem varredura nem rotina por tick.
- Dar suporte à busca de jogadores conectados e aos dados já salvos no mundo, com acesso protegido e consultas paginadas conforme a implementação atual.
- Permitir que administradores consultem e editem circuito e treinadores vencidos do progresso RCT individual. O jogador precisa estar conectado e fora de batalha; usar fingerprint contra alterações concorrentes e arquivar o estado anterior.

### Interface, acesso e operação

- Toda configuração administrativa fica no painel web; não manter painel administrativo dentro do jogo.
- Organizar o painel para cobrir gerações/ginásios, regras, XP/níveis, captura/shiny, treinadores/recompensas, PC/progressão individual e estado dos jogadores online.
- O dashboard se limita a dados de jogadores e regras ativas, e ao reinício supervisionado. Não manter páginas de presets, gerenciamento de acesso, diagnóstico ou monitoramento de recursos do servidor.
- Manter o painel privado, preso ao loopback por padrão. Para uso remoto, preferir VPN/segmento privado, HTTPS privado ou túnel SSH. Não expor a porta do painel à internet nem aos jogadores.
- Usar credenciais de administrador e consulta conforme a necessidade. Acesso de consulta pode ler dados do PC; alterações de regras, acessos e Pokémon ficam restritas a administradores.

## Desempenho e integração

Leveza é um requisito central, especialmente porque o modpack já contém muitos mods. Não adicionar trabalho por tick, varreduras constantes de entidades/PC/chunks, banco de dados externo ou dependências pesadas sem uma necessidade demonstrada. Preferir hooks/eventos específicos, cache limitado, leitura sob demanda, lotes cooperativos e filas com limites.

Use as APIs/eventos das versões compatíveis do Cobblemon e dos mods integrados. Para qualquer novo otimizador, medir em cenário comparável; não instalar ou habilitar mods de otimização apenas pelo nome. O procedimento existente está em docs/PERFORMANCE.md.

O usuário pediu que fossem pesquisados o site [Cobblemon Extras](https://cobblemonextras.com/) e o repositório [chasem-dev/CobblemonExtras](https://github.com/chasem-dev/CobblemonExtras) para inspiração e integração. São referências para avaliar recursos e compatibilidade, não autorização automática para copiar código. O usuário disse que código de outros mods poderia ser aproveitado se possível; antes de qualquer reutilização, verificar licença, proveniência, dependências, compatibilidade com a licença MIT deste projeto e atribuição. Preferir interoperabilidade por API.

## Ideias futuras, ainda sem compromisso de escopo

Controles adicionais de lendários, eventos especiais ou dados econômicos mais granulares continuam como possibilidades futuras. Não prometa ajustes que os mods integrados não exponham por API ou datapack; confirme compatibilidade antes de adicioná-los.

## Validação, servidor de teste e segurança de dados

- O usuário aceitou a EULA do Minecraft somente para um servidor de teste descartável.
- Um host dedicado de teste não estava disponível. A resposta “SSH já configurado” foi seguida pela confirmação de que a máquina dedicada ainda não estava acessível. Não presuma que exista um servidor remoto pronto para uso.
- O smoke do GitHub Actions e os limites da evidência estão registrados em docs/TESTING.md. Esse smoke não substitui benchmark A/B de carga em host dedicado.
- Em 8 de novembro de 2026, um teste local usou o usuário CobbleDemo, mas não confirmou persistência de Pokémon no PC. Portanto, não afirme que esse jogador está online ou tem Pokémon sem verificar novamente.
- O teste local usou cópias temporárias e não copiou o mundo/saves da instância original. Antes de retomar, confirme se os processos e diretórios temporários ainda existem; não assuma que o servidor continua rodando.
- Nunca versionar tokens, credenciais, saves, arquivos de jogador ou JARs de mods de terceiros.

## Preferências de colaboração e publicação

- O usuário quer implementar as funções no projeto antes de voltar às tarefas do GitHub.
- O repositório GitHub pretendido é [Kennedhy/Cobbleverse-Server-Admin](https://github.com/Kennedhy/Cobbleverse-Server-Admin). O usuário informou que o GitHub CLI está autenticado e pediu organização como projeto open source, releases e documentação.
- Não fazer releases para mudanças pequenas. Agrupar alterações relacionadas e publicar uma nova versão quando houver um conjunto significativo, mantendo alterações menores no Unreleased até então.
- Conversar em português brasileiro e apresentar o que foi alterado, por que, como foi verificado e as limitações relevantes.

## Estado registrado neste repasse

- O projeto existente documenta a versão 0.5.0 preview e validação do pack; o ciclo atual reúne PCs, regras de cap de batalha, progressão RCT, dificuldade e economia de treinadores e taxa shiny para a próxima versão preview. Consulte README.md, docs/IMPROVEMENTS.md e docs/TESTING.md antes de propor duplicatas.
