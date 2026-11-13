# Melhorias e estado de implementação

Revisado em 13/11/2026 para a versão preview 0.6.0. O ciclo reúne operações de PC, controles de batalha e gerenciamento de treinadores. Ainda não há medição A/B em host dedicado.

## Implementado neste ciclo

- **Edição de PC:** administradores podem adicionar, editar e excluir Pokémon, incluindo espécie/forma, apelido, nível, shiny, natureza, habilidade, golpes, IVs e EVs. O servidor confere o UUID observado no espaço, usa o armazenamento do Cobblemon e mantém viewer em somente leitura.
- **Cap de batalha PvE:** o administrador pode bloquear toda a luta ou somente Pokémon acima do cap ao entrar/trocar; PvP continua isento.
- **Progressão individual RCT:** a página de PC permite selecionar um circuito liberado e editar treinadores vencidos. A edição usa revisão contra estado desatualizado, impede alterações durante batalha, salva o ledger/histórico fora da thread do jogo e atualiza o cap RCT do jogador conectado.
- **Dificuldade RCT por grupos:** grupos próprios de jogador (UUID) e treinador (categoria RCT e/ou ID) recebem perfis de nível, IV mínimo e EV por atributo. O par de grupos exato tem precedência; depois valem treinador, jogador e perfil geral. Em batalhas cooperativas, aplica-se o perfil mais difícil entre os jogadores. A regra altera apenas cópias de batalha dos treinadores RCT.
- **Spawn de treinadores:** a página usa a configuração do RCT para chance, frequência, limites, distâncias, cartão de treinador e NPC de associação, com faixas máximas para impedir aumentos extremos da população.
- **Dinheiro e recompensas:** o painel configura ganhos de NPCs e o multiplicador do CobbleDollars. Esse multiplicador também altera rendas de Pokémon selvagens; o pack não oferece valor de recompensa por treinador individual.
- **Taxa shiny:** configuração opcional da taxa global do Cobblemon, incluída em aplicar ao vivo e descartar. Ela pode afetar sistemas de outros mods que usam a mesma configuração global.
- **Organização do painel:** treinadores e economia ficam em telas administrativas próprias. A página inicial foca jogadores, regras ativas e reinício do servidor; acesso, diagnóstico/monitoramento e presets foram removidos do frontend. Credenciais seguem administradas pelo console.
- **Interface React:** os formulários adicionais carregam dados sob demanda, salvam por rotas autenticadas e não adicionam polling ou dependências ao processo do servidor.

## Limites e estado de validação

1. A consulta/edição de progresso RCT só funciona enquanto o jogador está conectado. O RCT não oferece acesso oficial à progressão de jogadores offline.
2. A dificuldade só vale em batalhas PvE com treinadores RCT; não altera batalha PvP, selvagens ou os JSONs de equipe do datapack. O resultado do evento precisa ser confirmado em batalha real no Cobbleverse.
3. O CobbleDollars oferece um toggle de pagamento de NPCs e um multiplicador global; não há prêmio por treinador nem multiplicador exclusivo para batalhas NPC.
4. Spawn, economia e dificuldade são configurações próprias e salvam independentemente da revisão de gerações. Viewer pode consultar; só admin altera. XP, limites, captura e shiny continuam no fluxo de revisão principal.
5. Gerações, espécies, ginásios, datapacks e regras de mundo continuam pendentes até reiniciar. XP, cap, captura e taxa shiny podem ser aplicados ao vivo.
6. O build local do ciclo compila Java e o frontend; não há confirmação de comportamento de batalha em servidor carregado nem benchmark A/B de TPS/MSPT, heap ou GC. A validação de novas integrações e a medição de carga continuam pendentes.

## Próxima etapa de desempenho

Quando houver um host de teste dedicado, siga [o procedimento A/B](PERFORMANCE.md) com a mesma cópia do mundo, modpack, população e atividade. Não declarar ganhos de desempenho a partir somente do build ou de uma instância vazia.
