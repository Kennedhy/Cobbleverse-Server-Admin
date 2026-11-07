# Arquitetura e custo

## Limites do projeto

- O Fabric carrega o mod apenas no servidor dedicado (`environment: server`). Não há entrypoint nem interface de configuração no cliente.
- React/Vite geram arquivos estáticos empacotados no JAR. A JVM do servidor não carrega Node, React ou um servidor web externo.
- O painel usa o `HttpServer` do JDK, escuta em loopback e delega a no máximo dois workers com fila limitada. Usa token, sessão, validação de origem e sem CORS.
- As regras e revisões são arquivos no diretório de dados do mundo; não há banco de dados ou serviço externo.

## Caminhos de execução

- Filtragem de geração, regras de XP, limite de nível, captura e integrações de estruturas usam os eventos e hooks específicos do Cobblemon/RCT e dos addons compatíveis.
- O callback de fim de tick retorna imediatamente quando não há validação administrativa em andamento.
- A validação de equipes/armazenamento só inicia por solicitação e avança em lotes. A leitura de arquivos acontece fora da thread principal.
- No modo de limite RCT, o evento de spawn considera jogadores próximos. A consulta termina assim que um jogador permite o nível original. Se todos tiverem um limite inferior, a regra usa o maior deles. O modo global evita a consulta.
- O painel não faz polling contínuo do mundo e não inicia varreduras periódicas de entidades ou chunks.
- O datapack de regras mantém a busca ampla para espécies e spawns, que podem vir de outros namespaces. Para equipes, circuitos, raids e estruturas de ginásio, lê apenas os namespaces que pode modificar. Funções e estruturas de ginásio só são examinadas quando o filtro está ligado.

## Dependências e otimização

O build obtém versões exatas de Cobblemon, RCT e integrações via IDs imutáveis do Maven do Modrinth. Os JARs de terceiros não são incorporados ao artefato do projeto nem ao repositório.

O modpack de referência já inclui Lithium, FerriteCore, Krypton, ModernFix, C2ME e ScalableLux. Este addon não instala duplicatas nem modifica suas configurações. Alterar versões ou opções de C2ME sem perfil comparável pode afetar worldgen customizado.

- [Lithium](https://github.com/CaffeineMC/lithium) otimiza a lógica do jogo. [FerriteCore](https://github.com/malte0811/FerriteCore) reduz o uso de memória. Preserve as versões do pack como referência para a medição.
- Se a exploração de terreno novo concentrar os picos, teste o [Chunky para Fabric 1.21.1](https://modrinth.com/plugin/chunky?g=1.21.1&l=fabric) em uma cópia do mundo. Ele permite gerar uma região limitada durante manutenção, depois de fixar as regras de gerações e ginásios. Compare o tempo de tick ao explorar a região pré-gerada e outra equivalente sem pré-geração. A [documentação dos comandos](https://github.com/pop4959/Chunky/wiki/Commands) cobre seleção, progresso e pausa.

## Como avaliar o custo

Para isolar o custo do addon, mantenha o mesmo host, pack, cópia do mundo, distâncias de visão e simulação e número de jogadores. Faça uma rodada com o addon ausente e outra com ele ativo. Após o aquecimento, repita períodos equivalentes em repouso e exploração. Faça uma rodada adicional com validação do painel ativa para medir o custo dessa operação. Capture MSPT, TPS, heap e pausas do GC com [spark](https://spark.lucko.me/docs/Command-Usage) ou JFR. Compare p95 e p99 dos ticks e memória do processo. Se o perfil mostrar tempo alto em chunks ou simulação, mude uma distância em `server.properties` por rodada e repita a medição.

Para repetir posições de jogadores em uma cópia de teste, avalie o comando `/player` do [Fabric Carpet](https://github.com/gnembon/fabric-carpet). Use a mesma versão e configuração do Carpet nas duas rodadas. Use clientes do modpack para validar carga de rede, batalhas e captura.

O callback leve e a validação sob demanda limitam trabalho contínuo por desenho, mas isso não substitui a medição em servidor real. Veja também a [matriz de validação](TESTING.md).
