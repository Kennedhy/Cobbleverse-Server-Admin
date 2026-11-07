# Estado de validação

O projeto é um preview para o modpack Cobbleverse de Minecraft 1.21.1. Este documento separa o que foi observado do que ainda precisa de servidor e jogadores reais.

## Versão 0.4.5

- Build local do JAR e 36 testes automatizados concluídos. O artefato contém apenas entrypoint de servidor e inclui os arquivos estáticos do painel.
- O pack de teste com 252 mods chegou a `Done` no servidor local isolado. Minecraft e painel escutaram em `127.0.0.1`.
- Em um proxy HTTPS local de teste, com certificado verificado, passaram login, leitura de estado autenticada, logout e a checagem dos atributos do cookie. Um hostname não configurado e alterações com origem errada ou ausente foram recusados. Login e logout por HTTP em loopback também passaram.
- O acesso pelo Tailscale Serve no host de destino, a conexão de um cliente do pack sem o JAR nesta versão e o perfil com carga representativa ainda precisam de validação. A conexão de um cliente sem o JAR foi observada na 0.4.3.

## Versão 0.4.4

- Build `remapJar` a partir do repositório, sem uma instalação local do modpack: concluído.
- Inspeção do JAR: versão 0.4.4, ambiente `server`, somente entrypoint `server`, 91 classes, zero classes cliente; HTML, CSS e JavaScript do painel estão empacotados. Tamanho 303.619 bytes, SHA-256 `4E9DCB7EE9C69642E87C6E5C2197D8E2B3562F6DB23D4EC872D13532545706DD`.
- Boot completo do pack isolado: ainda pendente para 0.4.4.
- Testes automatizados: não executados nesta preparação do release.
- Acesso por SSH a um host dedicado, clientes sem o JAR, comportamento funcional completo e carga representativa: pendentes.

Não instale esta versão no mundo de produção antes de verificar os itens necessários ao seu servidor.

## Evidência anterior — 0.4.3

Em uma cópia local isolada do pack, o JAR 0.4.3 iniciou com 252 mods e mundo de teste próprio. O painel respondeu em loopback, login e `/api/state` autenticados funcionaram, origem externa foi recusada e o arquivo do token recebeu ACL local. Um cliente do pack sem o JAR do gerenciador conectou nessa cópia.

Esse teste não validou host dedicado via SSH, carga real, migração de progresso RCT ou todas as rotas de spawn e captura. O tempo de boot foi uma observação única e não mede o custo incremental do addon.

## Matriz

| Área | Estado | Observação |
| --- | --- | --- |
| Compilação e empacotamento | 0.4.5 concluído | Build sem `packDir` local |
| Metadados server-only | 0.4.5 verificados | Apenas entrypoint de servidor |
| Painel HTTP, token e sessão | 0.4.5 observado localmente | Loopback e proxy de teste com TLS |
| Cliente do pack sem o addon | 0.4.3 conectado localmente | Repetir na build 0.4.5 |
| Spawn, XP, captura, RCT e addons | Parcial | Cobertura varia por evento e versão |
| Tailscale Serve ou túnel SSH no host de destino | Pendente | Requer host acessível pela rede privada |
| TPS, heap e memória sob carga | Pendente | Sem benchmark A/B representativo |

## Limitações conhecidas

- Mods podem criar Pokémon, raids ou capturas por rotas que não usam os eventos e hooks integrados.
- Mudanças de regra não removem Pokémon já capturados, estruturas existentes nem progresso salvo.
- O comportamento de cap RCT depende da versão e do estado de progresso do RCT.
- O modo de geração não cria novos circuitos de ginásio. A disponibilidade de circuitos depende do conteúdo do modpack.
- Ainda falta observar o perfil de desempenho em um host dedicado com atividade e população representativas.
