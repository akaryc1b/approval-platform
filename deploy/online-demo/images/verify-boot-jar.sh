#!/bin/sh
set -eu
[ "$#" -eq 1 ] || { echo 'Expected one absolute executable JAR path' >&2; exit 2; }
case "$1" in /*.jar) ;; *) echo 'Expected an absolute .jar path' >&2; exit 2 ;; esac
[ -f "$1" ] && [ ! -L "$1" ] || { echo 'JAR must be a regular file' >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
jar tf "$1" > "$work/entries"
grep -q '^BOOT-INF/classes/' "$work/entries"
grep -q '^BOOT-INF/lib/.*\.jar$' "$work/entries"
grep -qx 'BOOT-INF/classes/demo/purchase-payment-golden-path.json' "$work/entries"
grep -qx 'BOOT-INF/classes/demo/purchase-payment-demo-seed.json' "$work/entries"
(cd "$work" && jar xf "$1" META-INF/MANIFEST.MF)
tr -d '\r' < "$work/META-INF/MANIFEST.MF" > "$work/manifest"
grep -qx 'Main-Class: org.springframework.boot.loader.launch.JarLauncher' "$work/manifest"
grep -q '^Start-Class: [A-Za-z][A-Za-z0-9_.]*$' "$work/manifest"
echo 'EXECUTABLE_JAR_LAYOUT_VERIFIED_NOT_APPLICATION_RUNTIME'
