# Administração do painel

## Primeiro acesso

1. Inicie o servidor Fabric com o addon instalado somente no servidor.
2. Execute `/geracoes web` no console ou como operador. O comando mostra o endereço local e o caminho do token.
3. No próprio computador do servidor, abra `http://127.0.0.1:8765` e informe o token de `<mundo>/generation-control/web-access.token`.
4. Mantenha esse arquivo privado. Não coloque o token em URL, log público, captura de tela ou conversa.

O token é uma credencial administrativa. O addon tenta aplicar ACL restrita ao usuário que executa o servidor no Windows, ou modo POSIX `0600` quando o sistema oferece suporte. Revise as permissões do arquivo no seu host.

## Acesso remoto seguro

O painel escuta em `127.0.0.1`. Você pode entrar por um endereço HTTPS privado ou usar um túnel SSH. Mantenha a porta do painel fechada no roteador e no firewall público.

### Endereço HTTPS privado com Tailscale Serve

1. Conecte o servidor e os dispositivos administradores à mesma rede Tailscale. Restrinja nas políticas da rede o acesso ao servidor na porta HTTPS aos administradores.
2. Na máquina do Minecraft, com o painel em execução, configure o Serve na raiz do endereço:

   ```sh
   tailscale serve --bg http://127.0.0.1:8765
   tailscale serve status
   ```

3. Copie o endereço HTTPS mostrado pelo Serve para `<mundo>/generation-control/web.properties`. Por exemplo:

   ```properties
   httpsOrigin=https://cobbleverse.exemplo.ts.net
   ```

4. Reinicie o Minecraft. No dispositivo administrador, conectado à rede Tailscale, abra esse endereço no navegador e informe o token. Leia o arquivo do token por SSH/SFTP ou diretamente na máquina do servidor.

O Serve fornece HTTPS dentro da sua rede Tailscale. Mantenha o compartilhamento público pelo Funnel desativado. [Documentação do Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve).

Você também pode usar um proxy HTTPS já instalado no mesmo host. Configure um hostname exclusivo para o painel, encaminhe a raiz para `http://127.0.0.1:8765`, preserve o cabeçalho `Host` do navegador e restrinja a entrada à rede administrativa. O addon valida o endereço configurado e a origem de cada alteração, sem confiar em cabeçalhos `X-Forwarded-*`. O cookie remoto recebe `Secure`. [Documentação do proxy reverso do Caddy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).

### Helper do Windows

Com OpenSSH e um alias configurado no `~/.ssh/config`:

```powershell
.\tools\open-admin-tunnel.ps1 -Server cobbleverse -OpenBrowser
```

O helper escolhe uma porta loopback livre, verifica a resposta do painel e mostra a URL local. Para buscar o token por SSH e copiá-lo para a área de transferência sem exibi-lo no terminal:

```powershell
.\tools\open-admin-tunnel.ps1 `
  -Server cobbleverse `
  -RemoteTokenPath '/srv/cobbleverse/world/generation-control/web-access.token' `
  -CopyToken -OpenBrowser
```

Em um servidor Windows, adicione `-RemoteTokenShell Windows` e use o caminho Windows do arquivo. A janela do PowerShell mantém o túnel aberto; `Ctrl+C` encerra a conexão. O token permanece na área de transferência até ser substituído.

### Túnel manual

Em qualquer cliente com OpenSSH:

```sh
ssh -N -T -o ExitOnForwardFailure=yes \
  -L 18765:127.0.0.1:8765 admin@host-privado
```

Com esse processo ativo, abra `http://127.0.0.1:18765` no computador administrador. Para obter o token, leia o arquivo por SSH/SFTP ou use o helper do Windows.

## Porta e ativação

O arquivo `<mundo>/generation-control/web.properties` controla o painel:

```properties
enabled=true
port=8765
httpsOrigin=
```

Reinicie o servidor após mudar qualquer opção deste arquivo. Deixe `httpsOrigin` vazio para usar somente o acesso local ou por SSH. Uma alteração de regra feita no navegador é salva como revisão pendente. Use **Validar** e **Salvar**, depois reinicie para aplicá-la. A revisão anterior continua disponível para restauração pelo painel.

## Boas práticas

- Dê acesso ao arquivo de token apenas a quem administra o servidor.
- Use uma VPN/segmento privado para chegar ao host SSH e restrinja o SSH no firewall.
- Não publique tokens, arquivos do mundo ou dados de jogadores em issues.
- Se o token vazar, pare o servidor, remova o token com o processo desligado e reinicie para gerar outro; confirme a ACL antes de reabrir o túnel.
