# Estado de validação

O projeto está sendo migrado para Cobbleverse 1.8.0 (Minecraft 1.21.1, Cobblemon 1.8.4). Os resultados históricos abaixo referem-se a 1.7.x e não comprovam compatibilidade com 1.8.0.

## Versão 0.8.0 Preview — build local (2026-11-19)

- `.\gradlew.bat --no-daemon --max-workers=1 build` passou com **72 testes, 0 falhas, 0 erros**, incluindo `sourcesJar`; `test remapJar remapVerificationJar --rerun-tasks` também passou do zero.
- `tools/verify_server_jar.py` confirmou o artefato server-only `cobbleverse-generation-control-0.8.0.jar`: 185 classes, 37 mixins, 743.173 bytes e assets web presentes. SHA-256: `0053877ad0a5f2e695be2fb6f44a24684a43b3561fa7d33f73c86e089cf586d4`.
- A auditoria do painel e o smoke conectado de captura desta versão estão descritos nas seções de 19/11/2026 abaixo. Os limites conhecidos continuam registrados no fim da migração.
- O workflow de build e a publicação da release são executados após o envio da tag `v0.8.0`.

## Migração Cobbleverse 1.8.0 — validação local (2026-11-18/19)

- Atualizados os pins para Fabric Loader 0.19.5, Fabric API 0.116.18+1.21.1, Cobblemon 1.8.4, RCT 0.19.2-beta e RCT API 0.16.2-beta. Os hashes SHA-256 das dependências estão registrados em `gradle/verification-metadata.xml`.
- `.\gradlew.bat --no-daemon --max-workers=1 test remapJar remapVerificationJar` passou. As métricas do JAR final auditado estão registradas abaixo.
- Adaptado o filtro de starters para preservar ordem, metadados de categoria e seleção aleatória. O primeiro boot revelou alterações no Raid Dens 0.13.5 (campo `aspects` removido e nova assinatura de `spawnRaidBoss`); o mixin e a lista de versões reconhecidas foram atualizados. O servidor completou o boot com Cobblemon 1.8.4, Raid Dens, RCT 0.19.2 e os demais módulos reconhecidos, sem falha de Mixin.
- Em uma cópia descartável do pack, um cliente Fabric sem o addon conectou. O smoke autenticado confirmou estado do painel, configurações/anúncios/transações GTS, simulação de filtros/XP/cap e consulta do PC ao vivo, incluindo o Pokémon de teste.
- A validação conectada do armazenamento não foi concluída: durante a consulta de progresso, o painel respondeu HTTP 503/timeout enquanto o smoke adicionava 240 Pokémon ao servidor. Isso valida a resposta limitada de servidor ocupado, mas deixa pendente a confirmação da rechecagem incremental do store em runtime. Não aumentamos o timeout de produção.
- **Limitação do ambiente do smoke:** o servidor descartável foi iniciado sem C2ME, pois a seleção de mods do pack apresenta requisitos Java incompatíveis entre C2ME e Cobblemon; Athena e Fusion, marcados como indisponíveis no servidor pelo índice, foram incluídos por serem dependências de outros mods. Portanto, o boot e os fluxos acima não certificam a pasta server-side original sem ajustes.
- Removidos a página e o endpoint de Fight or Flight porque Cobbleverse 1.8.0 remove esse mod. Nenhum comportamento substituto de agressividade foi implementado; as opções antigas não são equivalentes automaticamente à configuração Cobblemon `playerDamagePokemon`.
- Os filtros de spawn agora inspecionam membros dentro de `herdablePokemon` (incluindo líderes Alpha) e `habitat_pools`, compondo espécie com forma/aspectos/modificadores. Testes unitários cobrem a preservação de grupos com líder válido e o descarte de grupos sem líder permitido.
- A auditoria do Raid Dens 0.13.5 confirmou que `getRaidBoss(id)` pode retornar `null` durante a carga assíncrona. Os hooks agora adiam a decisão para IDs registrados ainda sem objeto; a rolagem não tenta carregar milhares de chefes e usa os pesos já filtrados no datapack.
- Em smoke dedicado descartável com política somente Kanto, o verificador passou: 1.746 arquivos de spawn/1.293 propriedades inspecionadas (155 Alpha), 51 pools/321 spawns de habitat, 1.124 recursos de chefes de raid (170 permitidos e 954 bloqueados, todos com peso zero), 9 estruturas de ginásio e 1.714 equipes RCT. Relatório: `build/pack-smoke-cobbleverse-180/server/runtime-policy-result.json`.
- `test remapJar remapVerificationJar` passou com 72 testes, sem falhas; a auditoria do JAR confirmou 185 classes, 37 mixins, 743.173 bytes e assets web. Os verificadores inspecionam os recursos carregados sem forçar a leitura síncrona do cache de chefes do Raid Dens.
- No fixture conectado do mercado, o cliente entrou e as telas do PC e dos detalhes do anúncio passaram pelas asserções de `PokemonItem` e tooltip (nível, natureza, IVs, EVs e tamanho). O anúncio de Pokémon foi criado e cancelado. O smoke completo **não passou**: a confirmação assíncrona da retirada informou que o arquivo persistido ainda não continha o Pokémon; depois, o watchdog encerrou o servidor durante `SpawnDensityCapper`. Assim, a validação visual por captura e a retirada/persistência de ponta a ponta continuam pendentes; o encerramento do servidor não foi atribuído à GUI.
- O painel escuta em `0.0.0.0:18765` por padrão; um teste de integração confirma que uma instalação nova grava essa porta. No pack descartável, o JAR atual iniciou e aceitou a origem HTTP allowlisted pelo endereço Wi‑Fi deste mesmo computador: página/login 200, API sem sessão 401 e Host/Origin falsificados ou não listados 403. Isso não equivale a testar de outro dispositivo nem valida firewall/roteador.
- Reexecutei a verificação de política no pack Cobbleverse 1.8.0 com o JAR atual: 1.746 arquivos/1.293 entradas de spawn, 51 pools/321 spawns de habitat, 9 estruturas de ginásio, 1.714 equipes RCT e 1.124 recursos de raid; os 954 chefes proibidos ficaram com peso zero, sem falhas.
- Corrigi no smoke automatizado o marcador de inicialização do painel, que ainda esperava uma mensagem removida. A checagem do marcador contra o log do servidor atual passou.
- Uma tentativa de rechecagem sintética com PC em cache não produziu relatório dentro de 360 segundos durante a carga do pack; outra execução sem cache percorreu 900 Pokémon, mas não exercitou a rechecagem. Nenhuma das duas conta como aprovação; a validação conectada anterior de 240 Pokémon também terminou em HTTP 503/timeout.
- Ainda faltam batalhas jogadas de raid, captura por arremesso e combate PvE/PvP, rechecagem do armazenamento sob carga em runtime e smoke com a composição server-side original quando o conflito de dependências do pack for resolvido. O teste de rede foi feito pelo próprio host, não por outro dispositivo da LAN.

