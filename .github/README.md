# Re:Lad

**A smart manga reader for Android, built on [Mihon](https://github.com/mihonapp/mihon).**

> 🚧 Early development. There are no public builds yet.

Re:Lad is a fork of Mihon, the free and open source manga reader. It stays close to
upstream Mihon and adds AI-powered features on top:

- **Panel-by-panel reading:** the app finds panels on a page and zooms into them one by one.
- **On-page translation:** speech bubbles, sound effects and background text are translated
  in place, while you read, including into languages that rarely get official translations
  (e.g. Uzbek and Kazakh).
- **Per-title glossaries:** names, techniques and places stay consistent across chapters.
- **Scan enhancement:** upscaling and cleanup of blurry or compressed pages.
- **Spoiler-free recaps and character info,** based only on chapters you've already read.

Re:Lad is meant for **your own collection**: local files, your own server (Komga, Kavita)
and legal sources. It hosts no content.

Re:Lad installs next to Mihon (application ID `app.relad`) and doesn't replace it.

## Development

Our code lives in [`plus/`](../plus/README.md), separate from Mihon's code.
Every change to Mihon's files is a small, marked hook listed in [`plus/HOOKS.md`](../plus/HOOKS.md).
The roadmap is in [`plus/PLAN.md`](../plus/PLAN.md) (in Russian).

```bash
./gradlew :app:assembleDebug
```

## Credits and license

Re:Lad is based on [Mihon](https://github.com/mihonapp/mihon) (© 2024 Mihon Open Source Project,
© 2015 Javier Tomás) and, like Mihon, is licensed under the
[Apache License 2.0](../LICENSE). Re:Lad isn't affiliated with or endorsed by the Mihon project.

---

### По-русски

Re:Lad — умная читалка манги для Android на основе Mihon. Она умеет показывать страницу
по кадрам, переводить облачка и звуковые эффекты прямо на странице (в том числе на узбекский
и казахский), держит единый глоссарий для каждого тайтла и улучшает качество сканов.
Проект в ранней разработке, план — в [`plus/PLAN.md`](../plus/PLAN.md).
