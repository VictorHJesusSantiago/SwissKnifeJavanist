# Segurança

## Versões suportadas

A versão mais recente da linha `1.x` recebe correções de segurança.

## Comunicação responsável

Não abra uma issue pública contendo credenciais, dados pessoais ou detalhes
exploráveis. Envie uma descrição mínima para `dev@swissknife.local`, incluindo
versão, impacto, passos de reprodução e uma forma segura de contato.

O projeto confirmará o recebimento, reproduzirá o problema, preparará testes de
regressão e publicará a correção com crédito ao pesquisador quando autorizado.

## Operação segura

- Os servidores leves escutam apenas em loopback.
- Configure `SWISSKNIFE_API_TOKEN`.
- Use TLS no proxy reverso.
- Armazene senhas JDBC em variáveis de ambiente.
- Revise alterações destrutivas e relatórios antes de aplicá-los.
