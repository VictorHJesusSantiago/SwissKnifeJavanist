#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=${SWISSKNIFE_HOME:-"$HOME/.swissknife"}
[ -f "$ROOT/build/swissknife.jar" ] || "$ROOT/build.sh"
mkdir -p "$DEST"
cp "$ROOT/build/swissknife.jar" "$DEST/swissknife.jar"
cat > "$DEST/swissknife" <<'EOF'
exec java -jar "$(dirname "$0")/swissknife.jar" "$@"
EOF
chmod +x "$DEST/swissknife"
(cd "$DEST" && sha256sum swissknife.jar > swissknife.jar.sha256)
printf 'Instalado em %s. Adicione o diretório ao PATH.\n' "$DEST"
