#!/usr/bin/env bash
# Imports a Mihon version into Re:Lad as ONE commit on the `mihon` branch and merges it into a new
# sync/<date> branch. Re:Lad's history never gets Mihon's own commits, yet git can still merge
# every Mihon update normally: the previous import is the common ancestor.
#
#   bash plus/tools/import-mihon.sh                 # newest commit of the followed branch (plus/upstream.properties)
#   bash plus/tools/import-mihon.sh v0.21.4         # a Mihon tag
#   bash plus/tools/import-mihon.sh upstream/main   # any Mihon branch or commit
#
# Then: resolve conflicts if any (keep the meaning of the hooks in plus/HOOKS.md) and `git commit`,
# build and test, and finally
#   git switch main && git merge --ff-only sync/<date> && git push origin main mihon
#
# Set EXTRA_TRAILER to append a trailer line (e.g. Co-Authored-By) to the commits it creates.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    echo "Есть незакоммиченные изменения. Сначала закоммить или убери их." >&2
    exit 1
fi

# Mihon's branches go to upstream/*, its tags to upstream/tags/* (never into our own tags).
if ! git remote get-url upstream >/dev/null 2>&1; then
    git remote add upstream https://github.com/mihonapp/mihon.git
    git remote set-url --push upstream no_push
    git config remote.upstream.tagOpt --no-tags
    git config --replace-all remote.upstream.fetch '+refs/heads/*:refs/remotes/upstream/*'
    git config --add remote.upstream.fetch '+refs/tags/*:refs/remotes/upstream/tags/*'
fi
git fetch --quiet upstream
git fetch --quiet origin

tracked="$(sed -n 's/^trackedBranch=//p' plus/upstream.properties)"
ref="${1:-upstream/$tracked}"

if git rev-parse -q --verify "refs/remotes/upstream/tags/$ref^{commit}" >/dev/null; then
    target="$(git rev-parse "refs/remotes/upstream/tags/$ref^{commit}")"
    label="$ref"
else
    target="$(git rev-parse --verify "$ref^{commit}")"
    label="$(git for-each-ref --points-at "$target" --format='%(refname:lstrip=4)' refs/remotes/upstream/tags | head -1)"
    [[ -n "$label" ]] || label="${ref#upstream/}@$(git rev-parse --short "$target")"
fi

# The local mihon branch must contain what is already on GitHub.
if git rev-parse -q --verify refs/remotes/origin/mihon >/dev/null; then
    if ! git rev-parse -q --verify refs/heads/mihon >/dev/null; then
        git branch mihon origin/mihon
    elif ! git merge-base --is-ancestor origin/mihon mihon; then
        echo "Локальная ветка mihon расходится с origin/mihon. Разберись вручную." >&2
        exit 1
    fi
fi

imported="$(git log -1 --format='%(trailers:key=Mihon-Commit,valueonly)' mihon | tr -d '[:space:]')"
if [[ -z "$imported" ]]; then
    echo "В последнем коммите ветки mihon нет строки Mihon-Commit." >&2
    exit 1
fi
if [[ "$imported" == "$target" ]]; then
    echo "Mihon $label уже импортирован. Нечего делать."
    exit 0
fi
if git merge-base --is-ancestor "$target" "$imported"; then
    echo "Mihon $label старее уже импортированной версии." >&2
    exit 1
fi

count="$(git rev-list --count "$imported..$target")"
trailer="${EXTRA_TRAILER:+$'\n'$EXTRA_TRAILER}"

new_import="$(git commit-tree "$target^{tree}" -p mihon -F - <<EOF
Import Mihon $label

Unmodified source code of Mihon $label:
https://github.com/mihonapp/mihon/tree/$target
$count Mihon commits since the previous import:
https://github.com/mihonapp/mihon/compare/$imported...$target

Mihon-Ref: $label
Mihon-Commit: $target$trailer
EOF
)"
git branch -f mihon "$new_import"

sync="sync/$(date +%Y-%m-%d)"
n=2
while git rev-parse -q --verify "refs/heads/$sync" >/dev/null; do
    sync="sync/$(date +%Y-%m-%d)-$n"
    n=$((n + 1))
done
git switch --quiet -c "$sync" main

echo "Импортирован Mihon $label ($count новых коммитов Mihon) в ветку mihon."
if git merge --no-ff --quiet -m "Merge Mihon $label$trailer" mihon; then
    echo "Влит в $sync без конфликтов. Дальше: собрать, проверить и перенести в main."
else
    echo
    echo "Конфликты при вливании в $sync:"
    git diff --name-only --diff-filter=U | sed 's/^/  /'
    echo
    echo "Разреши их, сохраняя смысл правок из plus/HOOKS.md, затем: git add <файлы> && git commit"
    exit 2
fi
