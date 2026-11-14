# Estado de validação

O projeto é um preview para o modpack Cobbleverse de Minecraft 1.21.1. Este documento separa o que foi observado do que ainda precisa de servidor e jogadores reais.

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

## Ataques de Pokémon selvagens — smoke local (2026-11-14)

- `npm run build` e `gradlew.bat remapJar` passaram com a nova página e a API de combate selvagem. O JAR server-only contém 175 classes, 36 mixins e 709.143 bytes; os assets web estão incluídos. SHA-256: `CA531334768125896D6B0943E1465F6CB328D226A173AB07F0241390DC7693EE`.
- Uma cópia descartável do Cobbleverse iniciou em `build/pack-smoke-gts-runtime/server`, Minecraft `127.0.0.1:25593`, painel `127.0.0.1:18771`; o modpack carregou Fight or Flight. A página e o bundle foram servidos pelo painel (HTTP 200), e a rota `/api/wild-combat` leu a configuração real `fightorflight.json5`.
- Com a credencial admin, a API salvou o dano máximo corpo a corpo de 5 para 5,5 e, numa segunda alteração, o nível mínimo de 15 para 16, a opção de pouca luz e o limiar neutro de 25 para 30. As releituras confirmaram cada valor e `restartRequired=true`. A comparação do arquivo antes/depois confirmou que cada gravação só alterou as chaves selecionadas.
- A credencial viewer consultou a página, mas recebeu 403 ao tentar salvar; uma revisão desatualizada foi recusada com 409. A configuração, as propriedades do servidor, o JAR e o fixture da cópia foram restaurados após o smoke; as portas temporárias foram liberadas. A instância original do launcher não foi modificada.
- O smoke confirma o gerenciamento e a persistência dos controles de Fight or Flight, não uma batalha com jogador conectado. A agressão e o dano efetivos em jogo ainda precisam ser observados com um jogador no mundo; não houve benchmark de TPS, MSPT, heap ou GC.

## GTS e leilões — verificação atual (2026-11-14)

- A revisão de performance adicionou índices de grupos/perfis RCT para o caminho de início de batalha, cache limitado das distribuições de raid, evitou criar propriedades Primal quando o Pokémon não tem orbe relevante e trocou a busca aninhada de marcadores no inventário. `gradlew.bat --no-daemon --max-workers=1 test remapJar remapVerificationJar` passou com **66 testes, 0 falhas, 0 erros e 0 ignorados**; a etapa web ficou corretamente `UP-TO-DATE`. `git diff --check` passou, com avisos de conversão normal de LF/CRLF nos arquivos editados.
- `tools/verify_server_jar.py` confirmou o JAR server-only atual: 172 classes, 36 mixins, 696.831 bytes e assets web. SHA-256: `8A551843B3B49B468504BCCC7A89BF44CE11A4BC5B5914FA07159C1FDD88FCAF`. O artefato inclui SGui 1.6.1+1.21.1 como biblioteca aninhada e o texto LGPL-3.0 correspondente; o fixture de teste não está no JAR de produção.
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
| Compilação e empacotamento 0.6.0 | Build `43489280795` concluído | Testes automatizados, JAR remapeado e auditoria server-only; o JAR publicado também foi baixado e verificado |
| Metadados server-only | Verificados no JAR 0.6.0 | 150 classes, 35 mixins e assets web; sem classes cliente |
| Login, dashboard e reinício | Smoke local 0.6.0 observado | Login admin, players, reinício disponível via supervisor; sem campos de monitoramento de servidor |
| Pokémon selvagens | Smoke API local 2026-11-14 | Leitura/gravação de danos e agressividade, persistência, viewer 403 e revisão vencida 409; sem jogador em combate |
| Aparições e recompensas | Smoke local 0.6.0 observado | API RCT e CobbleDollars; mudança de spawn aplicada e restaurada |
| Dificuldade por grupos | Smoke local 0.6.0 observado | Catálogo de 1.559 treinadores/10 categorias, dois grupos, perfil combinado persistido; viewer GET 200 e PUT 403 |
| PC e progressão individual | Build local concluído | Consultas/edições compilam; requer jogador conectado para validar dados reais ao vivo |
| Batalhas PvE/PvP acima do cap | Build local concluído | Hooks compilam; falta batalha real para verificar bloqueio, troca de Pokémon e isenção PvP |
| Gerações, ginásios, XP, níveis e captura | Smoke `42505819843` observado | Ver os resultados históricos do pack no Build 0.5.0 abaixo |
| GTS: GUI, anúncios e retiradas | Smoke local 0.6.0 conectado | Pokémon com IV/EV e item anunciados/cancelados/retirados; compra fixa, débito CobbleDollars, lances, reembolso, entrega do leilão e pagamento pendente do vendedor exercitados |
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
