# Estado de validação

O projeto é um preview para o modpack Cobbleverse de Minecraft 1.21.1. Este documento separa o que foi observado do que ainda precisa de servidor e jogadores reais.

## Build 0.5.0

O Build [42496572379](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42496572379) passou no commit `1aac404`: testes, JAR remapeado, auditoria server-only e pacote web identificado como 0.5.0. A versão inclui aplicação ao vivo seletiva de XP/limites/captura, reavaliação de store alterado durante a prévia e métricas sob demanda de TPS estimado, CPU Java e GC. A deduplicação entre party, PC e reserva é mantida com snapshots temporários limitados. O JAR da release tem o mesmo SHA-256 do artefato testado: `0b7ba142b8ddbc72d6decfba63d220441c1487934b77a49fde1b9385d8772999`.

O smoke [42496723137](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42496723137) passou com o Cobbleverse 1.7.42: instalou 168 arquivos do pack, filtrou o dedicado para 103 JARs (33 client-only omitidos), conectou um cliente sem o addon, autenticou no painel e consultou métricas. O servidor preparou a política Kanto com 3.237 arquivos sobrepostos e 1.391 equipes adaptadas. A simulação confirmou bloqueio de Johto no modo de geração única, multiplicadores de grupo/jogador e cap global. O PC foi lido ao vivo e após reinício; a validação reavaliou o store alterado; XP, limites e captura foram aplicados e descartados ao vivo. Ainda não há medição A/B em host dedicado.

O próximo smoke também compila um fixture Fabric separado para conexão real, sem incluir suas classes no JAR distribuído. Com um jogador conectado, ele amostra o cálculo de captura de Poké Ball e Ultra Ball em 1×/2× e verifica chance zero e Master Ball garantida. Em uma segunda inicialização, dois jogadores ficam próximos para verificar o maior cap RCT, depois um jogador distante, bloqueio, nível inalterado e cap global no evento de spawn do Cobblemon. O fixture injeta somente progresso/caps RCT de teste; não mede um spawn natural nem fluxo de batalha.

## Teste do pack no GitHub

O workflow manual **Cobbleverse runtime smoke** baixa o Cobbleverse 1.7.42 do Modrinth, verifica os arquivos e inicia um servidor e um cliente Fabric em uma máquina temporária do GitHub. O JAR informado no formulário é instalado apenas no servidor. O teste usa um mundo descartável e credenciais locais. A execução `42496723137` verifica simulação de gerações/XP/cap, PC ao vivo/salvo, métricas, revalidação de store alterado e aplicação/descarte seletivos ao vivo.

Para testar mudanças ainda sem release, informe em `build_run` o ID de uma execução bem-sucedida do workflow **Build** na branch `main`. Com esse campo preenchido, o teste baixa o mod e o fixture da mesma execução. Smoke iniciado por tag de release não carrega o fixture conectado. O build `42429072403` já inclui o JAR e o helper SSH para Windows como arquivos separados.

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
| Compilação e empacotamento | Build `42496572379` concluído | Testes, JAR remapeado e auditoria server-only |
| Metadados server-only | Verificados no Build `42496572379` | `environment: server`, só entrypoint dedicado; cliente entrou sem o JAR no smoke `42496723137` |
| Painel HTTP, token e sessão | Smoke `42496723137` observado | Login autenticado, estado e métricas no servidor temporário |
| Regras de geração/XP/cap | Smoke `42496723137` observado | Simulação ao vivo de espécie, grupo/jogador e cap global |
| PC dos jogadores | Smoke `42496723137` observado no pack | Consulta autenticada ao PC ao vivo e salvo após reinício |
| Prévia de armazenamento | Smoke `42496723137` observado no pack | Reavaliou o store mudado durante a validação, preservando os demais |
| Cliente do pack sem o addon | Smoke `42496723137` conectado em Linux | [Execução do smoke mais recente](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42496723137) |
| Aplicação seletiva de XP, níveis e captura | Smoke `42496723137` observado | Estado aplicado ao vivo e restaurado ao descartar revisão pendente |
| Regras de espécie/geração, XP, captura, RCT e addons | Parcial | Regras puras e controles do painel cobertos; rotas de gameplay e addons variam por evento/versão |
| Tailscale Serve ou túnel SSH no host de destino | Pendente | Requer host acessível pela rede privada |
| TPS, heap e memória sob carga | Pendente | Sem benchmark A/B representativo |

## Limitações conhecidas

- Mods podem criar Pokémon, raids ou capturas por rotas que não usam os eventos e hooks integrados.
- Mudanças de regra não removem Pokémon já capturados, estruturas existentes nem progresso salvo.
- O comportamento de cap RCT depende da versão e do estado de progresso do RCT.
- O modo de geração não cria novos circuitos de ginásio. A disponibilidade de circuitos depende do conteúdo do modpack.
- Ainda falta observar o perfil de desempenho em um host dedicado com atividade e população representativas.
