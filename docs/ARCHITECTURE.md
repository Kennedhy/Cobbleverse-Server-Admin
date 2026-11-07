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
- No modo de limite RCT, o evento de spawn considera jogadores próximos e escolhe o maior limite aplicável. O modo global evita essa consulta.
- O painel não faz polling contínuo do mundo e não inicia varreduras periódicas de entidades ou chunks.

## Dependências e otimização

O build obtém versões exatas de Cobblemon, RCT e integrações via IDs imutáveis do Maven do Modrinth. Os JARs de terceiros não são incorporados ao artefato do projeto nem ao repositório.

O modpack de referência já inclui Lithium, FerriteCore, Krypton, ModernFix, C2ME e ScalableLux. Este addon não instala duplicatas nem modifica suas configurações. Alterar versões ou opções de C2ME sem perfil comparável pode afetar worldgen customizado.

## Como avaliar o custo

Não existe um benchmark A/B suficiente para atribuir melhoria ou regressão de TPS, heap ou memória total a este addon. Para uma comparação, mantenha o mesmo host, pack, mundo, view/simulation distance e população. Capture períodos equivalentes em repouso, exploração e validação com Spark ou JFR; compare p95/p99 dos ticks, pausas de GC, heap e memória do processo.

O callback leve e a validação sob demanda limitam trabalho contínuo por desenho, mas isso não substitui a medição em servidor real. Veja também a [matriz de validação](TESTING.md).
