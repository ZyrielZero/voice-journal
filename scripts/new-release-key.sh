#!/usr/bin/env bash
# Creates a new release signing key and wires it up. Run this once, by hand,
# only when the release key has to be replaced (the 1.0.0 key was lost).
#
#   bash scripts/new-release-key.sh
#
# It does four things:
#   1. generates a keystore with a random password in ~/voice-journal-signing
#      (outside the repo), refusing to overwrite an existing one
#   2. writes keystore.properties next to it and copies it to the repo root
#      (git-ignored), so local release builds are signed
#   3. sets the four GitHub Actions secrets the Release workflow reads
#   4. prints the certificate's SHA-256, which has to be pinned in
#      .github/workflows/release.yml, README.md and the F-Droid recipe
#
# The password is never printed. It is in keystore.properties; back that
# file and the .jks up somewhere that is not this computer. Whoever holds
# them can publish updates to the app, and without them nobody can.
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="$HOME/voice-journal-signing"
jks="$dir/voice-journal-release.jks"
props="$dir/keystore.properties"
alias_name="voicejournal"

for tool in keytool openssl gh base64; do
    command -v "$tool" > /dev/null || { echo "$tool not found"; exit 1; }
done
if [ -e "$jks" ]; then
    echo "$jks already exists. Refusing to replace a signing key."
    exit 1
fi

umask 077
mkdir -p "$dir"
pw="$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-32)"

# 50 years: Play requires validity past 2033, and a key cannot be renewed.
keytool -genkeypair -keystore "$jks" -storetype PKCS12 -alias "$alias_name" \
    -keyalg RSA -keysize 4096 -validity 18250 \
    -dname "CN=ZyrielZero, O=Voice Journal" \
    -storepass "$pw" -keypass "$pw" > /dev/null 2>&1

{
    echo "storeFile=$jks"
    echo "storePassword=$pw"
    echo "keyAlias=$alias_name"
    echo "keyPassword=$pw"
} > "$props"
cp "$props" "$repo/keystore.properties"

cd "$repo"
base64 -w0 "$jks" | gh secret set KEYSTORE_BASE64
printf '%s' "$pw" | gh secret set KEYSTORE_PASSWORD
printf '%s' "$pw" | gh secret set KEY_PASSWORD
printf '%s' "$alias_name" | gh secret set KEY_ALIAS

sha="$(keytool -list -v -keystore "$jks" -storepass "$pw" 2> /dev/null \
    | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')"

echo
echo "Keystore:    $jks"
echo "Properties:  $props (copied to $repo/keystore.properties)"
echo "Secrets set: KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_PASSWORD KEY_ALIAS"
echo "CERT_SHA256=$sha"
echo
echo "Back up $dir now."
