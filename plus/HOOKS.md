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
| `app/build.gradle.kts` → тот же блок версии | Поле `BuildConfig.MIHON_VERSION_CODE` — номер версии Mihon из его строки `versionCode` | Номер Mihon нужен миграциям настроек (см. следующую строку) |
| `app/.../tachiyomi/App.kt` → `initializeMigrator()` | Вместо `BuildConfig.VERSION_CODE` используется `BuildConfig.MIHON_VERSION_CODE` | Миграции настроек Mihon помечены номерами версий Mihon (34, 39…) и запускаются, когда номер растёт. С нашим номером (0.1.0 = 100) будущие миграции Mihon (40, 41…) у пользователей Re:Lad никогда бы не запустились. Свои миграции Re:Lad будут в `plus:core` со своим счётчиком |
| `app/.../data/updater/AppUpdateChecker.kt` → `RELEASE_TAG` | `v…` → `relad-v…` | Теги Re:Lad — `relad-vX.Y.Z`, потому что `vX.Y.Z` заняты историей Tachiyomi и Mihon. Ссылка «Что нового» ведёт на наш выпуск. Сравнение версий Mihon убирает из тега всё, кроме цифр и точек, так что приставка ему не мешает |
| Workflow `Update website` (настройка GitHub, файл не менялся) | Отключён в настройках Actions репозитория | При каждом выпуске он стучится в репозиторий сайта Mihon и у нас падает |

Workflow Mihon `build.yml` (сборка, тесты, стиль кода на каждую отправку в `main` и PR)
работает у нас без изменений. Workflow `release.yml` Mihon запускается только в
`mihonapp/mihon`, у нас его заменяет `relad-release.yml`.

## Основа для функций (ветка `relad/core`)

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `settings.gradle.kts` | Вместо `include(":plus:core")` подключаются все папки `plus/*` с `build.gradle.kts` | Новый модуль функции (`plus/translate`…) не требует правки файлов Mihon, и ветки функций не конфликтуют между собой |
| `app/build.gradle.kts` → `dependencies` | Вместо `implementation(projects.plus.core)` приложение зависит от всех модулей `plus/*` | То же |
| `app/.../presentation/more/MoreScreen.kt` | Пункт `PlusMoreEntry()` после переключателей «Только скачанное» и «Инкогнито» | Вход на экран Re:Lad |
| `app/.../reader/viewer/pager/PagerPageHolder.kt` | `page.statusFlow` → `PlusReader.statusFlow(...)`, `page.stream` → `PlusReader.stream(...)` | Страница проходит через конвейер Re:Lad (перевод, улучшение, раскраска) и перезагружается, когда готова обработанная версия |
| `app/.../reader/viewer/webtoon/WebtoonPageHolder.kt` | То же для режима ленты | То же |

## Чтение по кадрам (ветка `relad/panels`)

