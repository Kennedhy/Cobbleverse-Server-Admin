# Arquitetura e custo

## Limites do projeto

- O Fabric carrega o mod apenas no servidor dedicado (`environment: server`). Não há entrypoint nem interface de configuração no cliente. O Build verifica o JAR remapeado para impedir entrypoints/mixins/classes de cliente e confirmar que os assets web estão incluídos.
- React/Vite geram arquivos estáticos empacotados no JAR. A JVM do servidor não carrega Node, React ou um servidor web externo.
- O painel usa o `HttpServer` do JDK, escuta em loopback e delega a no máximo dois workers com fila limitada. Usa token, sessão, validação de origem e sem CORS.
- As regras e revisões são arquivos no diretório de dados do mundo; não há banco de dados ou serviço externo.

## Caminhos de execução

- Filtragem de geração, regras de XP, limite de nível, captura e integrações de estruturas usam os eventos e hooks específicos do Cobblemon/RCT e dos addons compatíveis.
- O callback de fim de tick retorna imediatamente quando não há validação administrativa em andamento.
- A validação de equipes/armazenamento só inicia por solicitação e avança em lotes. A leitura de arquivos acontece fora da thread principal; Pokémon já carregados são percorridos por iteradores, sem materializar uma cópia completa do PC na memória.
- A análise de armazenamento processa no máximo 12 Pokémon ou 3 ms por jogador em cada tick, o que ocorrer primeiro. Uma execução maior é distribuída por vários ticks.
- A validação de espécies percorre formas e aspectos diretamente, sem criar uma lista e um conjunto temporários para cada forma.
- No modo de limite RCT, o evento de spawn considera jogadores próximos. A consulta termina assim que um jogador permite o nível original. Se todos tiverem um limite inferior, a regra usa o maior deles. O modo global evita a consulta.
- O painel não faz polling contínuo do mundo e não inicia varreduras periódicas de entidades ou chunks.
- A ponte HTTP aceita até dois pedidos pendentes ou em execução na thread do jogo. Um pedido que ainda não começou após três segundos é descartado com HTTP 503. As alterações que já começaram retornam seu resultado normal.
- O datapack de regras mantém a busca ampla para espécies e spawns, que podem vir de outros namespaces. Para equipes, circuitos, raids e estruturas de ginásio, lê apenas os namespaces que pode modificar. Funções e estruturas de ginásio só são examinadas quando o filtro está ligado.

## Dependências e otimização

O build obtém versões exatas de Cobblemon, RCT e integrações via IDs imutáveis do Maven do Modrinth. Os JARs de terceiros não são incorporados ao artefato do projeto nem ao repositório.

O inventário da instância local do modpack (137 mods) encontrou estes otimizadores: Lithium `0.15.4`, FerriteCore `7.0.3`, Krypton `0.2.8`, ModernFix `5.25.1`, C2ME `0.4.0-alpha.0.23` e ScalableLux `0.1.0.1`. Este addon não instala duplicatas nem modifica suas configurações. O inventário não encontrou Spark nem ServerCore. As versões e presença de mods podem diferir da máquina dedicada.

O índice Modrinth 1.7.42 lista 135 JARs de mod como disponíveis no servidor. No inventário local, 33 desses JARs declaram `environment: client` no nível principal (24,97 MiB); Fabric não os carrega em um dedicado. `tools/prepare_server_mods.py` os exclui de uma nova cópia sem tocar na instalação do cliente. O resultado local medido foi de 103 JARs porque manteve um mod extra instalado fora do índice; o pack indexado sozinho resulta em 102. Isso reduz 24,97 MiB e o número de JARs a inspecionar, mas não mede o tempo total de boot nem prova redução de heap/MSPT/TPS.

- [Lithium](https://github.com/CaffeineMC/lithium) otimiza a lógica do jogo. [FerriteCore](https://github.com/malte0811/FerriteCore) reduz o uso de memória. Preserve as versões do pack como referência para a medição.
- O pack já inclui otimizadores de lógica, memória, rede, carregamento de chunks e iluminação; não acrescente outro otimizador por suposição. A instância local não inclui `spark`: use [Spark 1.10.97 para Fabric 1.21.1](https://modrinth.com/mod/spark/version/1.10.97-fabric) em uma cópia de teste para medir MSPT, TPS, CPU, heap e pausas de GC. Essa versão roda no servidor sem precisar ser instalada nos clientes; Spark é diagnóstico separado, não dependência nem código incorporado neste addon.
- [ServerCore 1.5.19+1.21.1](https://modrinth.com/mod/servercore/version/1.5.19%2B1.21.1) é um candidato server-side se um perfil mostrar custo em entidades ou chunks. Os padrões atuais incluem otimizações como redução de carregamentos síncronos e cache dos chunks que estão sendo tickados, enquanto ativação de entidades e ajustes dinâmicos ficam desligados; consulte a [configuração padrão upstream](https://github.com/Wesley1808/ServerCore/blob/main/docs/config/DEFAULT.md). A página da [versão 1.5.10 para 1.21.1](https://modrinth.com/mod/servercore/version/1.5.10%2B1.21.1) registra uma correção específica para Pokémon do Cobblemon que demoravam mais a desaparecer quando a ativação de entidades era ligada. Portanto, mantenha essa opção desligada até validar spawn, permanência e batalha numa cópia do pack. ServerCore ainda não foi instalado nem validado com este modpack. Para redstone, considere [Alternate Current](https://modrinth.com/mod/alternate-current) apenas se o perfil identificar circuitos como gargalo; ele altera a implementação de redstone.
- C2ME já está no pack em uma versão alpha. Não troque versão nem habilite opções adicionais sem perfil comparável e verificação do worldgen customizado.
- Se a exploração de terreno novo concentrar os picos, teste o [Chunky para Fabric 1.21.1](https://modrinth.com/plugin/chunky?g=1.21.1&l=fabric) em uma cópia do mundo. Ele permite gerar uma região limitada durante manutenção, depois de fixar as regras de gerações e ginásios. Compare o tempo de tick ao explorar a região pré-gerada e outra equivalente sem pré-geração. A [documentação dos comandos](https://github.com/pop4959/Chunky/wiki/Commands) cobre seleção, progresso e pausa.

## Como avaliar o custo

Siga o [procedimento repetível de perfil](PERFORMANCE.md) antes de adicionar otimizadores ou mudar configurações. Ele compara o pack base, o custo fixo do addon e as regras pretendidas com Spark no mesmo host.

Para repetir posições de jogadores em uma cópia de teste, avalie o comando `/player` do [Fabric Carpet](https://github.com/gnembon/fabric-carpet). Use a mesma versão e configuração do Carpet nas duas rodadas. Use clientes do modpack para validar carga de rede, batalhas e captura.

O callback leve e a validação sob demanda limitam trabalho contínuo por desenho, mas isso não substitui a medição em servidor real. Veja também a [matriz de validação](TESTING.md).
