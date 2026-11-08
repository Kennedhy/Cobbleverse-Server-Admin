# Cobbleverse Server Admin

[![Build](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/Kennedhy/Cobbleverse-Server-Admin/actions/workflows/build.yml)

Addon Fabric **server-only** para administrar regras do Cobbleverse por um painel web privado. O painel acompanha o modpack: jogadores não precisam instalar este JAR.

[Read this in English](README.en.md)

> **Versão 0.4.8 — preview.** Use primeiro em uma cópia do servidor e do mundo. Consulte [o estado de validação](docs/TESTING.md).

## O que administra

- Gerações de Pokémon: uma geração, até uma geração ou seleção personalizada, com exceções de espécies e formas.
- Circuitos de ginásio e regras de estruturas reconhecidas pelos mods integrados.
- Multiplicador de XP global, grupos criados no painel e ajustes por jogador.
- Limite de nível de Pokémon selvagens, global ou derivado do progresso RCT. Perto de vários jogadores, usa o maior limite aplicável.
- Multiplicador global de captura que preserva o efeito das Poké Bolas.
- Consulta das caixas de PC dos jogadores conectados ou com dados salvos no mundo.
- Validação, diagnóstico, histórico, descarte de rascunho e restauração da revisão anterior.

O painel salva uma revisão pendente. As novas regras entram em vigor quando o servidor reinicia; Pokémon e estruturas que já existem não são removidos.

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
2. Baixe o JAR na página de [Releases](https://github.com/Kennedhy/Cobbleverse-Server-Admin/releases). As próximas releases também terão `open-admin-tunnel.ps1` e `prepare_server_mods.py` como ferramentas separadas. Se uma release antiga não tiver esses arquivos, obtenha o código-fonte da mesma versão.
3. Coloque o JAR somente na pasta `mods/` do servidor Fabric. Não o instale nos clientes.
4. Inicie o servidor e, como administrador, execute `/geracoes web` para ver o endereço e o caminho do token.
5. No computador do servidor, abra `http://127.0.0.1:8765` e cole o token. Para acesso remoto, configure HTTPS privado ou um túnel SSH. Veja [Administração do painel](docs/ADMIN.md).

O painel fica preso a `127.0.0.1`. **Não exponha a porta 8765 à internet nem aos jogadores.** A configuração de porta fica em `<mundo>/generation-control/web.properties`; alterá-la exige reiniciar o processo.

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

O servidor HTTP usa o JDK, autenticação por token e sessão, validação de origem e sem CORS. O token é criado dentro dos dados do mundo; o mod tenta restringir as permissões do arquivo ao usuário do servidor. O helper PowerShell facilita um túnel SSH no Windows; `prepare_server_mods.py` cria uma cópia de mods para servidor sem alterar a instância cliente. Mais detalhes em [Administração](docs/ADMIN.md).

O addon não varre jogadores, Pokémon, entidades ou chunks continuamente. Validações pesadas só começam após solicitação administrativa e são processadas em lotes. Não foram incluídas cópias de outros mods nem uma segunda camada de otimização. **Ainda não há benchmark A/B que prove ganho de TPS ou memória**; consulte [arquitetura e custo](docs/ARCHITECTURE.md).

## Licença

Código deste projeto sob [MIT](LICENSE). O JAR não redistribui Cobblemon, RCT ou outros mods de terceiros; cada dependência mantém os termos e a licença de seu autor.