Вся логика — в `app/src/main/java/plus/app/panels/` и `plus/panels`. Когда режим выключен,
каждая точка входа сразу возвращает обычное поведение Mihon.

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `app/.../reader/viewer/pager/Pager.kt` → `dispatchTouchEvent` | `super.dispatchTouchEvent(ev)` → `PlusPanels.dispatchTouch(this, ev) { super.dispatchTouchEvent(it) }` | В режиме кадров свайп ведёт к следующему кадру, а не тащит страницу и не двигает картинку |
| `app/.../reader/viewer/pager/PagerViewer.kt` → `onReaderPageSelected` | Вызов `PlusPanels.onPageSelected(this, page, forward)` | Страница открывается на первом кадре (или на последнем, если пришли назад) |
| `app/.../reader/viewer/pager/PagerViewer.kt` → `moveRight`, `moveLeft` | В начале: `if (PlusPanels.onPagerMove(...)) return` | Нажатия, клавиши и свайпы сначала идут к следующему кадру; после последнего кадра страница перелистывается сразу (без «панорамы» Mihon) |
| `app/.../reader/viewer/pager/PagerPageHolder.kt` → `setImage` | Вызов `PlusPanels.onPagerImage(viewer, this, source)` после разрезания/поворота | Кадры ищутся на той картинке, которую показывает читалка |
| `app/.../reader/viewer/pager/PagerPageHolder.kt` → `Config(landscapeZoom = …)` | `viewer.config.landscapeZoom` → `PlusPanels.landscapeZoom(viewer, …)` | «Приближение широких страниц» Mihon через полсекунды перебивало бы кадр |
| `app/.../reader/viewer/webtoon/WebtoonPageHolder.kt` → `setImage` | Вызов `PlusPanels.onWebtoonImage(viewer, frame, source)` | Пустые полосы ленты для деления на экраны |
| `app/.../reader/viewer/webtoon/WebtoonViewer.kt` → `scrollUp`, `scrollDown` | В начале: `if (PlusPanels.onWebtoonScroll(...)) return` | Нажатия и клавиши в ленте ведут к следующему куску, а не на ¾ экрана |
| `app/.../presentation/reader/appbars/ReaderBottomBar.kt` | Кнопка `PanelsReaderButton()` перед кнопкой настроек | Включение и выключение режима прямо в читалке |

Свайпы в ленте ловятся без правок Mihon: `RecyclerView.OnFlingListener` и слушатель прокрутки
ставятся из нашего кода.

## Перевод страниц (ветка `relad/translate`)

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `app/.../mihon/app/di/AppGraph.kt` | Поле `downloadProvider` | Перевод глав из меню загружает страницы теми же загрузчиками, что и читалка (`ChapterLoader`), а ему нужен `DownloadProvider` |
| `app/.../presentation/manga/components/MangaBottomActionMenu.kt` | Параметр `onTranslateClicked` и кнопка «Перевести» (значок-глобус) в меню выделенных глав | Перевести выбранные главы в фоне, не открывая их |
| `app/.../presentation/manga/MangaScreen.kt` → `SharedMangaBottomActionMenu` | Параметр `manga`, кнопка вызывает `ChapterTranslation.start` | То же |
| `app/.../presentation/reader/ReaderPageActionsDialog.kt` | Параметр `plusActions` (кнопки в ряд после «Сохранить») | Кнопка «Перевести заново» (эту страницу или всю главу) в меню долгого нажатия на страницу |
| `app/.../ui/reader/ReaderActivity.kt` → `ReaderPageActionsDialog(...)` | `plusActions = { PlusPageActions(...) }` | То же |
| `app/.../presentation/reader/appbars/ReaderBottomBar.kt` | `PlusReaderButton()` перед кнопкой настроек | Кнопка-глобус: «Перевести главу», потом переключение «оригинал / перевод» открытой главы; видна, только пока включён перевод |
| `app/.../presentation/reader/appbars/ReaderTopBar.kt` → меню ⋯ | `addAll(plusReaderMenu())` после «Поделиться» | «Перевести главу»; у переведённой главы — «Оригинал» и «Перевод (язык)», отмечен показанный |
| `app/.../presentation/manga/MangaScreen.kt` → `MangaChapterListItem(scanlator = …)` | `PlusChapterLabel.withScanlator(...)` | «Перевод» или «Перевод 12/20» в строке главы, перед именем сканлейтеров |

## Тестовые выпуски (ветка `relad/beta-release`)

| Файл Mihon | Что изменено | Зачем |
|---|---|---|
| `app/build.gradle.kts` → блок версии Re:Lad | Номер для `versionCode` берётся из части до `-` | Тестовая версия `0.2.0-beta.1` получает номер 0.2.0 |
| `domain/.../release/interactor/GetApplicationRelease.kt` → `isNewVersion` | Для обычных сборок вместо цикла Mihon вызывается `plusIsNewer`: числа сравниваются по порядку, выпуск новее своей тестовой версии | Цикл Mihon читал `0.2.0-beta.1` как `0.2.0.1`, падал, если у выпуска меньше чисел, и считал `0.1.5` новее `0.2.0` |
| `domain/src/test/.../GetApplicationReleaseTest.kt` | Два теста: тестовые версии и версии, которые отличаются не в одном числе | Проверка правки выше |

