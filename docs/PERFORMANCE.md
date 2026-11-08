# Perfil de desempenho do servidor dedicado

Este procedimento mede o custo do modpack e do gerenciador sem confundir diferenças de máquina, mundo ou carga. Faça as medições em uma cópia descartável do servidor, nunca no mundo principal.

## Preparação

1. Instale o [Spark para Fabric 1.21.1](https://modrinth.com/mod/spark/versions) apenas na cópia do servidor. Os jogadores não precisam instalar Spark.
2. Registre versão do modpack, Java, argumentos da JVM, RAM, `server.properties`, mods ativos e configuração do gerenciador.
3. Prepare a pasta `mods` do dedicado com `tools/prepare-server-mods.py` e use a mesma pasta filtrada nos quatro cenários. Faça uma cópia do mesmo mundo pré-gerado para cada rodada. Use a mesma quantidade de jogadores, os mesmos clientes, posições e percurso. Não explore chunks novos em somente uma das rodadas.
4. Deixe os jogadores e o mundo aquecerem por cinco minutos antes de cada medição. Repita cada cenário três vezes.

## Cenários

- **A — pack base:** gerenciador ausente.
- **B — custo fixo:** gerenciador instalado, filtros e controles que mudam o jogo desativados.
- **C — configuração pretendida:** regras que serão usadas no servidor ligadas.
- **D — ação administrativa:** repita C e execute uma validação pelo painel durante o perfil. Registre separadamente quanto tempo ela leva e o impacto nos ticks; essa validação é trabalho sob demanda, não custo contínuo.

Spark deve permanecer instalado nos quatro cenários. A compara o pack sem o addon; B estima seu custo fixo; C mede o custo real das regras junto com o comportamento de jogo que elas alteram; D mede uma ação administrativa isolada. Não compare um mundo já explorado com outro que ainda esteja gerando terreno.

## Comandos

Execute pelo console do servidor ou como operador no jogo. No console omita a barra inicial; no jogo use `/`.

Antes e depois de cada rodada, registre:

```text
spark tps
spark health show --memory
spark gc
```

Para amostrar o thread principal durante 15 minutos:

```text
spark profiler start
```

Repita o percurso definido por 15 minutos e depois salve o perfil localmente:

```text
spark profiler stop --comment "C, rodada 1" --save-to-file
```

Para investigar alocações e pressão de memória, faça outra rodada separada:

```text
spark profiler start --alloc
```

Repita o mesmo cenário por 15 minutos e salve a captura de alocações localmente:

```text
spark profiler stop --comment "C, alocacoes, rodada 1" --save-to-file
```

Guarde a saída e o arquivo gerado junto com os dados daquela rodada. O modo de alocação substitui o perfil de CPU; não execute os dois perfis simultaneamente. O Spark documenta `profiler start`, `--alloc`, `profiler stop --save-to-file`, `health show --memory`, `tps` e `gc` na [referência de comandos](https://spark.lucko.me/docs/Command-Usage).

## Comparação e mudanças

Compare mediana (p50), p95 e MSPT máximo, TPS, CPU, heap e pausas/tempo de GC nas três repetições. Spark apresenta a mediana e o percentil 95 no comando `spark tps`, conforme sua [documentação de TPS/MSPT](https://spark.lucko.me/docs/guides/TPS-and-MSPT). Leia o perfil para encontrar os métodos que ocupam os ticks mais lentos. Se o tempo estiver em chunks ou geração de terreno, investigue primeiro a configuração existente do C2ME e teste exploração pré-gerada; se estiver em entidades, avalie um candidato como ServerCore em outra cópia. Para cada candidato, mude uma única variável, repita A/B/C e rode o teste de spawn, batalha e captura do Cobblemon.

Não instale ServerCore, Alternate Current, não altere distâncias e não atualize o C2ME só porque são mods ou opções de otimização. Cada mudança pode afetar o comportamento do mundo; mantenha-a somente se as rodadas comparáveis mostrarem ganho sem regressões funcionais. Perfis e logs podem conter nomes de jogadores: revise-os antes de compartilhar.

## Limite da evidência atual

O smoke test automatizado confirma boot, painel, conexão de cliente e consulta ao PC em um runner temporário. Ele não simula população/carga representativa e não substitui este A/B no host dedicado. Até esse host estar acessível, não há resultado de TPS/MSPT ou ganho de desempenho a declarar.
