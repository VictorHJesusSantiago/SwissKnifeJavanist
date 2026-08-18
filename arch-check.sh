#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

status=0
fail() { echo "ARCH-FAIL: $1"; status=1; }

if grep -rn 'import dev\.swissknife\.Main;' src/main/java --include='*.java' \
    | grep -v 'src/main/java/dev/swissknife/Main\.java'; then
  fail "um pacote interno importa dev.swissknife.Main — ciclo de pacotes (use CommandExecutor)."
fi

if grep -rn 'new[[:space:]]\+Random[[:space:]]*(' \
    src/main/java/dev/swissknife/anonymize src/main/java/dev/swissknife/server 2>/dev/null; then
  fail "java.util.Random em caminho de segurança/anonimização — use SecureRandom."
fi

if grep -rn '\.hashCode()' src/main/java --include='*.java' \
    | grep -iE 'fingerprint|etag|identity|dedup'; then
  fail "hashCode() usado como identidade/fingerprint — use SHA-256."
fi

if grep -rhn '^import ' src/main/java --include='*.java' \
    | grep -vE '^[0-9]*:import (static )?(java\.|javax\.|com\.sun\.|dev\.swissknife\.)'; then
  fail "import de dependência externa no núcleo — o núcleo deve compilar só com o JDK."
fi

if [ "$status" -eq 0 ]; then echo "arch-check: todos os invariantes OK"; fi
exit "$status"
