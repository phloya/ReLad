#!/usr/bin/env bash
# Creates Re:Lad's release signing key and the local keystore.properties that the build reads.
#
#   bash plus/tools/create-signing-key.sh
#
# - The key goes to ~/.relad/relad-release.jks (outside the repository).
# - keystore.properties goes to the repository root (ignored by git, readable only by you).
# - The password is typed by you and never printed. Keep it and the key file in your backups:
#   losing either means Re:Lad can never be updated with the same signature again.
set -euo pipefail

KEY_DIR="$HOME/.relad"
KEYSTORE="$KEY_DIR/relad-release.jks"
ALIAS="relad"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROPS="$REPO_ROOT/keystore.properties"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
elif [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" ]]; then
    KEYTOOL="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
else
    echo "Не найден keytool. Установи Android Studio или задай JAVA_HOME." >&2
    exit 1
fi

if [[ -e "$KEYSTORE" ]]; then
    echo "Ключ уже существует: $KEYSTORE" >&2
    echo "Скрипт ничего не перезаписывает. Если нужен новый ключ, сначала убери старый вручную." >&2
    exit 1
fi
if [[ -e "$PROPS" ]]; then
    echo "Файл уже существует: $PROPS" >&2
    echo "Скрипт ничего не перезаписывает." >&2
    exit 1
fi

echo "Придумай пароль для ключа подписи Re:Lad."
echo "Минимум 16 символов, без пробелов в начале и в конце. Сохрани его в менеджере паролей."
while true; do
    read -r -s -p "Пароль: " PASS1; echo
    read -r -s -p "Ещё раз: " PASS2; echo
    if [[ "$PASS1" != "$PASS2" ]]; then
        echo "Пароли не совпадают, попробуй ещё раз."
    elif (( ${#PASS1} < 16 )); then
        echo "Слишком короткий пароль (${#PASS1} символов), нужно минимум 16."
    elif [[ "$PASS1" != "${PASS1#[[:space:]]}" || "$PASS1" != "${PASS1%[[:space:]]}" ]]; then
        echo "Пароль не должен начинаться или заканчиваться пробелом."
    else
        break
    fi
done
unset PASS2

mkdir -p "$KEY_DIR"
chmod 700 "$KEY_DIR"

export RELAD_KEYSTORE_PASSWORD="$PASS1"
trap 'unset RELAD_KEYSTORE_PASSWORD PASS1' EXIT

"$KEYTOOL" -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=ReLad, O=ReLad" \
    -storepass:env RELAD_KEYSTORE_PASSWORD -keypass:env RELAD_KEYSTORE_PASSWORD
chmod 600 "$KEYSTORE"

# In .properties files a backslash starts an escape sequence, so it has to be doubled.
ESCAPED_PASS="${PASS1//\\/\\\\}"
umask 077
cat > "$PROPS" <<EOF
storeFile=$KEYSTORE
storePassword=$ESCAPED_PASS
keyAlias=$ALIAS
keyPassword=$ESCAPED_PASS
EOF
unset ESCAPED_PASS
chmod 600 "$PROPS"

FINGERPRINT="$("$KEYTOOL" -list -v -keystore "$KEYSTORE" -alias "$ALIAS" \
    -storepass:env RELAD_KEYSTORE_PASSWORD | awk -F': ' '/SHA256:/ {print $2; exit}')"

echo
echo "Готово."
echo "  Ключ:        $KEYSTORE"
echo "  Псевдоним:   $ALIAS"
echo "  Для сборки:  $PROPS (git его игнорирует)"
echo "  Отпечаток SHA-256 (не секрет, по нему проверяют подлинность APK):"
echo "  $FINGERPRINT"
echo
echo "Теперь сделай резервные копии ключа и пароля (см. plus/README.md, раздел «Подпись»)."
