#!/usr/bin/env bash
# Renders the PNG pictures of the app icon from its master, docs/icon/icon.svg:
#   docs/icon.png      192x192, the visible 72dp of the 108dp grid under a circular mask, for the README;
#   docs/icon-512.png  512x512, the same 72dp as a full-bleed square without transparency, for listings.
# Run it from anywhere after every change of the SVG and commit the PNGs with it:
#   scripts/render-icon.sh
# ImageMagick's own SVG renderer drops gradients, so headless Google Chrome draws the SVG at 1536x1536
# and ImageMagick 7 (magick) crops, masks and scales the shot. Both are required; CHROME=<path> picks
# another Chrome or Chromium binary. Every run writes the same bytes for the same SVG and Chrome version.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
svg=$root/docs/icon/icon.svg

fail() {
    echo "render-icon.sh: $*" >&2
    exit 1
}

if [[ -n ${CHROME:-} ]]; then
    chrome=$CHROME
elif [[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
    chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
else
    chrome=$(command -v google-chrome || command -v google-chrome-stable || command -v chromium ||
        command -v chromium-browser || true)
fi
[[ -n $chrome && -x $chrome ]] || fail "Google Chrome is not found; install it or set CHROME to its binary"
command -v magick > /dev/null || fail "ImageMagick 7 (magick) is not found; install it, e.g. brew install imagemagick"
[[ -f $svg ]] || fail "$svg is missing"

work=$(mktemp -d "${TMPDIR:-/tmp}/post-icon-XXXXXX")
trap 'rm -rf "$work"' EXIT

# The page shows the SVG at 768 CSS px; the device scale factor 2 makes the shot 1536 px, so the visible
# 72dp of the 108dp grid are the central 1024 px, from 256 to 1280.
cp "$svg" "$work/icon.svg"
echo '<!doctype html><meta charset="utf-8"><style>html,body{margin:0}img{display:block}</style><img src="icon.svg" width="768" height="768" alt="">' \
    > "$work/page.html"
# Headless Chrome does not always exit after the screenshot; the alarm ends it.
perl -e 'alarm 60; exec @ARGV' "$chrome" --headless=new --disable-gpu --hide-scrollbars \
    --force-device-scale-factor=2 --window-size=768,768 \
    --screenshot="$work/shot.png" "file://$work/page.html" > /dev/null 2>&1 || test -s "$work/shot.png" ||
    fail "Chrome made no screenshot of $svg"
size=$(magick identify -format '%wx%h' "$work/shot.png")
[[ $size == 1536x1536 ]] || fail "the screenshot is $size, not 1536x1536"

magick "$work/shot.png" -crop 1024x1024+256+256 +repage -alpha off "$work/visible.png"

# No metadata, no timestamps: the same SVG gives the same files.
png_out=(-strip -define "png:exclude-chunk=date,time")

magick "$work/visible.png" -filter Lanczos -resize 512x512 "${png_out[@]}" PNG24:"$root/docs/icon-512.png"

magick "$work/visible.png" -alpha set \
    \( -size 1024x1024 xc:none -fill white -draw "circle 511.5,511.5 511.5,0" \) \
    -compose DstIn -composite -filter Lanczos -resize 192x192 "${png_out[@]}" PNG32:"$root/docs/icon.png"

echo "docs/icon.png and docs/icon-512.png rendered from docs/icon/icon.svg"
