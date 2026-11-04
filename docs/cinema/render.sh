#!/bin/sh
# Renders the README images of the Cinema Home from layout.html (a replica built from the app's measurements,
# with made-up titles and painted artwork), at the TV's 1920x1080. Needs Google Chrome or Chromium.
#
#   docs/cinema/render.sh            writes home.png, apps.png, ajustes.png, botoes.png, menu-titulo.png here
set -eu
here=$(cd "$(dirname "$0")" && pwd)
chrome=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
[ -x "$chrome" ] || chrome=$(command -v chromium || command -v google-chrome)

for pair in home:home apps:apps ajustes:ajustes botoes:botoes menu:menu-titulo; do
  screen=${pair%%:*}
  out=${pair#*:}
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --force-device-scale-factor=1 --window-size=1920,1080 --virtual-time-budget=3000 \
    --screenshot="$here/$out.png" "file://$here/layout.html#$screen" >/dev/null 2>&1
  echo "$here/$out.png"
done