### Validação de 19/11/2026 — captura conectada e auditoria do painel

- O fixture conectado de captura passou na cópia descartável: 2.000 amostras por combinação, Poké Ball 12,95% em 1× e 22,7% em 2×, Ultra Ball 18,35% em 1× e 41,7% em 2×, zero sucesso com chance zerada e Master Ball sempre garantida. Esse resultado confirma os multiplicadores globais no calculador padrão do Cobblemon, não um arremesso manual com jogador.
- O painel foi percorrido em navegador real contra o servidor descartável (boot sem o fixture de desenvolvimento). As dez páginas — Visão geral, Gerações e ginásios, Regras, XP e níveis, Captura, Treinadores (aparições e dificuldade), PC dos jogadores, Mercado Pokémon, Histórico e Mundo — renderizaram sem erro de JavaScript. As leituras usadas pelas páginas responderam 200: `state`, `dashboard`, `legendary-limits`, `trainer-operations`, `trainer-difficulty`, `pc/players`, `pc/box`, `market/settings`, `market/listings`, `market/transactions`, `history` e `species`.
- Sem sessão, `/api/state` respondeu 401. Com credencial de consulta, as leituras continuaram em 200 e as oito rotas de escrita testadas responderam 403: `action`, `pc/edit`, `player/progress/edit`, `market/settings`, remoção de anúncio, `trainer-operations`, `trainer-difficulty` e `server/restart`. Na interface, os interruptores do mercado e os botões de salvar ficaram desabilitados nessa credencial.
- O valor de XP 101 foi recusado no cliente com a mensagem de faixa antes de qualquer chamada ao servidor, confirmando a validação de rascunho do painel. O PC abriu com 60 caixas a partir do arquivo salvo e a consulta de progresso RCT respondeu 409 para jogador offline, como esperado.
- Corrigido um defeito de build: `:sourcesJar` lia a saída de `:buildWeb` sem dependência declarada, e `gradlew build` falhava na validação do Gradle. O arquivo de fontes agora declara a ordem do build web e não empacota o painel gerado. `.\gradlew.bat --no-daemon --max-workers=1 build` passou, inclusive recriando os assets web do zero.


## Versão 0.7.0 Preview — build para release (2026-11-14)

- O workflow [Build 43641701925](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/43641701925), no commit `8411d0a`, passou em `test remapJar remapVerificationJar`, criou o JAR `0.7.0`, confirmou o artefato server-only e publicou os artefatos de CI.
- O verificador encontrou 184 classes, 37 mixins e 743.384 bytes no JAR; os assets web estavam presentes e não houve referências a classes client-only.
- Os smokes conectados de GTS/leilões e de configuração Fight or Flight, além do smoke dos limites lendários, estão descritos nas seções abaixo. O preview ainda não tem benchmark A/B em host dedicado, teste visual/manual completo da GUI do mercado ou batalha real de treinador após esta revisão.
- O workflow de release também executa os testes e a auditoria server-only antes de montar os assets publicados.
- O workflow [Release 43642158500](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/43642158500) passou e publicou a tag `v0.7.0`. Baixei os quatro assets e os três arquivos listados em `SHA256SUMS.txt` conferiram; o JAR publicado passou novamente no verificador server-only. SHA-256: `636a72d3951522eaed7f4509ec94bfa958237926259e74394cbae5310afa7ca5`.

