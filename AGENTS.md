# Contexto persistente do projeto

Leia [docs/PROJECT_CONTEXT.md](docs/PROJECT_CONTEXT.md) ao iniciar trabalho neste repositório. Esse documento registra os objetivos e decisões confirmados pelo usuário nas conversas do Codex. Para detalhes do comportamento já implementado, compatibilidade e operação, consulte também README.md, DEVELOPMENT.md e os documentos em docs/.

## Orientações de trabalho

- O produto é um addon Fabric somente para servidor, com painel web. Não reintroduza tela administrativa dentro do Minecraft nem exija este JAR nos clientes.
- Priorize eficiência em servidor: hooks específicos, pouco trabalho na thread de jogo, processamento sob demanda e lotes/fila limitados. Não adicione otimizações ou varreduras contínuas por suposição.
- Preserve acesso privado ao painel e credenciais protegidas. Edição de PC é administrativa; credenciais de consulta continuam somente leitura.
- Fale com o usuário em português do Brasil. Ele prefere alterações substanciais agrupadas e não quer uma versão/release para cada pequena mudança.
- Antes de editar, confira o estado do Git e preserve mudanças existentes. Não faça push, tag ou release como parte de uma alteração local, a menos que a etapa de publicação esteja solicitada.
- Para servidores de teste, use apenas cópias descartáveis de mods e mundos. A aceitação da EULA nesta conversa valeu somente para um servidor de teste descartável.
- Ao avaliar código ou recursos de outros mods, confira primeiro licença, compatibilidade e atribuição. Não copie JARs, assets ou código de terceiros sem autorização compatível com a licença.
