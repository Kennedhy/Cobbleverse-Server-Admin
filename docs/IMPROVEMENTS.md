# Melhorias e estado de implementação

Revisado em 08/11/2026. As mudanças abaixo estão no `main` no commit `ff38354`; nenhuma nova release foi criada. Ainda não foram medidas em um host dedicado.

## Implementado neste ciclo

- **Prévia e validação:** espécies, equipes, jogadores e consistência avançam sob orçamento cooperativo compartilhado de até 3 ms por tick (1 ms quando a média recente passa de 45 ms/tick). A leitura de arquivo usa um executor isolado, fila limitada, interrupção cooperativa e prazo de dez minutos. O painel mostra fase/progresso e permite cancelar. Se um store carregado mudar, a prévia reavalia apenas esse store e recompõe as contagens com snapshots dos outros stores, preservando a deduplicação entre party, PC e reserva. Snapshots temporários ficam limitados a 100 mil entradas e 10 mil por jogador e são liberados ao terminar; acima desses limites, a prévia volta à leitura completa daquele jogador. São aceitas até 64 reavaliações de store e 8 por jogador. Alterações em arquivos offline ou entradas/saídas de jogadores continuam invalidando as contagens, em vez de exibir um resultado parcial. A validade das regras e equipes continua separada dessas contagens.
- **Persistência web:** salvamento, restauração de revisão e descarte usam uma fila exclusiva, com um worker e dois itens de espera. A escrita acontece fora da thread do jogo; a publicação em memória volta à thread do servidor. Diários de salvamento e descarte permitem concluir operações interrompidas na inicialização.
- **Simulador:** a página de XP usa o mesmo cálculo de multiplicadores e limite do runtime. Agora explica a geração/forma, valida a forma da espécie, mostra a origem de XP e do limite e não cria entidades.
- **Atribuição de XP:** a página pesquisa jogadores offline por nome ou UUID usando o índice existente do PC e pagina os resultados quando necessário. A chamada é explícita, para não reindexar nem pesquisar a cada tecla; um UUID manual continua disponível para jogadores sem dados salvos.
- **PC dos jogadores:** consulta somente leitura com busca paginada por cursor. Um índice temporário de até 200 mil UUIDs e um cache de até 100 mil nomes são reutilizados por 30 segundos; cada consulta de caixa carrega uma caixa por vez. Ao selecionar um Pokémon, mostra natureza, habilidade, golpes, IVs e EVs, além do nível, forma e shiny.
- **Histórico:** nova página lista até 50 revisões, compara os campos alterados e prepara uma revisão antiga como uma nova revisão pendente.
- **Presets e arquivos:** presets rápidos, presets nomeados por mundo (até 20), importação e exportação JSON de até 32 KiB. A importação e o carregamento de um preset salvo validam o schema, mostram uma comparação e alteram apenas o rascunho.
- **Acessos individuais e auditoria:** a página Acessos cria tokens de administrador ou consulta por pessoa, lista credenciais sem revelar seus tokens e revoga-as sem reiniciar. A revogação encerra as sessões ativas. É possível desativar tokens compartilhados e impedir sua recriação; o último administrador não pode ser removido. Revisões feitas pelo painel registram `web:<identificador>` no histórico e a interface mostra quem está autenticado. O comando também permite criar/revogar contas quando a interface estiver desligada.
- **Diagnóstico do runtime:** ao abrir Diagnóstico, o painel coleta MSPT, heap, TPS estimado pelo MSPT, CPU do processo Java quando disponível e tempo de GC entre amostras a cada 15 segundos. A série fica limitada a 120 pontos/30 minutos em memória, sem gravação em disco ou listener por tick; a coleta para quando a página fecha e reinicia com o servidor.
- **Aplicação seletiva ao vivo:** após validar e salvar, o administrador pode aplicar XP, limites de nível/selvagens e captura sem reiniciar. O snapshot imutável desses ajustes fica separado das regras de geração e mundo; os hooks consultam a referência atual sem trabalho por tick. O painel recusa uma troca incompatível com um calculador de captura personalizado do Cobblemon, antes de aplicar qualquer campo. Descartar a revisão pendente restaura os ajustes de gameplay da revisão ativa.

## Limites ainda abertos

1. Painel e comandos de edição/restauração gravam em segundo plano. Ativação e rollback durante a inicialização continuam síncronos porque ocorrem antes de abrir o mundo para jogo.
2. O snapshot limitado de stores acelera alterações carregadas; jogadores que excedam 10 mil entradas ou o orçamento de 100 mil usam uma releitura completa dos próprios stores. Mudanças em arquivos offline continuam invalidando as contagens para preservar a consistência.
3. O painel exibe progresso por fase, tamanho/atividade da fila de persistência e recusas acumuladas desde a inicialização. Métricas de desempenho só são coletadas enquanto Diagnóstico está aberto; não estima tempo restante nem mantém série entre reinícios.
4. O índice de PC tem limite deliberado de 200 mil jogadores; servidores maiores recebem erro explícito em vez de lista parcial. O cache troca para outro mundo e renova após 30 segundos ou alteração dos arquivos indexados.
5. Gerações, espécies, ginásios, equipes, datapacks e regras de mundo continuam pendentes até reiniciar. A interface diferencia esse estado da aplicação seletiva de XP, limites e captura.
6. A série em Diagnóstico inclui TPS estimado, CPU Java (se suportada) e tempo de GC entre amostras; não mede TPS real do cliente/rede nem perfis de threads. Use Spark para diagnóstico detalhado; o progresso da validação existe somente enquanto uma prévia sob demanda está ativa.
7. Não há medição A/B de TPS, MSPT, heap ou GC: o host dedicado de teste não está disponível. O build local e a inspeção do JAR não demonstram ganho de desempenho.

## Próxima ordem recomendada

Medir o custo em `docs/PERFORMANCE.md` quando houver um servidor de teste comparável.
