# Estado de validação

O projeto é um preview para o modpack Cobbleverse de Minecraft 1.21.1. Este documento separa o que foi observado do que ainda precisa de servidor e jogadores reais.

## Candidato 0.6.0 — build e smoke local

- `gradlew.bat --no-daemon --max-workers=1 remapJar` e `npm run build` passaram localmente. O verificador server-only confirmou o JAR `0.6.0` com 150 classes, 35 mixins, assets web e 499.861 bytes. A suíte `gradlew test` não foi executada nesta preparação; o workflow Build a executará após o push.
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
| Compilação e empacotamento 0.6.0 | Build local concluído | `compileJava`, `remapJar`, `npm run build` e auditoria server-only; workflow Build ainda executará a suíte automatizada |
| Metadados server-only | Verificados no JAR 0.6.0 | 150 classes, 35 mixins e assets web; sem classes cliente |
| Login, dashboard e reinício | Smoke local 0.6.0 observado | Login admin, players, reinício disponível via supervisor; sem campos de monitoramento de servidor |
| Aparições e recompensas | Smoke local 0.6.0 observado | API RCT e CobbleDollars; mudança de spawn aplicada e restaurada |
| Dificuldade por grupos | Smoke local 0.6.0 observado | Catálogo de 1.559 treinadores/10 categorias, dois grupos, perfil combinado persistido; viewer GET 200 e PUT 403 |
| PC e progressão individual | Build local concluído | Consultas/edições compilam; requer jogador conectado para validar dados reais ao vivo |
| Batalhas PvE/PvP acima do cap | Build local concluído | Hooks compilam; falta batalha real para verificar bloqueio, troca de Pokémon e isenção PvP |
| Gerações, ginásios, XP, níveis e captura | Smoke `42505819843` observado | Ver os resultados históricos do pack no Build 0.5.0 abaixo |
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
