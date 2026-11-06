#!/usr/bin/env bash
# Puts Re:Lad's signing key into the GitHub repository secrets used by
# .github/workflows/relad-release.yml:
#   RELAD_SIGNING_KEY        base64 of ~/.relad/relad-release.jks
#   RELAD_KEYSTORE_PASSWORD  password of the key
#
#   bash plus/tools/upload-signing-secrets.sh
#
# You type the password yourself (it is checked against the key before anything is uploaded)
# and it is never printed. GitHub stores secrets encrypted; nobody can read them back,
# workflows only receive them while running.
set -euo pipefail

REPO="phloya/ReLad"
KEYSTORE="$HOME/.relad/relad-release.jks"
ALIAS="relad"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
elif [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" ]]; then
    KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
else
    echo "Не найден keytool. Установи Android Studio или задай JAVA_HOME." >&2
    exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
    echo "Нет ключа $KEYSTORE. Сначала создай его: bash plus/tools/create-signing-key.sh" >&2
    exit 1
fi
gh auth status >/dev/null 2>&1 || { echo "gh не авторизован: выполни gh auth login" >&2; exit 1; }

read -r -s -p "Пароль ключа подписи (из Bitwarden): " PASS; echo
export RELAD_KEYSTORE_PASSWORD="$PASS"
trap 'unset RELAD_KEYSTORE_PASSWORD PASS' EXIT

if ! "$KEYTOOL" -list -keystore "$KEYSTORE" -alias "$ALIAS" \
    -storepass:env RELAD_KEYSTORE_PASSWORD >/dev/null 2>&1; then
    echo "Пароль не подходит к ключу. Ничего не загружено." >&2
    exit 1
fi

base64 -i "$KEYSTORE" | tr -d '\n' | gh secret set RELAD_SIGNING_KEY --repo "$REPO"
printf '%s' "$PASS" | gh secret set RELAD_KEYSTORE_PASSWORD --repo "$REPO"

echo
echo "Готово. Секреты в $REPO:"
gh secret list --repo "$REPO"
