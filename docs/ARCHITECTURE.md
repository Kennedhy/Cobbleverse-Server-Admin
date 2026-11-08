# Arquitetura e custo

## Limites do projeto

- O Fabric carrega o mod apenas no servidor dedicado (`environment: server`). Não há entrypoint nem interface de configuração no cliente. O Build verifica o JAR remapeado para impedir entrypoints/mixins/classes de cliente e confirmar que os assets web estão incluídos.
- React/Vite geram arquivos estáticos empacotados no JAR. A JVM do servidor não carrega Node, React ou um servidor web externo.
- O painel usa o `HttpServer` do JDK, escuta em loopback e delega a no máximo dois workers com fila limitada. Usa token, sessão, validação de origem e sem CORS.
- As regras e revisões são arquivos no diretório de dados do mundo; não há banco de dados ou serviço externo.

## Caminhos de execução

- Filtragem de geração, regras de XP, limite de nível, captura e integrações de estruturas usam os eventos e hooks específicos do Cobblemon/RCT e dos addons compatíveis.
- O callback de fim de tick retorna imediatamente quando não há validação administrativa em andamento.
- A validação de espécies, equipes e armazenamento só inicia por solicitação e avança em lotes. A leitura de arquivos acontece fora da thread principal; Pokémon já carregados são percorridos por iteradores, sem materializar uma cópia completa do PC na memória.
- As fases incrementais de espécies, equipes, jogadores e consistência compartilham um orçamento cooperativo de até 3 ms por tick, reduzido para 1 ms quando a média recente excede 45 ms/tick.
- A análise de armazenamento processa no máximo 12 Pokémon por jogador e respeita o orçamento compartilhado do tick. Uma execução maior é distribuída por vários ticks. Durante a prévia, stores carregados são observados por eventos de mudança; o addon não mantém uma assinatura por Pokémon nem faz uma segunda varredura completa dos PCs.
- A conferência dos stores alterados e das referências de cache usa lotes limitados por item e por tempo. Esses listeners só existem durante uma prévia administrativa; sem uma validação em andamento, não há varredura periódica de Pokémon.
- A validação de espécies percorre formas e aspectos diretamente, sem criar uma lista e um conjunto temporários para cada forma.
- No modo de limite RCT, o evento de spawn considera jogadores próximos. A consulta termina assim que um jogador permite o nível original. Se todos tiverem um limite inferior, a regra usa o maior deles. O modo global evita a consulta.
- O painel não faz polling contínuo do mundo e não inicia varreduras periódicas de entidades ou chunks.
- A ponte HTTP aceita até dois pedidos pendentes ou em execução na thread do jogo. Um pedido que ainda não começou após três segundos é descartado com HTTP 503. As alterações que já começaram retornam seu resultado normal.
- Salvamento, restauração de revisão e descarte iniciados pelo painel, além de gravação/restauração por comando, compartilham um worker dedicado com fila de dois itens. O arquivo pendente é o ponto de confirmação; a publicação em memória volta à thread do jogo. Diários pequenos permitem recompor operações interrompidas. Ativação e rollback na inicialização continuam síncronos antes da abertura do mundo.
- A validação do painel pode ser cancelada. O worker de leitura é isolado por prévia, tem fila limitada, interrupção cooperativa e prazo total de dez minutos. Se um store carregado mudar, somente esse store é relido e sua contribuição é recomposta com snapshots dos demais stores do jogador, preservando a deduplicação. O snapshot transitório é limitado a 100 mil entradas no total e 10 mil por jogador, liberado ao fim da prévia; acima dos limites, o servidor faz a releitura completa daquele jogador. São aceitas até oito reavaliações por jogador e 64 por prévia. Conexões ou mudanças em arquivos offline ainda invalidam as contagens para preservar a consistência.
- O painel mostra a fase, tempo decorrido e progresso de espécies, jogadores/equipes durante uma validação; também consulta atividade, fila e recusas acumuladas da persistência, além das validações recusadas. A página Diagnóstico coleta MSPT, heap, TPS estimado, CPU do processo Java (se suportada) e tempo de GC entre amostras sob demanda a cada 15 segundos, apenas enquanto está aberta; mantém até 120 amostras/30 minutos em memória, sem listener por tick nem gravação em disco.
- XP, limites de nível/selvagens e captura ficam em um snapshot imutável de configurações de runtime, separado das regras de geração/datapack. Após salvar e validar, um administrador pode trocar esse snapshot na thread do jogo; os hooks de XP, spawn e captura leem a referência atual sem varredura, polling ou tarefa por tick. O multiplicador de captura só é aplicado quando o calculador padrão do Cobblemon está ativo. Descartar a revisão pendente restaura o snapshot da revisão ativa.
- A inspeção do PC é somente leitura e sob demanda. A busca usa cursor, páginas de 40 jogadores e um índice/cache temporário limitado a 200 mil UUIDs e 100 mil nomes, renovado por mudança de arquivo ou após 30 segundos. A consulta carrega uma caixa por vez; natureza, habilidade, golpes, IVs e EVs são extraídos somente do Pokémon selecionado.
- O histórico expõe até 50 alterações recentes e calcula as diferenças ao abrir uma revisão. A restauração grava uma nova revisão pendente. Credenciais individuais têm papéis de administrador ou consulta, são comparadas em tempo constante e podem ser revogadas sem reiniciar. A revogação encerra as sessões daquela conta. Tokens compartilhados iniciais podem ser desativados por marcador persistente. Revisões do painel registram `web:<identificador>`; comandos registram o nome do jogador ou console.
- O datapack de regras mantém a busca ampla para espécies e spawns, que podem vir de outros namespaces. Para equipes, circuitos, raids e estruturas de ginásio, lê apenas os namespaces que pode modificar. Funções e estruturas de ginásio só são examinadas quando o filtro está ligado.

## Dependências e otimização

### Referência CobblemonExtras

O projeto [CobblemonExtras](https://github.com/chasem-dev/CobblemonExtras) descreve comandos server-side para consultar PCs e equipes; o site também apresenta um Showcase de PC no navegador. Este addon já oferece a inspeção somente leitura dentro do painel administrativo privado, então adicionar CobblemonExtras ou encaminhar os dados para o Showcase duplicaria a função e aumentaria a dependência operacional. O repositório informa licença MPL-2.0; nenhum código dele foi incorporado. Ele permanece uma referência de funcionalidades para comandos futuros.

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
