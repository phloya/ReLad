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

## Наши файлы (конфликтов с Mihon не бывает)

| Путь | Что это |
|---|---|
| `plus/app-res/common/` | Общие ресурсы: передний план иконки, монохромная иконка, логотип `ic_mihon` (имя оставлено, чтобы заставка, уведомления и шапка «Ещё» показывали Re:Lad без правок кода), строки `plus_*` |
| `plus/app-res/release/` | Название «Re:Lad» и фон иконки для выпусков |
| `plus/app-res/dev/` | Название «Re:Lad Dev» и тёмный фон иконки для отладочных сборок |
| `app/src/main/java/plus/app/donate/PlusDonateScreen.kt` | Экран донатов Re:Lad. Ссылки задаются в `PlusDonations.links`; пока список пуст, пункт меню скрыт |

## Отложено (см. PLAN.md, раздел 5)

- Ключи трекеров Mihon и ссылки `mihon://`, `app.mihon://` в `AndroidManifest.xml` и `data/track/*/…Api.kt`.
- Схема `tachiyomi://` для добавления репозиториев расширений — общая для всех форков Tachiyomi, её не трогаем.