## Limites lendários — build e smoke local (2026-11-14)

### Revisão geral em andamento

- O resgate passou a confirmar a gravação de forma assíncrona pela fila do armazenamento. O teste descartável de 14/11/2026 ocupou essa fila, mediu 51 ms para a chamada de resgate, observou dez ticks avançando antes de liberar a gravação e confirmou a entrega de um único UUID. Pedidos repetidos foram recusados. O resultado local está em `build/legendary-review-1f8ee8a0/rescue-review-result.json`.
- Esse cenário usa um jogador sintético offline. A liberação final para jogador conectado e a reconexão durante a confirmação ainda precisam de verificação.
- O cache da ordenação do mercado tem teste de atualização e rollback. A suíte contém 67 testes, todos aprovados, e o build do JAR/painel passou após os ajustes.
- Duas sessões HTTP autenticadas validaram a proteção da dificuldade de treinadores em um servidor descartável: a primeira salvou uma alteração, a segunda tentou salvar a revisão antiga e recebeu HTTP 409. Uma leitura posterior confirmou a preservação da configuração nova e o incremento único da revisão. As opções anteriores foram restauradas e o servidor foi encerrado. Evidência: `build/legendary-review-1f8ee8a0/trainer-conflict-result.json`. A primeira tentativa encontrou HTTP 503 durante a inicialização; a execução aprovada aguardou o carregamento e permitiu repetição limitada apenas da leitura.
- Uma medição local de `MarketStore.update` usou 100 anúncios com payload sintético de 8 KiB cada e 500 transações concluídas sem payload. Após dez aquecimentos, 50 gravações do journal de 963.308 bytes tiveram mediana de 8,68 ms, p95 de 11,35 ms e máximo de 12,37 ms. Isso inclui serialização, cópia para rollback, `force(true)` e substituição do arquivo. O script local está em `build/MarketJournalProbe.java`. Não representa uma operação financeira inteira, o tamanho máximo permitido, discos de produção ou TPS/MSPT sob carga; os resultados não justificam remover a confirmação durável.
- O estado nulo em um registro de resgate agora segue a recusa explícita de registro inválido, sem lançar `NullPointerException`. Após o ajuste, `test remapJar` passou novamente; o verificador confirmou 184 classes, 37 mixins e assets web no JAR somente para servidor.
- A revisão também adicionou cancelamento de leituras substituídas no painel do mercado e invalidação do cache de reprodução ao recarregar datapacks. A validação integrada dessas mudanças, a reconexão durante resgate e a revisão das demais gravações na thread do jogo continuam pendentes.

#### Auditoria dos caminhos de gravação (14/11/2026)

| Caminho | Execução observada no código | Decisão da revisão |
| --- | --- | --- |
| Confirmação de resgate | Serializa o store no jogo; grava pela fila do Cobblemon e agenda a conclusão no jogo | Removida a espera bloqueante; registro inicial pequeno ainda é gravado antes de conceder o Pokémon |
| Custódia e retirada de Pokémon no mercado | Espera de até dez segundos no executor `SAVE_WAITER`, com fila de 32; confirmação retorna à thread do jogo | Mantida a espera fora do jogo e a recuperação de operações pendentes |
| Journal financeiro e inventário de itens | Gravação síncrona durante a transação, com limites de registros/payload | Mantida a confirmação antes de movimentar bens; custo do journal medido acima, operação completa ainda sem benchmark |
| Migração automática de circuito RCT | Arquiva e grava o histórico no login somente quando o circuito muda | Mantida a preservação antes do setter que limpa o progresso; não é uma gravação por tick |
| Edição administrativa de progresso | Prepara no jogo, persiste no worker HTTP e aplica no jogo com verificação de estado | Mantida a divisão existente e o rollback do histórico em caso de conflito |
| Dificuldade de treinadores | Valida e constrói o índice antes da escrita atômica no worker HTTP | Protegida por revisão; conflito entre sessões validado em runtime |
| Varredura de validação | Leitura assíncrona e etapas cooperativas com orçamento de 1–3 ms, rechecagens e duração limitadas | Mantidos os limites; não transformada em varredura permanente |

Esta auditoria descreve os caminhos inspecionados, não garante ausência de pausas do sistema de arquivos nem desempenho sob carga em outra máquina.

