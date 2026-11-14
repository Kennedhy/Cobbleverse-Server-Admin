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

- Oferecer um multiplicador global e um multiplicador adicional para Pokémon lendários, preservando os fatores e efeitos individuais de cada Poké Bola.
- Permitir limites editáveis por espécie/forma com contagem global para todo o mundo e todos os jogadores; `-1` significa sem limite.
- Contar Pokémon nas equipes, PCs salvos inclusive offline e custódia do GTS. Não contar Pokémon selvagens ainda soltos; isso evita varrer entidades e chunks.
- Indexar os dados existentes uma vez ao iniciar o mundo, em lotes cooperativos. Atualizar o índice por eventos de captura/ganho, liberação, evolução, edição de PC e custódia do GTS; não ler PCs em toda captura ou tick.
- Enquanto a indexação estiver pendente ou se falhar, bloquear capturas que tenham teto finito. O calculador de captura padrão do Cobblemon é necessário para que teto e multiplicadores também cubram Master Ball.

### Treinadores, dificuldade e recompensas

- Gerenciar pelo painel as configurações de spawn de treinadores que o RCT realmente expõe, incluindo chance, intervalo, limites, distâncias, cartão e NPC de associação.
- Configurar perfis de dificuldade por grupos criados pelo administrador nos dois lados: jogadores e treinadores. Jogadores são atribuídos por UUID; treinadores por categoria RCT e/ou ID específico.
- Permitir perfis por combinação de grupos, por um dos lados e um padrão geral, com prioridade de par exato, grupo de treinador, grupo de jogador e padrão.
- Aplicar ajustes de nível, IV mínimo e EV às cópias de combate dos NPCs RCT. Não alterar equipes originais do datapack; não aplicar a Pokémon selvagens ou batalhas PvP.
- Administrar o pagamento de NPCs do CobbleDollars e seu multiplicador de renda. O multiplicador desse mod também afeta Pokémon selvagens; não há configuração de prêmio por treinador individual.
- Shiny é controlado somente pela taxa global de aparição exposta pelo Cobblemon; explicar que outros sistemas que usam essa taxa global também podem ser afetados.

### Combate de Pokémon selvagens

- Usar o Fight or Flight que já faz parte do modpack para gerenciar ataques diretos de Pokémon selvagens contra jogadores.
- Expor no painel controles de reação a provocação, ataque sem provocação, restrição de ataques espontâneos à pouca luz, níveis mínimos, limiares/multiplicador de agressividade, ativação de ataques à distância e dano mínimo/máximo para ataques corpo a corpo e à distância.
- Alterar somente os campos conhecidos de config/fightorflight.json5, preservando opções e comentários desconhecidos; escrita apenas sob ação administrativa e sem trabalho por tick.
- Credenciais viewer podem consultar, mas não editar. Detectar alterações externas concorrentes e sinalizar que o servidor deve reiniciar para aplicar os novos valores.

### PC dos jogadores

- Permitir que administradores consultem o PC dos jogadores para gestão e suporte.
- Permitir que administradores adicionem, editem e excluam Pokémon diretamente nas caixas do PC pelo painel.
- Restringir qualquer alteração a credenciais de administrador; credenciais de consulta continuam somente leitura.
- Confirmar exclusões e evitar alterar um espaço cujo Pokémon tenha mudado desde que foi carregado.
- Aplicar mutações pelo armazenamento oficial do Cobblemon e serializar operações do painel, sem varredura nem rotina por tick.
- Dar suporte à busca de jogadores conectados e aos dados já salvos no mundo, com acesso protegido e consultas paginadas conforme a implementação atual.
- Permitir que administradores consultem e editem circuito e treinadores vencidos do progresso RCT individual. O jogador precisa estar conectado e fora de batalha; usar fingerprint contra alterações concorrentes e arquivar o estado anterior.

### GTS e casa de leilões

- Fornecer um mercado integrado pela GUI dos jogadores e administrado no painel web privado.
- Permitir vender Pokémon do PC e itens, pagando em CobbleDollars. Exibir espécie/forma, IVs, EVs, natureza, shiny e tamanho do Pokémon.
- Suportar preço fixo e leilão, reserva de lance, reembolso ao ser superado, retirada de pagamentos e entrega segura dos bens.
- Guardar Pokémon, itens e transações em custódia com reconciliação após reinícios; moderadores podem remover anúncios e devolver o bem, mantendo estorno de lance quando necessário.
- Manter o addon server-only. A GUI de mercado é voltada a jogadores e não reintroduz configuração administrativa dentro do jogo. Priorizar hooks sob demanda, limites de armazenamento e nenhum ciclo de varredura por entidades ou Pokémon.

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

Eventos especiais ou dados econômicos mais granulares continuam como possibilidades futuras. Não prometa ajustes que os mods integrados não exponham por API ou datapack; confirme compatibilidade antes de adicioná-los.

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

- A release pública mais recente continua 0.6.0 preview; as mudanças posteriores seguem agrupadas em `Unreleased`, sem criar uma versão para cada ajuste.
- O GTS/leilões passou por smoke local com o Cobbleverse 1.7.42, Cobblemon 1.7.3, CobbleDollars 2.0.0 Beta 5.1 e cliente conectado sem o addon. GUI aberta, custódia de Pokémon/itens, compra, lances, reembolso, entrega ao vencedor e pagamento pendente foram exercitados. A transação foi chamada pelo fixture de servidor; cliques manuais e captura visual não foram testados.
- A revisão atual indexa grupos/perfis RCT ao salvar regras, reutiliza tabelas ponderadas de raid em cache limitado e reduz alocações/custos de busca na verificação de batalhas e recuperação de inventário. Build local, 66 testes, auditoria server-only e smoke de raids no pack passaram. Ainda não há benchmark A/B de MSPT, heap ou TPS em servidor dedicado nem smoke de batalha real de treinador. Consulte `docs/TESTING.md`.
- O workspace pode conter alterações locais não commitadas de funções e redesign do painel. Preserve-as ao continuar; não faça commit, push, tag ou release sem instrução específica nesta etapa.