## Наши файлы (конфликтов с Mihon не бывает)

| Путь | Что это |
|---|---|
| `plus/app-res/common/` | Общие ресурсы: передний план иконки, монохромная иконка, логотип `ic_mihon` (имя оставлено, чтобы заставка, уведомления и шапка «Ещё» показывали Re:Lad без правок кода), строки `plus_*` |
| `plus/app-res/release/` | Название «Re:Lad» и фон иконки для выпусков |
| `plus/app-res/dev/` | Название «Re:Lad Dev» и тёмный фон иконки для отладочных сборок |
| `app/src/main/java/plus/app/donate/PlusDonateScreen.kt` | Экран донатов Re:Lad. Ссылки задаются в `PlusDonations.links`; пока список пуст, пункт меню скрыт |
| `plus/core/` | Модуль `:plus:core`: конвейер страниц (`pipeline/`), кеш обработанных страниц, подключение к ИИ (`ai/`: OpenAI-совместимые, Gemini, Claude), зашифрованное хранилище ключей (`secret/`), настройки (`settings/`) |
| `app/src/main/java/plus/app/` | Наш код внутри приложения: `Plus.kt` (общие объекты), `PlusFeature.kt` (список функций), `reader/PlusReader.kt` (две точки входа в читалку), `ui/` (экран Re:Lad и ИИ-провайдеры), по пакету на функцию (`translate/`, `enhance/`, `colorize/`) |
| `plus/tools/test-manga/` | Генератор тестовой главы (облачка на японском, корейском, китайском, английском) для проверки на эмуляторе |
| `plus/panels/` | Модуль `:plus:panels`: поиск кадров (`PanelDetector`), порядок чтения (`ReadingOrder`), план приближений (`PanelPlanner`), деление ленты (`StripPlanner`), кеш (`PanelStore`) |
| `app/src/main/java/plus/app/panels/` | Чтение по кадрам в приложении: функция и настройки (`PanelsFeature`), точки входа (`PlusPanels`), режим «страницы» (`PagerPanels`), затемнение (`PanelFocusView`), режим «лента» (`WebtoonPanels`), кнопка в читалке |
| `plus/app-res/common/values*/strings_panels.xml`, `drawable/plus_ic_panels.xml` | Строки (en, ru) и значок чтения по кадрам |
| `plus/tools/test-manga/MakePanelPages.java` | Синтетическая манга с номерами кадров для проверки порядка на эмуляторе |
| `plus/README.md`, `plus/PLAN.md`, `plus/LICENSES.md` | Описание папки, план проекта, реестр лицензий |
| `.github/README.md` | Страница Re:Lad на GitHub. GitHub показывает её вместо корневого `README.md` Mihon, который мы не трогаем |
| `.github/workflows/relad-release.yml` | Выпуск по тегу `relad-vX.Y.Z`: подписанные APK, проверка отпечатка подписи, черновик выпуска |
| `.github/workflows/relad-upstream-check.yml` | Еженедельная проверка Mihon: пробное слияние, задача (issue) при новинках или конфликтах |
| `plus/version.properties` | Номер версии Re:Lad |
| `plus/upstream.properties` | За какой веткой Mihon мы идём |
| `plus/tools/import-mihon.sh` | Импорт новой версии Mihon одним коммитом в ветку `mihon` и вливание в ветку `sync/…` |
| `plus/tools/upload-signing-secrets.sh` | Загрузка ключа подписи в секреты GitHub |

## Отложено (см. PLAN.md, раздел 5)

- Ключи трекеров Mihon и ссылки `mihon://`, `app.mihon://` в `AndroidManifest.xml` и `data/track/*/…Api.kt`.
- Схема `tachiyomi://` для добавления репозиториев расширений — общая для всех форков Tachiyomi, её не трогаем.
