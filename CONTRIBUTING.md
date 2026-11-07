# Como contribuir

Obrigado por ajudar a melhorar o Cobbleverse Server Admin.

## Antes de abrir uma alteração

- Abra uma issue para erros, mudanças de comportamento ou compatibilidade nova.
- Inclua versões do Minecraft, Fabric Loader, Fabric API, Cobblemon, RCT e addons relacionados.
- Não publique tokens, endereços privados, mundo, dados de jogadores ou logs com credenciais.
- Não envie JARs, código-fonte ou recursos de outros mods. Integrações devem respeitar as licenças dos projetos envolvidos.

## Pull requests

- Faça uma alteração por PR e explique o comportamento esperado.
- Preserve o carregamento server-only, o bind loopback do painel e o processamento sob demanda.
- Atualize `CHANGELOG.md` e a documentação se a mudança afetar operadores.
- Compile com `./gradlew --no-daemon --max-workers=1 remapJar`; rode `./gradlew test` quando alterar código coberto pela suíte.
- Informe o que foi verificado e o que ainda não foi testado no servidor completo.

Ao enviar uma contribuição, você concorda que ela pode ser distribuída sob a licença MIT deste projeto. Mantenha os avisos de direitos autorais e licenças de terceiros.
