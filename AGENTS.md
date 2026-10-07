# AGENTS.md — руководство для агентов и разработчиков StabCam

Android-приложение «только видео» с **собственной гиро-стабилизацией**, шумоподавлением, LUT, HLG и обработкой после съёмки. Целевое устройство: **OnePlus 15R (Snapdragon, SM8845)**, но код написан под любые Android 12+ с Camera2.
Язык интерфейса и документации — русский; комментарии в коде — английские.

## 1. Быстрый старт

```bash
# Нужны JDK 17+ и Android SDK (platform 36, build-tools 36). Путь SDK: local.properties → sdk.dir=...
./gradlew assembleDebug assembleRelease testDebugUnitTest --no-daemon -q
```

* `./gradlew` уже в репозитории. Зависимостей почти нет (только Kotlin stdlib, **без AndroidX**, UI целиком кодом, без XML-экранов кроме главного).
* Если SDK нет: скачать `commandlinetools` с dl.google.com, `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`, принять лицензии.
* Сеть может идти через прокси (см. переменные `HTTPS_PROXY`); Gradle сам подхватывает `JAVA_TOOL_OPTIONS`.
* Сборка подписывается общим ключом `ci/stabcam-ci.jks` (пароль публичен; это не защита, а удобство, чтобы обновления ставились поверх). Свой ключ: секреты `SIGNING_*` (см. README).
* Перед коммитом **всегда**: сборка debug+release и юнит-тесты (команда выше). Тестов на телефоне нет — проверка пользователем.

## 2. Карта кода (`app/src/main/java/com/gl1ch5/stabcam/`)

| Пакет | Назначение |
|---|---|
| `ui/` | `MainActivity` (экран камеры), `SettingsActivity` (вкладки), `PostActivity` (очередь обработки), `LutPicker`, `QuickMenu`, `ModuleInstallDialog`, `UpdateDialog`, `LogActivity`, `Fx` (общие анимации), `RecordButton`, `AspectFrameLayout` |
| `camera/` | `CameraCaps` (возможности камеры, интринсики, отчёт), `VideoCamera` (Camera2: сессия, запись, vendor-теги, диагностика OIS) |
| `stab/` | **Ядро.** `StabPipeline` (SurfaceTexture → GL → превью + энкодер), `Shaders` (GLSL), `Stabilizer` (живое сглаживание), `OfflineStabilizer` + `OrientationPath` (предвидение), `FrameFit` (точная проверка «нет чёрных краёв»), `HorizonLock`, `GyroTracker`, `GyroLog` (.gcsv), `AxisCalibrator`, `StabRecorder` (видео+звук → MP4), `OfflineProcessor` (декодер → GL → энкодер), `PostJobs`/`ProcessingService`, `Mp4Tagger`, `EglCore`, `Quat` |
| `config/` | `ConfigRepository` (слои конфига), `AppConfig` (типизированный доступ), `Presets` (уровни: сила, шумодав…), `QuickProfiles` (быстрые профили) |
| `lut/` | `Luts` (встроенные стили генерируются кодом, парсер `.cube`) |
| `module/` | `ModuleManager` — формат `.module`, проверка, порядок, настройки модов (см. `docs/MODULES.md`) |
| `update/` | `Updater` (релиз `nightly` на GitHub), `InstallReceiver` |
| `util/` | `Logger` (кольцо + файл, перехват падений) |

Ресурсы: `res/layout/activity_main.xml` (главный экран), `assets/config/default.json` + `presets/*.json` (пресеты устройств), `assets/modules/*.module` (каталог модов).

## 3. Потоки данных

**Живая запись (STAB):** Camera2 → `SurfaceTexture` (OES) → `StabPipeline` (поток GL) → [шумодав по времени → warp по строкам (rolling shutter) + бикубика + резкость + LUT] → окно превью **и** энкодер (`StabRecorder`, `MediaCodec` + AAC + `MediaMuxer`). Матрицы для строк считает `Stabilizer.rowMatrices` из ориентации гироскопа (`GyroTracker`, 400 Гц, часы `REALTIME` = часы кадров).

**ПОСТ:** запись без обработки + `.gcsv` (гироскоп, формат Gyroflow) + `.grav.csv` + `.meta.json` (время и выдержка каждого кадра) в `files/post/`. Потом `OfflineProcessor` (в `ProcessingService`): траектория целиком → гауссово сглаживание в обе стороны → декодер → GL → энкодер. Звук копируется без перекодирования.

