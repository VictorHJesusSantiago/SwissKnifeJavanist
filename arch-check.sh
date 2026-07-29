#!/usr/bin/env bash
# Fitness function de arquitetura: falha o build se um invariante estrutural do núcleo for violado.
# É a rede que impede a regressão dos defeitos corrigidos na auditoria de 2026-07 — o CI já publicava
# o detector de ciclos mas nunca reprovava com base nele. Agora reprova.
set -euo pipefail
cd "$(dirname "$0")"

status=0
fail() { echo "ARCH-FAIL: $1"; status=1; }

# 1) Sem ciclo dev.swissknife <-> dev.swissknife.portal (ADP): nenhum pacote interno importa Main.
if grep -rn 'import dev\.swissknife\.Main;' src/main/java --include='*.java' \
    | grep -v 'src/main/java/dev/swissknife/Main\.java'; then
  fail "um pacote interno importa dev.swissknife.Main — ciclo de pacotes (use CommandExecutor)."
fi

# 2) Sem java.util.Random em caminho de segurança/anonimização (regra INSECURE_RANDOM).
if grep -rn 'new[[:space:]]\+Random[[:space:]]*(' \
    src/main/java/dev/swissknife/anonymize src/main/java/dev/swissknife/server 2>/dev/null; then
  fail "java.util.Random em caminho de segurança/anonimização — use SecureRandom."
fi

# 3) Sem hashCode() como identidade/fingerprint (32 bits colidem).
if grep -rn '\.hashCode()' src/main/java --include='*.java' \
    | grep -iE 'fingerprint|etag|identity|dedup'; then
  fail "hashCode() usado como identidade/fingerprint — use SHA-256."
fi

# 4) Sem dependência externa no núcleo: nenhum import fora de java.*/javax.*/com.sun.*/dev.swissknife.*
if grep -rhn '^import ' src/main/java --include='*.java' \
    | grep -vE '^[0-9]*:import (static )?(java\.|javax\.|com\.sun\.|dev\.swissknife\.)'; then
  fail "import de dependência externa no núcleo — o núcleo deve compilar só com o JDK."
fi

if [ "$status" -eq 0 ]; then echo "arch-check: todos os invariantes OK"; fi
exit "$status"
