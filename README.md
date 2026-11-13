# Cobbleverse Server Admin

[![Build](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/workflows/build.yml)

Addon Fabric **server-only** para administrar regras do Cobbleverse por um painel web privado. O painel acompanha o modpack: jogadores não precisam instalar este JAR.

[Read this in English](README.en.md)

> **Versão 0.6.0 — preview.** Use primeiro em uma cópia do servidor e do mundo. Consulte [o estado de validação](docs/TESTING.md).

## O que administra

- Gerações de Pokémon: uma geração, até uma geração ou seleção personalizada, com exceções de espécies e formas.
- Circuitos de ginásio e regras de estruturas reconhecidas pelos mods integrados.
- Multiplicador de XP global, grupos criados no painel e ajustes por jogador.
- Limite de nível de Pokémon selvagens, global ou derivado do progresso RCT. Perto de vários jogadores, usa o maior limite aplicável.
- Regras opcionais de cap em PvE: bloquear a batalha inteira ou somente o Pokémon acima do cap; PvP não recebe bloqueio de nível.
- Multiplicador global de captura que preserva o efeito das Poké Bolas e taxa opcional de aparição shiny.
- Aparição de treinadores RCT: chance, frequência, limites, distância, cartão e NPC de associação.
- Dificuldade de batalhas RCT por grupos de treinadores e jogadores, com ajustes em nível, IV e EV aplicados apenas às cópias de batalha.
- Recompensas CobbleDollars de NPC e multiplicador global de renda. O multiplicador também afeta Pokémon selvagens; valores de prêmio individuais não são oferecidos pelo mod.
- Consulta paginada das caixas de PC dos jogadores conectados ou com dados salvos no mundo; administradores podem adicionar, editar e excluir Pokémon. Na mesma página, podem editar circuito e treinadores vencidos da progressão RCT individual enquanto o jogador estiver conectado.
- Validação sob demanda, histórico de configurações, descarte de rascunho e restauração da revisão anterior.
- Credenciais de administrador e consulta gerenciadas pelo console, com autoria por conta no histórico.
- Dashboard de jogadores online com acesso ao PC e reinício pelo supervisor do servidor.

As configurações de gerações, ginásios, XP, nível, captura e shiny continuam seguindo o fluxo de revisão, validação e salvamento do painel. XP, níveis, captura e shiny podem ser aplicados ao vivo; gerações, ginásios, datapacks e regras de mundo exigem reinício. As telas de treinadores salvam separadamente: spawn e economia usam as APIs do RCT/CobbleDollars, e a dificuldade vale nas próximas batalhas. Progresso RCT só pode ser editado com o jogador conectado e fora de batalha. Pokémon e estruturas existentes não são removidos.

## Compatibilidade

| Componente | Versão alvo |
| --- | --- |
| Minecraft | 1.21.1 |
| Java | 21 ou superior |
| Fabric Loader | 0.18.4 ou superior |
| Fabric API | 0.116.14+1.21.1 |
| Cobblemon | 1.7.3 |
| Radical Cobblemon Trainers | 0.18.1-beta |
| RCT API | 0.15.2-beta |

As integrações de raids, megaevoluções e spawns de outros mods dependem das versões listadas no modpack. Consulte [limitações e testes pendentes](docs/TESTING.md).

## Instalação no servidor

1. Faça backup e valide o addon em uma cópia do servidor e do mundo.
2. Baixe o JAR e os utilitários `open-admin-tunnel.ps1` e `prepare_server_mods.py` na página de [Releases](https://github.com/Kennedhy/Cobbleverse-Server-Admin/releases). Releases antigas podem não incluir esses arquivos; nesse caso, obtenha-os no código-fonte da mesma versão.
3. Coloque o JAR somente na pasta `mods/` do servidor Fabric. Não o instale nos clientes.
4. Inicie o servidor e, como administrador, execute `/geracoes web` para ver os caminhos das credenciais compartilhadas iniciais.
5. Abra o painel por HTTPS privado ou por um túnel SSH e use o token de administrador inicial. Crie tokens individuais pelo console. A credencial de consulta permite ver jogadores, PCs e histórico e usar o simulador. [Administração do painel](docs/ADMIN.md)

O painel escuta em `127.0.0.1:1` por padrão (`port=0001`). O navegador bloqueia HTTP direto na porta 1. Para alterar a porta, edite `<mundo>/generation-control/web.properties` e reinicie o servidor.

## Compilar do código-fonte

Requer Java 21, Node.js 22.12+ e npm. O Gradle usa 1 GiB de heap somente durante a compilação. As dependências do modpack são obtidas de versões imutáveis do Maven oficial do Modrinth; não é necessário fornecer uma instalação local do Cobbleverse.

```powershell
Push-Location web
npm ci
Pop-Location
.\gradlew.bat --no-daemon --max-workers=1 remapJar
```

No Linux/macOS:

```sh
cd web && npm ci && cd ..
./gradlew --no-daemon --max-workers=1 remapJar
```

O JAR remapeado fica em `build/libs/`. React, Vite e Node geram os arquivos estáticos durante o build. Veja [desenvolvimento](DEVELOPMENT.md) para build, testes e processo de release.

## Segurança e desempenho

O servidor HTTP usa o JDK, tokens por conta, sessões, validação de origem e sem CORS. As credenciais ficam nos dados do mundo; o mod tenta restringir as permissões dos arquivos e da pasta ao usuário do servidor. Revisões feitas pelo painel identificam a conta no histórico. O helper PowerShell facilita um túnel SSH no Windows; `prepare_server_mods.py` cria uma cópia de mods para servidor sem alterar a instância cliente. Mais detalhes em [Administração](docs/ADMIN.md).

O addon não varre jogadores, Pokémon, entidades ou chunks continuamente. Validações pesadas só começam após solicitação administrativa e são processadas em lotes. Não foram incluídas cópias de outros mods nem uma segunda camada de otimização. **Ainda não há benchmark A/B que prove ganho de TPS ou memória**; consulte [arquitetura e custo](docs/ARCHITECTURE.md).

## Licença

Código deste projeto sob [MIT](LICENSE). O JAR não redistribui Cobblemon, RCT ou outros mods de terceiros; cada dependência mantém os termos e a licença de seu autor.