- `compileJava`, `remapJar`, o build Vite (`npm run build`) e `git diff --check` passaram depois da implementação do índice global e da página de captura/lendários.
- Na revisão de desempenho, o build e os 66 testes automatizados passaram. A auditoria confirmou o JAR somente para servidor e os arquivos do painel embutidos.
- Em uma cópia descartável do Cobbleverse com Cobblemon 1.7.3, a equipe salva de um jogador offline retornou um Mewtwo de um total máximo de dois. A edição administrativa no PC ocupou a última vaga e o valor persistiu após reiniciar. A API retornou o mesmo total para administradores e leitores e recusou adicionar um terceiro Mewtwo com HTTP 409.
- O smoke cobriu inicialização, leitura do mundo, persistência e aplicação do teto pelo PC. Captura em jogo com Master Ball, capturas concorrentes, evolução e transferência de lendários pelo GTS ainda não foram exercitadas. O teste local não mediu TPS nem MSPT.

## Histórico 1.7.x: ataques de Pokémon selvagens — smoke local (2026-11-14)

- `npm run build` e `gradlew.bat remapJar` passaram com a nova página e a API de combate selvagem. O JAR server-only contém 175 classes, 36 mixins e 709.143 bytes; os assets web estão incluídos. SHA-256: `CA531334768125896D6B0943E1465F6CB328D226A173AB07F0241390DC7693EE`.
- Uma cópia descartável do Cobbleverse iniciou em `build/pack-smoke-gts-runtime/server`, Minecraft `127.0.0.1:25593`, painel `127.0.0.1:18771`; o modpack carregou Fight or Flight. A página e o bundle foram servidos pelo painel (HTTP 200), e a rota `/api/wild-combat` leu a configuração real `fightorflight.json5`.
- Com a credencial admin, a API salvou o dano máximo corpo a corpo de 5 para 5,5 e, numa segunda alteração, o nível mínimo de 15 para 16, a opção de pouca luz e o limiar neutro de 25 para 30. As releituras confirmaram cada valor e `restartRequired=true`. A comparação do arquivo antes/depois confirmou que cada gravação só alterou as chaves selecionadas.
- A credencial viewer consultou a página, mas recebeu 403 ao tentar salvar; uma revisão desatualizada foi recusada com 409. A configuração, as propriedades do servidor, o JAR e o fixture da cópia foram restaurados após o smoke; as portas temporárias foram liberadas. A instância original do launcher não foi modificada.
- O smoke confirma o gerenciamento e a persistência dos controles de Fight or Flight, não uma batalha com jogador conectado. A agressão e o dano efetivos em jogo ainda precisam ser observados com um jogador no mundo; não houve benchmark de TPS, MSPT, heap ou GC.

## GTS e leilões — smoke do candidato 0.7.0 (2026-11-14)

- A revisão de performance adicionou índices de grupos/perfis RCT para o caminho de início de batalha, cache limitado das distribuições de raid, evitou criar propriedades Primal quando o Pokémon não tem orbe relevante e trocou a busca aninhada de marcadores no inventário. `gradlew.bat --no-daemon --max-workers=1 test remapJar remapVerificationJar` passou com **66 testes, 0 falhas, 0 erros e 0 ignorados**; a etapa web ficou corretamente `UP-TO-DATE`. `git diff --check` passou, com avisos de conversão normal de LF/CRLF nos arquivos editados.
- Na build usada pelo smoke local dessa revisão, `tools/verify_server_jar.py` confirmou 172 classes, 36 mixins, 696.831 bytes e assets web. SHA-256: `8A551843B3B49B468504BCCC7A89BF44CE11A4BC5B5914FA07159C1FDD88FCAF`. O artefato inclui SGui 1.6.1+1.21.1 como biblioteca aninhada e o texto LGPL-3.0 correspondente; o fixture de teste não está no JAR de produção. A build candidata 0.7.0 mais recente está descrita acima.
- O smoke usou o Cobbleverse 1.7.42 fixado, Cobblemon 1.7.3 e CobbleDollars 2.0.0 Beta 5.1. O servidor dedicado isolado carregou o JAR atual em `build/pack-smoke-gts-runtime/server`, mundo `smoke-world`, porta `25592`; o painel abriu em `127.0.0.1:18770`. Um cliente Fabric com o pack completo, sem o JAR do gerenciador, conectou como `MarketSmoke`.
- O fixture conectado chamou a GUI `/mercado` no jogador real. CobbleDollars foi detectado. O fluxo de Pokémon criou anúncio a partir do PC, preservou IVs/EVs, cancelou e retirou o mesmo Pokémon; o fluxo de item anunciou três diamantes, cancelou e confirmou a devolução exata de três itens sem marcador de custódia restante. O resultado está em `build/pack-smoke-gts-runtime/server/connected-market-result.json`.
- As rotas autenticadas de configurações, anúncios e transações do painel responderam corretamente; a simulação do mesmo smoke confirmou filtro de gerações, XP por jogador/grupo e cap global. O servidor temporário terminou e as portas `25592` e `18770` foram liberadas; os listeners preexistentes foram preservados.
- No smoke dedicado sem cliente, a política Kanto passou contra os recursos do pack: 552 entradas de spawn, 9 estruturas de ginásio e 1.714 equipes RCT. Foram verificadas 19.225 raids (3.057 permitidas e 16.168 bloqueadas); a rolagem rejeitou um chefe proibido, selecionou um permitido duas vezes usando a tabela em cache e ignorou peso zero. Relatório: `build/pack-smoke-gts-runtime/server/runtime-policy-result.json`.
- O fixture abriu a GUI `/mercado` no cliente conectado, mas executou as transações diretamente pelos serviços; não houve automação de cliques/teclado nem inspeção por captura de tela. Compra por preço fixo debitou 250 de um saldo de teste persistido de 2.000 e entregou dois diamantes. No leilão, os lances de 100 e 200 debitaram o saldo corretamente, o lance superado gerou reembolso de 100 e, ao expirar, o vencedor recebeu os dois diamantes; a transação registrou o pagamento de 200 ao vendedor offline como pendente. O saldo final do comprador foi 1.550 e a pilha final, sete diamantes. O resultado completo está em `build/pack-smoke-gts-runtime/server/connected-market-result.json`.
- A GUI foi aberta e o cliente permaneceu conectado para inspeção, mas não houve validação visual por captura nem teste manual de cliques. A recuperação após queda no meio de cada etapa financeira continua sem runtime dedicado. Uma execução mais ampla do smoke retornou HTTP 503 na consulta de jogadores do PC antes dessas etapas; o painel e o helper de smoke agora repetem leituras/503 transitórios com limite, mas essa consulta ainda não foi reexecutada sob contenção.
- Não foi medido MSPT, TPS, heap ou GC sob carga, nem testada uma batalha real de treinador após esta refatoração. Os testes unitários verificam a precedência dos índices e a distribuição ponderada; falta validação A/B em host dedicado. O mercado mantém limites de registros/payload, uma fila limitada de espera de salvamento e nenhuma varredura contínua de Pokémon; o custo do journal síncrono durante transações também precisa ser medido.

