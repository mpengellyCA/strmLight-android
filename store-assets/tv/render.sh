#!/usr/bin/env bash
# Renders banner.svg into the TV banner PNGs (android:banner). Needs rsvg-convert;
# the wordmark is set in the Archivo the app ships, not a system font.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
res=$here/../../app/src/main/res
fonts=$(mktemp -d)
trap 'rm -rf "$fonts"' EXIT
cat >"$fonts/fonts.conf" <<CONF
<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
  <dir>$here/../../app/src/main/assets/fonts</dir>
  <cachedir>$fonts/cache</cachedir>
</fontconfig>
CONF
export FONTCONFIG_FILE=$fonts/fonts.conf
for size in xhdpi:320:180 xxhdpi:480:270 xxxhdpi:640:360; do
    IFS=: read -r density w h <<<"$size"
    mkdir -p "$res/drawable-$density"
    rsvg-convert -w "$w" -h "$h" "$here/banner.svg" -o "$res/drawable-$density/atv_banner.png"
done
