MEMORY — краткая карта проекта DeadCycle

Цель: собирать и поддерживать внутреннюю «память» о структуре проекта, основных классах и местах для правок.

---- Общее
- Проект: DeadCycle (Minecraft Paper plugin)
- Структура: `src/main/java/me/korgan/deadcycle/...`, `src/main/resources/config.yml`, `pom.xml`.

---- Ключевые файлы и краткие описания (первые заметки)

- `DeadCyclePlugin.java`
  - Точка входа плагина: `onEnable()` создаёт менеджеры и регистрирует слушатели/команды.
  - Создаёт: `BaseManager`, `BaseResourceManager`, `EconomyManager`, `ProgressManager`, `BlockHealthManager`, `SiegeManager`, `ZombieWaveManager`, `PhaseManager`, GUI и пр.
  - Содержит периодическую задачу `tickSiege()` и запуск `phase`.

- `PhaseManager.java`
  - Управляет днями/ночами (BossBar, таймеры).
  - `switchToDay`/`switchToNight` — вызывают рестарт/стоп ночных систем (zombie, siege).
  - Используется как точка интеграции для поведения, завязанного на смену фазы.

- `siege/BlockHealthManager.java`
  - Управляет HP блоков базы, отображением частиц, логикой урона/ремонта и восстановлением сломанных блоков.
  - Читает `blocks_hp` из `config.yml`. Поддерживает понятия "сломанные" и "повреждённые" блоки.
  - Методы: `damage(Block,int)`, `repair(Block,int)`, `getMaxHp(Material)`, `clearStateAt(Location)`.
  - Содержит внутренний `BlockPos` для ключей картирования блоков.

- `base/WallUpgradeGUI.java`
  - GUI и механика прокачки стен: сканирует участки, тратит очки базы, апгрейдит блоки партиями.
  - Формула скорости: `blocks_per_tick_base + (builderLvl-1) * perLvl`.
  - Вызывает `plugin.blocks().clearStateAt(loc)` при установке блока.

- `system/TemporaryBlocksListener.java`
  - Слушает `BlockPlaceEvent` и планирует удаление поставленных блоков через фиксированный таймер (были внесены правки: не удалять блоки, поставленные `op` или с пермишном `deadcycle.admin`).
  - Используется для временных построек игроков (preview/демо).

- `base/BaseManager.java` (заметка, если есть)
  - Хранит центр базы, радиус, world name и проверку `isOnBase(Location)`.

- `siege/SiegeManager.java` (заметка, если есть)
  - Логика осады: запуск ночью, взаимодействие с зомби и `BlockHealthManager`.

- `kits`, `skills`, `shop` и другие пакеты
  - `KitManager`, `SkillManager`, GUI-классы: управление китами и скиллами.

- `WorldGen` (отдельный модуль/плагин в workspace)
  - `ArenaBuilder.java`, `BuildTask` и т.п. — генерация арен, детерминированная генерация, inplace/force флаги.

---- Конфигурация
- `config.yml` — содержит большинство настроек: `phase.*`, `zombies.*`, `base.*`, `blocks_hp.*`, `wall_upgrade.*`, `gates.*` (если есть), `repair_cost`, `kit_xp` и т.д.

---- Что сделано мной (кратко записано для истории)
- Добавлял прототип `GateManager`, `Gate`, `GateInteractListener` (управление воротами), интеграцию с `PhaseManager` и изменения в `TemporaryBlocksListener` (чтобы админские блоки не удалялись). Часть правок позже была отменена по вашему запросу.

---- Дальше (план действий в памяти)
1. Пройтись по каждому Java-файлу в `src/main/java/me/korgan/deadcycle` и дополнить описание (методы, точки интеграции).
2. Составить индекс изменений, где изменить при пожелании (пример: куда вставлять команды `dc gates ...`, куда hook`и для апгрейда ворот, список конфиг-параметров).
3. Поддерживать `MEMORY.md` актуальным: добавлять краткую инструкцию, куда править при новых задачах.

---- Примечание
Это начальный черновой файл памяти. Я начну полный автоматизированный проход по всем файлам кода и дополню `MEMORY.md` секциями для каждого файла (класса) — хотите, чтобы я начинал сейчас и добавлял подробные заметки по каждому Java-файлу? Если да — я продолжу и по завершении запишу файл и закоммичу.
