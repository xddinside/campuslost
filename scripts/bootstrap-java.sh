#!/usr/bin/env bash
# Installs the exact JDK used by CampusLost, outside the repository.
#
# Vendor/build: Eclipse Temurin 25.0.5+8 LTS (OpenJDK25U)
# Archive:      OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz
# SHA-256:      68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb
set -euo pipefail

VERSION="25.0.5+8"
DIR_NAME="jdk-25.0.5+8"
URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz"
CHECKSUM="68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb"
DEST="${JAVA25_HOME:-$HOME/.local/share/campuslost}"
ARCHIVE="$DEST/temurin-$DIR_NAME.tar.gz"

if [[ -x "$DEST/$DIR_NAME/bin/javac" ]]; then
  echo "JDK $VERSION already installed at $DEST/$DIR_NAME"
  exit 0
fi

mkdir -p "$DEST"
if [[ ! -f "$ARCHIVE" ]]; then
  echo "Downloading Temurin $VERSION..."
  curl -fL --retry 3 -o "$ARCHIVE" "$URL"
fi
echo "$CHECKSUM  $ARCHIVE" | sha256sum -c -
tar -xzf "$ARCHIVE" -C "$DEST"
echo "Installed JDK $VERSION at $DEST/$DIR_NAME"
