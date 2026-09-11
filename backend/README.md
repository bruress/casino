# Backend

Легкий Java backend без внешних зависимостей. Основные игровые операции авторитетны на сервере.

## Запуск

```bash
javac -d backend/out backend/src/main/java/com/casino/balloon/BalloonCasinoServer.java
java -cp backend/out com.casino.balloon.BalloonCasinoServer
```

По умолчанию API доступен на `http://localhost:8080`.

## Проверяемые операции

- `GET /api/health`
- `GET /api/config`
- `PUT /api/admin/config`
- `GET /api/player`
- `GET /api/history`
- `GET /api/leaderboard`
- `POST /api/rounds/start`
- `GET /api/rounds/{id}`
- `POST /api/rounds/{id}/cashout`

Конфигурация хранится в `config/game-config.json`.
