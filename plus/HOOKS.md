# Re:Lad: точки входа в код Mihon

Все наши правки в файлах Mihon помечены `// PLUS -->` … `// PLUS <--`
(в XML — комментарием `PLUS:`). Здесь перечислены все такие места.
Если при вливании новой версии Mihon возник конфликт в одном из этих файлов,
сохраняем смысл нашей правки и переносим её на новый код Mihon.

Цель — не больше 20–30 изменённых файлов Mihon на весь проект.

## M0.3 — ребрендинг

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `app/build.gradle.kts` → `applicationId` | `app.mihon` → `app.relad` | Re:Lad ставится рядом с Mihon, а не вместо него |
| `app/build.gradle.kts` → `sourceSets` | Подключены ресурсы из `plus/app-res/`. У отладочных сборок (debug, nightly, benchmark) убран `src/debug/res` Mihon | Название, иконка и логотип Re:Lad перекрывают ресурсы Mihon без правки его файлов |
| `app/.../data/updater/AppUpdateChecker.kt` → `GITHUB_REPO` | `mihonapp/mihon` → `phloya/ReLad` | Обновления приходят из выпусков Re:Lad |
| `app/.../presentation/more/MoreScreen.kt` | `onClickSupport` может быть `null`, тогда пункт «Поддержать нас» скрыт | Своих ссылок на донаты пока нет |
| `app/.../ui/more/MoreTab.kt` | Вместо `SupportUsScreen` Mihon открывается `PlusDonateScreen` | Донаты идут Re:Lad, а не Mihon |
| `app/.../ui/main/MainActivity.kt` | Отключён вызов `ShowDonationCampaign()` | Окно Mihon просит задонатить разработчику Mihon |
| `app/.../settings/screen/about/AboutScreen.kt` | Убраны политика конфиденциальности и соцсети Mihon. Добавлены «Основано на Mihon» и ссылка на GitHub Re:Lad | Указываем основу (Apache-2.0), не выдаём ссылки Mihon за свои |

## M0.4 — каркас `plus/`

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `settings.gradle.kts` | `include(":plus:core")` в конце списка модулей | Подключение модулей Re:Lad. Новые модули `plus:*` добавляются сюда же |
| `app/build.gradle.kts` → `dependencies` | `implementation(projects.plus.core)` | Приложение видит код Re:Lad |
| `.github/FUNDING.yml` | **Удалён** | Кнопка «Sponsor» на GitHub вела на донаты Mihon. Свой файл вернём, когда будут ссылки на донаты Re:Lad |

## M0.6 — автосборки и выпуски

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `gradle/build-logic/.../mihon/gradle/BuildConfig.kt` → `includeTelemetry` | Телеметрия по умолчанию выключена при любом `-Pdist` | Встроенный `google-services.json` принадлежит проекту Firebase Mihon. Данные Re:Lad туда попадать не должны |
| `app/build.gradle.kts` → блок `defaultConfig` после `sourceSets` | `versionName` и `versionCode` берутся из `plus/version.properties`. Строки версии Mihon выше не тронуты | Своя нумерация Re:Lad. Повышение версии в Mihon не создаёт конфликтов |
| `app/.../data/updater/AppUpdateChecker.kt` → `RELEASE_TAG` | `v…` → `relad-v…` | Теги Re:Lad — `relad-vX.Y.Z`, потому что `vX.Y.Z` заняты историей Tachiyomi и Mihon. Ссылка «Что нового» ведёт на наш выпуск. Сравнение версий Mihon убирает из тега всё, кроме цифр и точек, так что приставка ему не мешает |
| Workflow `Update website` (настройка GitHub, файл не менялся) | Отключён в настройках Actions репозитория | При каждом выпуске он стучится в репозиторий сайта Mihon и у нас падает |

Workflow Mihon `build.yml` (сборка, тесты, стиль кода на каждую отправку в `main` и PR)
работает у нас без изменений. Workflow `release.yml` Mihon запускается только в
`mihonapp/mihon`, у нас его заменяет `relad-release.yml`.

## Наши файлы (конфликтов с Mihon не бывает)

| Путь | Что это |
|---|---|
| `plus/app-res/common/` | Общие ресурсы: передний план иконки, монохромная иконка, логотип `ic_mihon` (имя оставлено, чтобы заставка, уведомления и шапка «Ещё» показывали Re:Lad без правок кода), строки `plus_*` |
| `plus/app-res/release/` | Название «Re:Lad» и фон иконки для выпусков |
| `plus/app-res/dev/` | Название «Re:Lad Dev» и тёмный фон иконки для отладочных сборок |
| `app/src/main/java/plus/app/donate/PlusDonateScreen.kt` | Экран донатов Re:Lad. Ссылки задаются в `PlusDonations.links`; пока список пуст, пункт меню скрыт |
| `plus/core/` | Модуль `:plus:core`, пока пустой. Наполняется в M1 |
| `plus/README.md`, `plus/PLAN.md`, `plus/LICENSES.md` | Описание папки, план проекта, реестр лицензий |
| `.github/README.md` | Страница Re:Lad на GitHub. GitHub показывает её вместо корневого `README.md` Mihon, который мы не трогаем |
| `.github/workflows/relad-release.yml` | Выпуск по тегу `vX.Y.Z`: подписанные APK, проверка отпечатка подписи, черновик выпуска |
| `.github/workflows/relad-upstream-check.yml` | Еженедельная проверка Mihon: пробное слияние, задача (issue) при новинках или конфликтах |
| `plus/version.properties` | Номер версии Re:Lad |
| `plus/upstream.properties` | За какой веткой Mihon мы идём |
| `plus/tools/upload-signing-secrets.sh` | Загрузка ключа подписи в секреты GitHub |

## Отложено (см. PLAN.md, раздел 5)

- Ключи трекеров Mihon и ссылки `mihon://`, `app.mihon://` в `AndroidManifest.xml` и `data/track/*/…Api.kt`.
- Схема `tachiyomi://` для добавления репозиториев расширений — общая для всех форков Tachiyomi, её не трогаем.