## Versão 0.6.0 Preview — build e smoke local

- `gradlew.bat --no-daemon --max-workers=1 remapJar` e `npm run build` passaram localmente. O workflow [Build 43489280795](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/43489280795) passou no commit final `3192c0e`, incluindo a suíte automatizada, o empacotamento e a auditoria server-only. A [release preview v0.6.0](https://github.com/Kennedhy/Cobbleverse-Server-Admin/releases/tag/v0.6.0) publicou o JAR, o helper SSH, o preparador da pasta de mods e `SHA256SUMS.txt`; o JAR baixado também passou pelo verificador server-only (150 classes, 35 mixins, 499.847 bytes).
- Um servidor temporário iniciou em uma cópia isolada do pack, com mundo próprio `dashboard-test-060`, Minecraft na porta `25586` e painel em `127.0.0.1:18766`. O runtime carregou Minecraft 1.21.1, RCT 0.18.1-beta, RCT API 0.15.2-beta e CobbleDollars 2.0.0 Beta 5.1. Nenhum mundo ou configuração da instância original foi usado para o smoke.
- Em navegador Edge desktop, viewport 1440×960: login admin, dashboard, Treinadores, aparições/recompensas e dificuldade por grupos abriram sem erros JavaScript. Foram capturadas telas das duas abas em `cobbleverse-trainer-operations-060.png` e `cobbleverse-trainer-difficulty-060.png` na pasta temporária do computador.
- `/api/dashboard` respondeu 200, listou zero jogadores (nenhum cliente entrou), manteve a ação de reinício disponível pelo supervisor e não retornou campos de TPS, heap ou CPU. `/api/trainer-operations` respondeu 200 com 14 opções RCT e CobbleDollars disponível. A chance de spawn foi alterada de 0,55 para 0,56 e restaurada a 0,55. O multiplicador e a opção de pagamento NPC do CobbleDollars também foram alterados pela API, gravados no mod integrado e restaurados; as novas leituras confirmaram os valores anteriores.
- `/api/trainer-difficulty` carregou 1.559 treinadores e 10 categorias RCT. O painel gravou e recarregou dois grupos de demonstração e um perfil combinado no mundo descartável. A credencial viewer leu a configuração (200) e teve a gravação recusada (403).
- O primeiro acesso ao catálogo identificou que o RCT termina de registrar treinadores depois do evento inicial do servidor. A versão 0.6.0 agora deixa o catálogo retryable enquanto vazio e tenta atualizar sob demanda ao abrir a página; o smoke após reiniciar com o JAR corrigido confirmou 1.559 entradas.
- O primeiro boot da cópia ficou aproximadamente 5 segundos atrás enquanto o pack registrava treinadores e otimizava tabelas de spawn. Esse boot sem jogadores não mede custo contínuo nem desempenho do addon. Não foi feito benchmark A/B de MSPT, TPS, heap ou GC.
- Sem um jogador conectado, não foram exercitados no runtime a edição real de PC/progresso, batalhas, bloqueio PvE/PvP ou recompensa após vencer treinador. Esses caminhos compilaram; dependem de uma sessão com jogador para smoke representativo.

## Build 0.5.0

O Build [42505562693](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42505562693) passou no commit `ff38354`: testes, JAR remapeado, auditoria server-only e fixture de smoke separado. O JAR tem 129 classes, 33 mixins e 418.031 bytes; o smoke confirmou o SHA-256 `73d68e66d65c1681473dbe8e5106191661d67fcd9aa84fbe70c6e7ad1b0712f0`. As mudanças estão no `main`; nenhuma nova release foi criada.

O smoke [42505819843](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42505819843) passou com o Cobbleverse 1.7.42. Instalou 168 arquivos, preparou 103 JARs para o dedicado (33 mods client-only omitidos) e conectou um cliente sem o addon. Painel, autenticação e métricas passaram. A simulação confirmou filtro de geração, multiplicadores de XP por grupo/jogador e cap global. O PC encontrou um Pikachu shiny de nível 13 ao vivo e depois da reinicialização; a validação limitada reavaliou somente o store alterado; XP, limites e captura foram aplicados e restaurados ao descartar a revisão.

Na verificação do pack com Kanto ativo, o fixture inspecionou 552 entradas de spawn, 9 estruturas de ginásio carregadas e 1.714 equipes RCT. A verificação exige estruturas ativas apenas na região permitida. A captura foi amostrada 2.000 vezes por combinação: Poké Ball 11,55% em 1× e 24,8% em 2×; Ultra Ball 19,05% e 42,95%; chance zero teve zero sucessos e Master Ball permaneceu garantida. No RCT, o nível foi 35 com ambos os jogadores próximos, 20 quando o jogador com cap 35 estava distante, e os modos bloquear, manter nível 100 e limitar globalmente a 25 passaram. O fixture injeta somente progresso/caps RCT de teste; não mede spawn natural nem fluxo de batalha. Ainda falta medição A/B sob carga em host dedicado.

## Teste do pack no GitHub

O workflow manual **Cobbleverse runtime smoke** baixa o Cobbleverse 1.7.42 do Modrinth, verifica os arquivos e inicia um servidor e um cliente Fabric em uma máquina temporária do GitHub. O JAR informado no formulário é instalado apenas no servidor. O teste usa um mundo descartável e credenciais locais. A execução `42505819843` cobre painel e métricas, simulação de gerações/XP/cap, PC ao vivo e salvo, validação de armazenamento, aplicação/descarte seletivos, política Kanto carregada, probabilidades de captura e evento de nível selvagem com cap RCT.

Para testar mudanças ainda sem release, informe em `build_run` o ID de uma execução bem-sucedida do workflow **Build** na branch `main`. Com esse campo preenchido, o teste baixa o mod e o fixture da mesma execução e executa as verificações conectadas de runtime. Smoke iniciado por tag de release não carrega esse fixture; nesse caso, painel, PC, simulação e demais etapas do pack continuam sendo verificadas, e os testes que exigem fixture são pulados.

## Alterações incorporadas em 0.5.0

- O commit `a484f14` removeu listas e conjuntos temporários por forma na validação do catálogo de espécies e formas. O Build `42436514507` passou; o smoke `42436621152` passou com o Cobbleverse 1.7.42 e exercitou a prévia em lotes para 1 jogador conectado, além dos testes de painel/PC e cliente sem o JAR. [Execução e logs](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42436621152).
- A otimização reduz alocações transitórias durante a validação, mas não temos uma medição de bytes alocados, MSPT, heap ou TPS para quantificar o ganho. Continua pendente o A/B no host dedicado.
- O commit `78d005e` alterou a prévia de jogadores para percorrer stores carregados por iteradores, sem criar uma lista completa temporária do PC. A análise continua limitada a 12 Pokémon ou 3 ms por tick. `gradlew.bat test remapJar` passou localmente; o Build `42435612164` passou testes, empacotamento e verificação server-only.
- O smoke `42435709615`, com o artefato desse Build, passou no Cobbleverse 1.7.42: 103 JARs no dedicado, 33 client-only omitidos, cliente sem o addon conectado, painel autenticado, PC consultado ao vivo e após reinício, e prévia do painel concluída em lotes para 1 jogador conectado. [Execução e logs](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42435709615).
- A prévia funcional no runner descarta a cópia integral do PC durante a validação, mas não mede ganho de TPS, heap ou MSPT. O A/B de desempenho representativo ainda depende do host dedicado.
- O commit `dd37a65` adicionou `prepare_server_mods.py`, ferramenta que cria uma pasta de mods do dedicado sem modificar a instância cliente. O parser só exclui JARs cujo ambiente principal do Fabric declara `client`; campos de mixin não são confundidos com o ambiente do mod.
- O Build `42433384510`, no commit de correção `f981837`, passou e publicou o artefato com o JAR e os dois utilitários separados. [Execução do Build](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42433384510).
- O Build `42434681831` passou pela nova auditoria automática do JAR: ambiente `server`, só entrypoint dedicado, sem referências às classes Minecraft client, 99 classes/33 mixins e assets web presentes; o arquivo tem 323.654 bytes. [Log do Build](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42434681831).
- O smoke `42433515409` passou com o Cobbleverse 1.7.42: a pasta do dedicado ficou com 103 JARs, omitindo 33 client-only; o cliente Fabric entrou sem o gerenciador. O painel autenticado abriu, encontrou o Pikachu shiny de nível 13 no PC ao vivo e encontrou o mesmo Pokémon no PC salvo após reiniciar. [Execução e logs](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42433515409).
- A execução confirma inicialização e esses fluxos em runner temporário. Não mede redução de boot, heap, MSPT/TPS ou carga representativa no host dedicado.
- O build `42429811203` passou por testes e empacotamento. O artefato contém `cobbleverse-generation-control-0.4.8.jar` e `open-admin-tunnel.ps1` separados. O JAR foi inspecionado: ambiente `server`, somente entrypoint de servidor, 99 classes, nenhuma classe cliente e três arquivos web (`index.html`, CSS e JavaScript). [Execução do Build](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42429811203).
- O smoke test do mesmo código, com o JAR do build `42429072403`, subiu o Cobbleverse 1.7.42 em um servidor dedicado temporário, autenticou no painel, conectou um cliente do pack sem o gerenciador e verificou o PC ao vivo e depois de reiniciar o servidor. O Pokémon de fixture foi encontrado nos dois estados. [Execução e logs](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42429289309).
- Estes resultados validam o pack de referência em runners temporários do GitHub. Não medem acesso ao host dedicado final, MSPT/TPS sob carga representativa, nem desempenho A/B.

## Versão 0.4.8

- O teste do Cobbleverse 1.7.42 iniciou um servidor dedicado com 252 mods e um cliente do pack com 307 mods, sem o JAR do gerenciador. O cliente entrou no servidor.
- A API do PC recusou acesso sem sessão. Após autenticação, encontrou um Pikachu shiny de nível 13 no PC ao vivo; depois que o cliente saiu e o servidor reiniciou, encontrou o mesmo Pokémon no PC salvo, em modo somente leitura. [Execução e logs](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42425118940).
- O mesmo smoke test completo passou com o artefato do Build `42425408887`, sem tag ou release. [Execução](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42425573363).
- O teste confirma esse fluxo no pack de referência. O acesso pelo host dedicado final e o perfil de desempenho com população representativa continuam pendentes.

## Versão 0.4.7

- A suíte inclui um executor de teste que representa a thread do jogo ocupada. O teste verifica que a fila aceita no máximo duas solicitações, descarta operações vencidas sem executá-las depois e volta a aceitar pedidos quando o executor avança.
- Um cliente do Cobbleverse 1.7.42 entrou no servidor dedicado Linux sem o JAR do gerenciador. Login administrativo e leitura de estado também passaram. [Execução do teste](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42424454333).
- O acesso privado e a medição de carga representativa continuam pendentes no host de destino.

## Versão 0.4.6

- A cópia local do pack com 252 mods chegou a `Done` e iniciou o painel com o código desta versão. Na revisão Kanto usada no teste, o datapack gerou 3.275 arquivos sobrepostos e adaptou 1.391 equipes, as mesmas contagens observadas com a 0.4.5.
- O inventário de 147 JARs e ZIPs da cópia encontrou 210 JSONs nos caminhos filtrados em outros namespaces. Esse número mede o que a busca pode deixar de abrir no inventário completo, incluindo datapacks regionais que podem estar desativados.
- No microbenchmark local de normalização de formas, duas execuções por versão mediram **260,5 → 104,5 bytes por chamada** na thread do teste. A mediana de tempo por chamada ficou entre 111,81 e 113,17 ns na 0.4.5 e entre 66,17 e 68,69 ns no candidato. Todas produziram o mesmo checksum. Os resultados cobrem somente essa função em Java 21.

Para repetir o microbenchmark, baixe os dois JARs de [Releases](https://github.com/Kennedhy/Cobbleverse-Server-Admin/releases) e execute no diretório do projeto:

```sh
java -XX:ActiveProcessorCount=2 -Xms64m -Xmx128m -cp cobbleverse-generation-control-0.4.5.jar tools/benchmarks/FormNamesBench.java
java -XX:ActiveProcessorCount=2 -Xms64m -Xmx128m -cp cobbleverse-generation-control-0.4.6.jar tools/benchmarks/FormNamesBench.java
```

Repita invertendo a ordem. A medição de MSPT, heap e GC com população representativa permanece pendente no host dedicado.

## Versão 0.4.5

- Build local do JAR e 36 testes automatizados concluídos. O artefato contém apenas entrypoint de servidor e inclui os arquivos estáticos do painel.
- O pack de teste com 252 mods chegou a `Done` no servidor local isolado. Minecraft e painel escutaram em `127.0.0.1`.
- Em um proxy HTTPS local de teste, com certificado verificado, passaram login, leitura de estado autenticada, logout e a checagem dos atributos do cookie. Um hostname não configurado e alterações com origem errada ou ausente foram recusados. Login e logout por HTTP em loopback também passaram.
- O acesso pelo Tailscale Serve no host de destino, a conexão de um cliente do pack sem o JAR nesta versão e o perfil com carga representativa ainda precisam de validação. A conexão de um cliente sem o JAR foi observada na 0.4.3.

## Versão 0.4.4

- Build `remapJar` a partir do repositório, sem uma instalação local do modpack: concluído.
- Inspeção do JAR: versão 0.4.4, ambiente `server`, somente entrypoint `server`, 91 classes, zero classes cliente; HTML, CSS e JavaScript do painel estão empacotados. Tamanho 303.619 bytes, SHA-256 `4E9DCB7EE9C69642E87C6E5C2197D8E2B3562F6DB23D4EC872D13532545706DD`.
- Boot completo do pack isolado: ainda pendente para 0.4.4.
- Testes automatizados: não executados nesta preparação do release.
- Acesso por SSH a um host dedicado, clientes sem o JAR, comportamento funcional completo e carga representativa: pendentes.

Não instale esta versão no mundo de produção antes de verificar os itens necessários ao seu servidor.

## Evidência anterior — 0.4.3

Em uma cópia local isolada do pack, o JAR 0.4.3 iniciou com 252 mods e mundo de teste próprio. O painel respondeu em loopback, login e `/api/state` autenticados funcionaram, origem externa foi recusada e o arquivo do token recebeu ACL local. Um cliente do pack sem o JAR do gerenciador conectou nessa cópia.

Esse teste não validou host dedicado via SSH, carga real, migração de progresso RCT ou todas as rotas de spawn e captura. O tempo de boot foi uma observação única e não mede o custo incremental do addon.

## Matriz

| Área | Estado | Observação |
| --- | --- | --- |
| Compilação e empacotamento 0.7.0 | Build `43641701925` concluído | Testes automatizados, JAR remapeado e auditoria server-only; assets web presentes |
| Metadados server-only | Verificados no JAR 0.7.0 | 184 classes, 37 mixins e assets web; sem classes client-only |
| Login, dashboard e reinício | Smoke local 0.6.0 observado | Login admin, players, reinício disponível via supervisor; sem campos de monitoramento de servidor |
| Pokémon selvagens | Removido em 1.8.0 | Fight or Flight não está no Cobbleverse 1.8.0; o smoke histórico abaixo é somente para o pack 1.7.x |
| Aparições e recompensas | Smoke local 0.6.0 observado | API RCT e CobbleDollars; mudança de spawn aplicada e restaurada |
| Dificuldade por grupos | Smoke local 0.6.0 observado | Catálogo de 1.559 treinadores/10 categorias, dois grupos, perfil combinado persistido; viewer GET 200 e PUT 403 |
| PC e progressão individual | Build local concluído | Consultas/edições compilam; requer jogador conectado para validar dados reais ao vivo |
| Batalhas PvE/PvP acima do cap | Build local concluído | Hooks compilam; falta batalha real para verificar bloqueio, troca de Pokémon e isenção PvP |
| Gerações, ginásios, XP, níveis e captura | Smoke `42505819843` observado | Ver os resultados históricos do pack no Build 0.5.0 abaixo |
| GTS: GUI, anúncios e retiradas | Smoke local do candidato 0.7.0 conectado | Pokémon com IV/EV e item anunciados/cancelados/retirados; compra fixa, débito CobbleDollars, lances, reembolso, entrega do leilão e pagamento pendente do vendedor exercitados |
| Cliente do pack sem o addon | Smoke `42505819843` conectado em Linux | [Execução do smoke histórico](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42505819843) |
| Acesso privado e desempenho sob carga | Pendente | Requer host dedicado, jogadores e benchmark A/B representativos |
| Tailscale Serve ou túnel SSH no host de destino | Pendente | Requer host acessível pela rede privada |
| TPS, heap e memória sob carga | Pendente | Sem benchmark A/B representativo |

## Limitações conhecidas

- Mods podem criar Pokémon, raids ou capturas por rotas que não usam os eventos e hooks integrados.
- Mudanças de regra não removem Pokémon já capturados, estruturas existentes nem progresso salvo.
- O comportamento de cap RCT depende da versão e do estado de progresso do RCT.
- O modo de geração não cria novos circuitos de ginásio. A disponibilidade de circuitos depende do conteúdo do modpack.
- Ainda falta observar o perfil de desempenho em um host dedicado com atividade e população representativas.
