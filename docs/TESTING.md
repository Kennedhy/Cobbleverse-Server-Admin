# Estado de validação

O projeto é um preview para o modpack Cobbleverse de Minecraft 1.21.1. Este documento separa o que foi observado do que ainda precisa de servidor e jogadores reais.

## Teste do pack no GitHub

O workflow manual **Cobbleverse runtime smoke** baixa o Cobbleverse 1.7.42 do Modrinth, verifica os arquivos e inicia um servidor e um cliente Fabric em uma máquina temporária do GitHub. O JAR informado no formulário é instalado apenas no servidor. O teste usa um mundo descartável e credenciais locais. A partir da 0.4.8, o teste também consulta o PC em memória e o arquivo salvo após reiniciar.

Para testar mudanças ainda sem release, informe em `build_run` o ID de uma execução bem-sucedida do workflow **Build** na branch `main`. Com esse campo preenchido, o teste usa o artefato da compilação.

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
| Compilação e empacotamento | 0.4.8 concluído | Build sem `packDir` local; artefato sem release usado no smoke test completo |
| Metadados server-only | 0.4.8 verificados | `environment: server`, apenas entrypoint de servidor; cliente do pack entrou sem o JAR |
| Painel HTTP, token e sessão | 0.4.5 observado localmente | Loopback e proxy de teste com TLS |
| PC dos jogadores | 0.4.8 observado no pack | Consulta autenticada ao PC ao vivo e salvo após reinício |
| Cliente do pack sem o addon | 0.4.8 conectado em Linux | [Execução do teste](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/runs/42425118940) |
| Spawn, XP, captura, RCT e addons | Parcial | Cobertura varia por evento e versão |
| Tailscale Serve ou túnel SSH no host de destino | Pendente | Requer host acessível pela rede privada |
| TPS, heap e memória sob carga | Pendente | Sem benchmark A/B representativo |

## Limitações conhecidas

- Mods podem criar Pokémon, raids ou capturas por rotas que não usam os eventos e hooks integrados.
- Mudanças de regra não removem Pokémon já capturados, estruturas existentes nem progresso salvo.
- O comportamento de cap RCT depende da versão e do estado de progresso do RCT.
- O modo de geração não cria novos circuitos de ginásio. A disponibilidade de circuitos depende do conteúdo do modpack.
- Ainda falta observar o perfil de desempenho em um host dedicado com atividade e população representativas.