**Конфиг (слои, поздний побеждает):** `default.json` → пресет устройства (по `match`, наибольший `priority`) → **включённые моды** (по порядку) → пользовательский `config_user.json`. Все ключи, которые модам разрешено менять, — в `ModuleManager.ALLOWED`.

## 4. Как добавить…

* **Настройку.** Ключ в `default.json` → геттер в `AppConfig` → строка в `SettingsActivity` (`toggle`/`choice`/`slider`, пишут `repo.set(path, value)`); если влияет на конструктор конвейера, достаточно возврата на камеру (экран сам перезагрузит конфиг и переоткроет камеру). Разрешить модам: `ModuleManager.ALLOWED` (+ подпись в `LABELS`).
* **Пресет устройства.** JSON в `assets/config/presets/` с `match` (подстрока без регистра: `manufacturer`, `brand`, `model`, `device`, `socManufacturer`, `socModel`) и `priority`. Код менять не нужно. Новые пресеты проверяет `ConfigLoadTest`.
* **Быстрый профиль.** Запись в `QuickProfiles.builtin` (ключ→значение).
* **Мод в каталог.** Файл `assets/modules/*.module`; формат — `docs/MODULES.md`.
* **Uniform в шейдер.** Добавить в `Shaders` (и в списки `names` у `StabPipeline` и `OfflineProcessor`: **оба**), задать значение в `draw()` обоих. Шейдеры живого и офлайн-режима общие.
* **Анимацию кнопки.** Использовать `Fx` (`pop`, `shake`, `pulse`, `slideText`, `spin`) — все кнопки должны реагировать одинаково (нажатие: `pressable`).

## 5. Грабли, на которых уже наступили (читать обязательно)

1. **Регулярные выражения на Android (ICU) строже, чем на JVM.** Закрывающая `}` в шаблоне обязана экранироваться (`\\}`), иначе `PatternSyntaxException` только на телефоне. Юнит-тесты это не ловят; есть тест `PortableRegexTest`, ищущий такие случаи.
2. **Матрицу `SurfaceTexture` (`uST`) не использовать.** Камера отдаёт кадры уже повёрнутыми под портрет; расчёты гироскопа и интринсик ведутся в **неповёрнутой сенсорной системе** (ландшафт). Шейдер берёт «сырые» координаты буфера (`texture(uTex, q)`, верхняя строка = 0). Превью поворачивается на 90° вручную (`uPreview`, 4 варианта; долгое нажатие на надпись режима).
3. **Оси гироскопа → система кадра** для задней камеры с `SENSOR_ORIENTATION=90`: `["-y","-x","-z"]` (подтверждено на OnePlus 15R). Матрица перехода обязана быть собственным поворотом (det = +1). Автокалибровка: `AxisCalibrator`.
4. **Размеры массивов в GL.** `Stabilizer.identityRows` заполняет по размеру массива (раньше падало на 9-элементном).
5. **Запас кропа считать точно** (`FrameFit`), а не по `tan(угол)`: для поворота на 6° в кадре 16:9 нужен зум ≈1.3 (рыбий край уже на 35°). Крен требует ещё больше. Иначе — чёрные углы.
6. **Камера объявляет не всё.** OnePlus скрывает от сторонних приложений OIS и 4K60 (в `getOutputSizes` для MediaRecorder только 4K@30). 4K60 при этом **работает** через SurfaceTexture на 60 fps. Пресет `oneplus-15r` ставит `camera.forceAllQualities`. Если режим не стартует, `VideoCamera` вызывает `onQualityUnsupported` и UI спускается на режим ниже.
7. **UI-операции только на главном потоке.** Колбэки камеры идут из потока камеры: любую правку View оборачивать в `runOnUiThread` (был вылет при старте).
8. **Исключения на потоках GL/камеры** не должны убивать приложение: у обоих `Handler` переопределён `dispatchMessage` с логированием. Падения пишутся в `stabcam.log` (метка `CRASH`), при следующем запуске приложение включает безопасный режим (STAB и HLG выкл) и показывает трассу.
9. **Шейдеры компилируются только на устройстве.** Ошибка в GLSL не видна в сборке: в живом пути её ловит `ensureStab` (падаем в обычную запись), в офлайн-пути — `OfflineProcessor.run` (сообщение пользователю). Проверять синтаксис вдумчиво (ES 3.0, `precision highp sampler3D`).
10. **`MediaMuxer`:** `moov` в конце файла; `Mp4Tagger` дописывает теги только если это так. Дорожка звука должна быть добавлена до `start()`.
11. **Часы:** метки `SurfaceTexture`, гироскопа и `AudioRecord.getTimestamp(TIMEBASE_BOOTTIME)` — одна шкала (boottime). Всё остальное нормируется на `baseTs` — время первого кадра.
12. **Обновления:** релиз `nightly` пересоздаётся каждый раз (`gh release delete` + `create`), поэтому имя APK = версия (`StabCam-nightly.ДАТА.SHA.apk`) и сравнение идёт по нему. Лимит GitHub API (60/ч без токена) обходится кэшем ETag и страницей релиза.

