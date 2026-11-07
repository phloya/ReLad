# Сравнение моделей улучшения сканов

Скрипты, которыми выбрана модель для `plus/enhance` (см. `plus/DECISIONS.md`, «Улучшение сканов»).
Работают на компьютере, ncnn на процессоре. В приложение не входят.

```bash
uv venv venv && VIRTUAL_ENV=venv uv pip install ncnn numpy pillow scipy
mkdir -p models pages out
venv/bin/python -I fetch_models.py models      # модели-кандидаты по закреплённым версиям
venv/bin/python -I make_page.py pages          # чистая страница truth.png и «сканы» small/bad/worst/yellow
venv/bin/python -I bench.py models pages out worst bad small
venv/bin/python -I grid.py out worst 700 120 340 worst-bubble.png truth bicubic waifu2x-n1 cugan-se-d3
```

- `make_page.py` рисует страницу, похожую на мангу (растр, штриховка, линии скорости, мелкий
  текст), и портит её: уменьшение, размытие, зерно, серая/жёлтая бумага, сильный JPEG.
  Шрифты берутся из macOS. Глава 3 тестовой манги — это `worst.jpg`, `bad.jpg`, `yellow.jpg`,
  `small.jpg` под именами `001.jpg`–`004.jpg` в папке `ReLad Test/Chapter 3`.
- `bench.py` прогоняет каждую модель по целой странице и печатает PSNR к чистой странице и
  остаток шума на местах, где в оригинале белая бумага (`white_noise`, меньше — чище).
  PSNR на растре мало что значит: точки растра после уменьшения не восстановить. Смотрите картинки.
- `tiled.py` — прототип алгоритма телефона: Real-CUGAN по кусочкам с общими SE-признаками.
  Режимы: `nosync`, `sample32` (как realcugan по умолчанию), `tiles200` (как в приложении), `exact`.
- `grid.py` складывает одинаковые кусочки результатов в одну картинку для сравнения.
- `tone.py` — сглаживание «неразрешимого» растра до нейросети, тот же алгоритм, что
  `plus/enhance/.../Screentone.kt` (на нём подбирались пороги). Пишет сглаженную страницу и
  маску растра: `venv/bin/python -I tone.py page.jpg out.png`.
- `real_bench.py` — объективная проверка на настоящих страницах: чистая страница портится
  (уменьшение, размытие, зерно, JPEG, цвет бумаги — как в `make_page.py`, плюс «мелкие»
  варианты, где растр остаётся в 2–3 px), улучшается как на телефоне со сглаживанием растра и
  без, оба результата сравниваются с оригиналом (PSNR, SSIM, шум на белой бумаге, тон без
  пятен). `venv/bin/python -I real_bench.py models out page1.png page2.jpg ...`
  Страницы манги владельца в репозиторий не кладём.
