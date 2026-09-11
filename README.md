# Воздушный шар

Рабочий MVP бонусной crash-игры с серверной генерацией исходов, бустерами, очками, историей, настройками и выразительным React-интерфейсом.

## Стек

- Backend: Java, встроенный `HttpServer`, без внешних зависимостей.
- Frontend: React, TypeScript, Tailwind CSS, Vite.
- Хранение MVP: in-memory состояние + `config/game-config.json` для параметров игры.

## Структура

```text
backend/                    Java backend
config/game-config.json      изменяемые игровые параметры
frontend/                   React/TypeScript/Tailwind frontend
AGENTS.md                   обязательные требования проекта
```

## Запуск backend

Нужен JDK 17+.

```bash
javac -d backend/out backend/src/main/java/com/casino/balloon/BalloonCasinoServer.java
java -cp backend/out com.casino.balloon.BalloonCasinoServer
```

Backend слушает `http://localhost:8080`.

## Запуск frontend

Нужен Node.js 20+.

```bash
cd frontend
npm install
npm run dev
```

Frontend доступен на `http://localhost:5173`. Vite проксирует `/api` на `http://localhost:8080`.

## Демо-пользователь

В прототипе есть один демонстрационный пользователь:

- имя: `Игрок`
- стартовый баланс: `2500` бонусных баллов
- игровые очки начисляются за уровни, cashout и бустер

## Обязательные сценарии проверки

1. **Выбор ставки и запуск игры**
   Откройте игру, выберите красный или зеленый шар, выберите ставку `50/150/300/600` и бустер `x1-x4`, нажмите "Начать". Backend спишет ставку и создаст раунд.

2. **Успешный cashout**
   Дождитесь первого уровня, нажмите "Забрать". Выплата фиксируется backend, шар продолжает лететь до crash, затем показывается итоговый экран.

3. **Проигрыш через crash**
   Не нажимайте "Забрать". После crash ставка сгорит, появятся очки, награда и запись в истории.

4. **Активация бустера**
   Выберите фрагмент с `x2`, `x3` или `x4`. Если шар достигнет серверного уровня бустера до cashout, коэффициент усилится, начислятся дополнительные очки.

5. **Управление параметрами**
   Откройте кнопку с шестеренкой в интерфейсе или измените `config/game-config.json`. Например, поменяйте `pointsPerLevel`, начните новый раунд и проверьте новое начисление очков.

## Математическая модель

Backend до старта раунда рассчитывает:

- точку crash;
- уровень бустера;
- hash результата для упрощенной provably fair проверки.

Crash рассчитывается из экспоненциального распределения:

```text
minCrashMultiplier + (-ln(1 - random) * crashAlpha * modeRisk)
```

Значение ограничивается `maxMultiplier`. Красная версия использует больший `redRisk`, зеленая - меньший `greenRisk`.

Коэффициент растет по времени:

```text
1 + elapsedSeconds * growthPerSecond
```

Если бустер активирован до cashout:

```text
currentMultiplier = baseMultiplier * booster
```

Cashout, выплаты, очки, награды и история рассчитываются только на backend.

## Конфигурация

Основные параметры находятся в `config/game-config.json`:

- `stakes`
- `redLevels`, `greenLevels`
- `redRisk`, `greenRisk`
- `minCrashMultiplier`, `maxMultiplier`, `crashAlpha`
- `redGrowthPerSecond`, `greenGrowthPerSecond`
- `multiplierPerLevel`
- `pointsPerLevel`
- `pointsCashoutBonus`
- `pointsBoosterBonus`
- `minWinOfferAmount`
- `popupTimeoutSeconds`
- `fixedSeed`

`fixedSeed` можно заполнить для воспроизводимых демонстрационных раундов.

## API

- `GET /api/health`
- `GET /api/config`
- `PUT /api/admin/config`
- `GET /api/player`
- `GET /api/rules`
- `GET /api/history`
- `GET /api/leaderboard`
- `POST /api/rounds/start`
- `GET /api/rounds/{id}`
- `POST /api/rounds/{id}/cashout`
- `POST /api/offers/activate`

## Известные ограничения MVP

- Состояние хранится in-memory и сбрасывается при перезапуске backend.
- Аутентификация и многопользовательская база данных не реализованы.
- Рейтинг использует демонстрационных соперников вокруг текущего игрока.
- Платежи и лотерейные операции имитационные.
- Полная криптографическая provably fair верификация заменена hash + раскрытием seed после завершения.