## 6. Тесты

Чистая математика (кватернионы, сглаживание, `FrameFit`, горизонт, калибровка, разбор модулей, `.gcsv`, конфиги) покрыта юнит-тестами в `app/src/test/`. Всё, что завязано на GL/Camera2/MediaCodec, проверяется **только на телефоне**: после изменений в этих местах попросить пользователя прислать лог (Настройки → Система → Лог) и описать результат. В сообщении пользователю всегда прямо писать, что на устройстве не проверено.

Метки логов: `App`, `VideoCamera`, `Stab` (раз в 2 с: fps, интервал кадров, пропуски, поправка, кроп, нагрев, угловая скорость по осям), `Gyro`, `Probe` (диагностика OIS), `Rec`, `Offline`, `Update`, `Module`, `LUT`, `Tag`, `CRASH`.

## 7. CI и релизы

* `.github/workflows/build.yml` — тесты + debug-APK на каждый push/PR.
* `.github/workflows/nightly.yml` — каждую ночь (если были коммиты), на push в ветку по умолчанию и вручную: release-APK → pre-release `nightly` + `SHA256SUMS.txt`. Версия: `0.1.0-nightly.ДАТА.SHA`, `versionCode` = номер запуска.
* Ветка разработки сессии (формат `ccr-…`) и трейлеры коммитов задаются в системных инструкциях сессии; PR создавать только по просьбе.

## 8. Чего не делать

* Не включать по умолчанию стоковый EIS вместе со STAB (обрезает и мылит кадр).
* Не расширять `ModuleManager.ALLOWED` ключами `update.*` или vendor-тегами камеры: моды не должны подменять источник обновлений или ломать сессию камеры.
* Не встраивать нейросетевой денойз в видеоконвейер: NAFNet-SIDD width32 (MIT) проверен, но ≈126 тайлов на кадр 4K — часы на ролик (подробности в `docs/ROADMAP.md`).
* Не гадать значения vendor-ключей OnePlus (`com.oplus.*`): использовать «Лабораторию vendor-ключей» (Настройки → Продвинутые) и смотреть на результат.
* Не менять оси гироскопа и поворот превью «на глаз» в коде: у пользователя есть автокалибровка и перебор в настройках.

## 9. Факты об устройстве-цели (OnePlus 15R, CPH2767, SM8845)

Основная камера id 0, LEVEL_3, часы кадров REALTIME, массив 4096×3072, фокус 5.59 мм, интринсики fx≈2903 (сенсорные пиксели). Объявлено: HLG10 да, OIS нет (но запрос OIS принимается и подтверждается в результате кадра), 4K@60 для MediaRecorder нет (но работает), high-speed 4K@120 есть, гироскоп ~470 Гц. Vendor-ключи запросов: `com.oplus.video.stabilization.mode` (значения 0–3 принимаются, смысл неизвестен), `com.oplus.camera.mode`, `com.oplus.is.sdk.camera.package`, `org.codeaurora.qcamera3.sessionParameters.SensorModesInConfig` и др.

## 10. Документы

`README.md` (для пользователей), `docs/MODULES.md` (формат модов), `docs/ROADMAP.md` (сделано/открыто), `docs/example.module`.

## Дополнительные gotchas (батч по отчётам)
- Порядок параметров конструктора `StabPipeline`: `denoiseAuto` — последний.
- `Stabilizer.ROWS = 16`; шейдер `uR[ROWS]` должен совпадать.
- Интринсики в StabPipeline по кадрам (`kCur`), не `kBase`.
- Сигма денойза зависит от ISO (`sigmaEff()`); `FrameLog` содержит iso третьим элементом.
- POST camera NR по умолчанию "minimal" — осознанный компромисс.
- Граница запаса в `Stabilizer` мягкая (`SOFT_KNEE` = 0.6, tanh). Жёсткий упор давал рывки, и на беге выход был трясучее входа. Любые изменения сглаживания проверять `WalkSimTest` (симуляция ходьбы и бега, метрика — угловое ускорение).
- `Stabilizer.marginUse` → `StabPipeline.marginUse()` → `VideoCamera.stabMarginUse()` → полоска в `OverlayView`.
