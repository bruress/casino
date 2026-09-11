package com.casino.balloon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BalloonCasinoServer {
    private static final Path CONFIG_PATH = Path.of("config", "game-config.json");
    private static final GameStore STORE = new GameStore();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(CONFIG_PATH.getParent());
        STORE.config = GameConfig.load(CONFIG_PATH);

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/config", BalloonCasinoServer::handleConfig);
        server.createContext("/api/admin/config", BalloonCasinoServer::handleAdminConfig);
        server.createContext("/api/player", BalloonCasinoServer::handlePlayer);
        server.createContext("/api/rules", BalloonCasinoServer::handleRules);
        server.createContext("/api/history", BalloonCasinoServer::handleHistory);
        server.createContext("/api/leaderboard", BalloonCasinoServer::handleLeaderboard);
        server.createContext("/api/rounds/start", BalloonCasinoServer::handleStartRound);
        server.createContext("/api/rounds", BalloonCasinoServer::handleRound);
        server.createContext("/api/offers/activate", BalloonCasinoServer::handleOffer);
        server.createContext("/api/health", exchange -> send(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();

        System.out.println("Balloon Casino backend listening on http://localhost:" + port);
    }

    private static void handleConfig(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        send(exchange, 200, STORE.config.toJson());
    }

    private static void handleAdminConfig(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            send(exchange, 200, STORE.config.toJson());
            return;
        }
        if (!allow(exchange, "PUT")) return;
        String body = readBody(exchange);
        GameConfig updated = STORE.config.applyPatch(body);
        updated.save(CONFIG_PATH);
        STORE.config = updated;
        send(exchange, 200, updated.toJson());
    }

    private static void handlePlayer(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        Player player = STORE.player;
        send(exchange, 200, "{" +
                "\"id\":\"" + esc(player.id) + "\"," +
                "\"name\":\"" + esc(player.name) + "\"," +
                "\"bonusBalance\":" + money(player.bonusBalance) + "," +
                "\"gamePoints\":" + player.gamePoints + "," +
                "\"boosters\":" + player.boostersJson() + "," +
                "\"tickets\":" + player.tickets +
                "}");
    }

    private static void handleRules(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        send(exchange, 200, "{" +
                "\"title\":\"Правила игры Воздушный шар\"," +
                "\"items\":[" +
                "\"Выберите красный шар на 12 уровней или зеленый шар на 9 уровней.\"," +
                "\"Выберите ставку 50, 150, 300 или 600 бонусных баллов и бустер x1-x4.\"," +
                "\"Ставка списывается backend при старте раунда после проверки баланса.\"," +
                "\"Кнопка Забрать доступна только после первого уровня.\"," +
                "\"Cashout фиксирует выигрыш, но шар продолжает лететь до серверной точки crash.\"," +
                "\"Если crash случился до cashout, ставка сгорает.\"," +
                "\"Бустер активируется только если шар достиг его уровня до cashout.\"," +
                "\"Игровые очки начисляются за уровни, cashout и бустер отдельно от бонусного баланса.\"," +
                "\"После раунда выдается дополнительная награда по серверным правилам.\"," +
                "\"Параметры игры меняются через config/game-config.json или admin API без правки кода.\"" +
                "]}");
    }

    private static void handleHistory(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        List<Round> copy = new ArrayList<>(STORE.history);
        copy.sort(Comparator.comparingLong((Round r) -> r.finishedAt).reversed());
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < copy.size(); i++) {
            if (i > 0) json.append(',');
            json.append(copy.get(i).historyJson());
        }
        json.append(']');
        send(exchange, 200, json.toString());
    }

    private static void handleLeaderboard(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "GET")) return;
        List<LeaderboardRow> rows = STORE.leaderboardRows();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) json.append(',');
            LeaderboardRow row = rows.get(i);
            json.append("{\"position\":").append(i + 1)
                    .append(",\"player\":\"").append(esc(row.player)).append("\"")
                    .append(",\"points\":").append(row.points)
                    .append(",\"current\":").append(row.current)
                    .append('}');
        }
        json.append(']');
        send(exchange, 200, json.toString());
    }

    private static void handleStartRound(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "POST")) return;
        String body = readBody(exchange);
        String idempotencyKey = Json.readString(body, "idempotencyKey", "");
        if (!idempotencyKey.isBlank() && STORE.startIdempotency.containsKey(idempotencyKey)) {
            Round existing = STORE.rounds.get(STORE.startIdempotency.get(idempotencyKey));
            send(exchange, 200, existing.stateJson(STORE.config, true));
            return;
        }

        String mode = Json.readString(body, "mode", "green").toLowerCase(Locale.ROOT);
        int stake = Json.readInt(body, "stake", 50);
        int booster = Json.readInt(body, "booster", 1);

        try {
            Round round = STORE.startRound(mode, stake, booster, idempotencyKey);
            send(exchange, 201, round.stateJson(STORE.config, true));
        } catch (GameException ex) {
            send(exchange, ex.status, "{\"error\":\"" + esc(ex.getMessage()) + "\"}");
        }
    }

    private static void handleRound(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        String rest = path.replaceFirst("^/api/rounds/?", "");
        if (rest.isBlank()) {
            send(exchange, 404, "{\"error\":\"round id required\"}");
            return;
        }
        String[] parts = rest.split("/");
        Round round = STORE.rounds.get(parts[0]);
        if (round == null) {
            send(exchange, 404, "{\"error\":\"round not found\"}");
            return;
        }

        if (parts.length == 2 && "cashout".equals(parts[1])) {
            if (!allow(exchange, "POST")) return;
            try {
                STORE.cashout(round.id);
                send(exchange, 200, round.stateJson(STORE.config, true));
            } catch (GameException ex) {
                send(exchange, ex.status, "{\"error\":\"" + esc(ex.getMessage()) + "\",\"round\":" + round.stateJson(STORE.config, true) + "}");
            }
            return;
        }

        if (!allow(exchange, "GET")) return;
        STORE.refreshRound(round);
        send(exchange, 200, round.stateJson(STORE.config, true));
    }

    private static void handleOffer(HttpExchange exchange) throws IOException {
        if (!allow(exchange, "POST")) return;
        String body = readBody(exchange);
        double cost = Json.readDouble(body, "cost", 0);
        int tickets = Json.readInt(body, "tickets", 0);
        if (cost <= 0 || tickets <= 0) {
            send(exchange, 400, "{\"error\":\"invalid offer\"}");
            return;
        }
        if (STORE.player.bonusBalance < cost) {
            send(exchange, 409, "{\"error\":\"not enough bonus points\"}");
            return;
        }
        STORE.player.bonusBalance = round2(STORE.player.bonusBalance - cost);
        STORE.player.tickets += tickets;
        send(exchange, 200, "{\"bonusBalance\":" + money(STORE.player.bonusBalance) + ",\"tickets\":" + STORE.player.tickets + "}");
    }

    private static boolean allow(HttpExchange exchange, String method) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return false;
        }
        if (!method.equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}");
            return false;
        }
        return true;
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Idempotency-Key");
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        addCors(exchange);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    static final class GameStore {
        final Player player = new Player();
        final Map<String, Round> rounds = new ConcurrentHashMap<>();
        final List<Round> history = new ArrayList<>();
        final Map<String, String> startIdempotency = new ConcurrentHashMap<>();
        final SecureRandom secureRandom = new SecureRandom();
        volatile GameConfig config;

        synchronized Round startRound(String mode, int stake, int booster, String idempotencyKey) {
            if (!Objects.equals(mode, "red") && !Objects.equals(mode, "green")) {
                throw new GameException(400, "unknown mode");
            }
            if (!config.stakes.contains(stake)) {
                throw new GameException(400, "stake is not allowed");
            }
            if (booster < 1 || booster > 4) {
                throw new GameException(400, "booster must be from x1 to x4");
            }
            if (player.bonusBalance < stake) {
                throw new GameException(409, "not enough bonus points");
            }

            player.bonusBalance = round2(player.bonusBalance - stake);
            Round round = Round.create(mode, stake, booster, config, secureRandom);
            rounds.put(round.id, round);
            if (!idempotencyKey.isBlank()) {
                startIdempotency.put(idempotencyKey, round.id);
            }
            return round;
        }

        synchronized void cashout(String roundId) {
            Round round = rounds.get(roundId);
            if (round == null) throw new GameException(404, "round not found");
            refreshRound(round);
            if (round.status == RoundStatus.CASHED_OUT || round.status == RoundStatus.FINISHED) return;
            if (round.status == RoundStatus.CRASHED) throw new GameException(409, "cashout after crash is rejected");
            if (round.currentLevel(config) < 1) throw new GameException(409, "cashout is available after first level");

            double current = round.currentMultiplier(config);
            if (current >= round.crashMultiplier) {
                finishCrash(round);
                throw new GameException(409, "balloon already crashed");
            }

            round.cashoutMultiplier = current;
            round.lockedPayout = round2(round.stake * current);
            round.status = RoundStatus.CASHED_OUT;
            round.cashoutAt = System.currentTimeMillis();
            round.points += config.pointsCashoutBonus;
            player.bonusBalance = round2(player.bonusBalance + round.lockedPayout);
            player.gamePoints += config.pointsCashoutBonus;
        }

        synchronized void refreshRound(Round round) {
            if (round.status == RoundStatus.FINISHED) return;

            int level = round.currentLevel(config);
            while (round.lastAwardedLevel < level) {
                round.lastAwardedLevel++;
                round.points += config.pointsPerLevel;
                player.gamePoints += config.pointsPerLevel;
                if (!round.boosterActive && round.status == RoundStatus.RUNNING && round.booster > 1 && round.lastAwardedLevel == round.boosterLevel) {
                    round.boosterActive = true;
                    round.points += config.pointsBoosterBonus;
                    player.gamePoints += config.pointsBoosterBonus;
                }
            }

            if (round.currentMultiplier(config) >= round.crashMultiplier) {
                if (round.status == RoundStatus.RUNNING) {
                    finishCrash(round);
                } else if (round.status == RoundStatus.CASHED_OUT) {
                    finishCashout(round);
                }
            }
        }

        private void finishCrash(Round round) {
            round.status = RoundStatus.CRASHED;
            round.finishedAt = System.currentTimeMillis();
            round.reward = Reward.generate(round, secureRandom);
            applyReward(round.reward);
            addHistoryOnce(round);
        }

        private void finishCashout(Round round) {
            round.status = RoundStatus.FINISHED;
            round.finishedAt = System.currentTimeMillis();
            round.reward = Reward.generate(round, secureRandom);
            applyReward(round.reward);
            addHistoryOnce(round);
        }

        private void addHistoryOnce(Round round) {
            if (!round.historySaved) {
                round.historySaved = true;
                history.add(round);
            }
        }

        private void applyReward(Reward reward) {
            if ("bonus_points".equals(reward.type)) {
                player.bonusBalance = round2(player.bonusBalance + reward.amount);
            }
            if ("booster".equals(reward.type)) {
                player.boosters.put(reward.label, player.boosters.getOrDefault(reward.label, 0) + 1);
            }
        }

        List<LeaderboardRow> leaderboardRows() {
            List<LeaderboardRow> rows = new ArrayList<>();
            rows.add(new LeaderboardRow("SkyAce", Math.max(2100, player.gamePoints + 180), false));
            rows.add(new LeaderboardRow("Redline", Math.max(1780, player.gamePoints + 65), false));
            rows.add(new LeaderboardRow(player.name, player.gamePoints, true));
            rows.add(new LeaderboardRow("CloudNine", Math.max(900, player.gamePoints - 75), false));
            rows.add(new LeaderboardRow("LuckyLift", Math.max(620, player.gamePoints - 160), false));
            rows.sort((a, b) -> Integer.compare(b.points, a.points));
            return rows;
        }
    }

    static final class Player {
        final String id = "demo-player";
        final String name = "Игрок";
        double bonusBalance = 2500;
        int gamePoints = 0;
        int tickets = 0;
        final Map<String, Integer> boosters = new HashMap<>();

        String boostersJson() {
            return "{" +
                    "\"x2\":" + boosters.getOrDefault("x2", 1) + "," +
                    "\"x3\":" + boosters.getOrDefault("x3", 1) + "," +
                    "\"x4\":" + boosters.getOrDefault("x4", 1) +
                    "}";
        }
    }

    static final class LeaderboardRow {
        final String player;
        final int points;
        final boolean current;

        LeaderboardRow(String player, int points, boolean current) {
            this.player = player;
            this.points = points;
            this.current = current;
        }
    }

    enum RoundStatus {
        RUNNING, CASHED_OUT, CRASHED, FINISHED
    }

    static final class Round {
        String id;
        String mode;
        int stake;
        int booster;
        int levels;
        int boosterLevel;
        boolean boosterActive;
        long startedAt;
        long cashoutAt;
        long finishedAt;
        double crashMultiplier;
        double cashoutMultiplier;
        double lockedPayout;
        int points;
        int lastAwardedLevel;
        RoundStatus status;
        String fairnessHash;
        String serverSeed;
        Reward reward;
        boolean historySaved;

        static Round create(String mode, int stake, int booster, GameConfig config, SecureRandom random) {
            Round round = new Round();
            round.id = UUID.randomUUID().toString();
            round.mode = mode;
            round.stake = stake;
            round.booster = booster;
            round.levels = config.levels(mode);
            round.startedAt = System.currentTimeMillis();
            round.status = RoundStatus.RUNNING;
            round.serverSeed = UUID.randomUUID() + ":" + random.nextLong();

            Random rng = config.fixedSeed.isBlank()
                    ? new Random(random.nextLong())
                    : new Random((config.fixedSeed + round.id).hashCode());
            double risk = "red".equals(mode) ? config.redRisk : config.greenRisk;
            double u = Math.max(0.0001, Math.min(0.9999, rng.nextDouble()));
            double raw = config.minCrashMultiplier + (-Math.log(1 - u) * config.crashAlpha * risk);
            round.crashMultiplier = round2(Math.min(config.maxMultiplier, Math.max(config.minCrashMultiplier, raw)));

            if (booster > 1) {
                round.boosterLevel = 1 + rng.nextInt(round.levels);
            } else {
                round.boosterLevel = 0;
            }
            round.fairnessHash = sha256(round.serverSeed + ":" + round.crashMultiplier + ":" + round.boosterLevel);
            return round;
        }

        int currentLevel(GameConfig config) {
            double current = Math.min(currentMultiplier(config), crashMultiplier);
            int level = (int) Math.floor((current - 1.0) / config.multiplierPerLevel);
            return Math.max(0, Math.min(level, levels));
        }

        double currentMultiplier(GameConfig config) {
            if (status == RoundStatus.CRASHED || status == RoundStatus.FINISHED) return crashMultiplier;
            long elapsed = System.currentTimeMillis() - startedAt;
            double base = 1.0 + (elapsed / 1000.0 * config.multiplierGrowthPerSecond(mode));
            double boosted = boosterActive ? base * booster : base;
            return round2(Math.min(config.maxMultiplier, boosted));
        }

        double potentialPayout(GameConfig config) {
            if (lockedPayout > 0) return lockedPayout;
            return round2(stake * Math.min(currentMultiplier(config), crashMultiplier));
        }

        String stateJson(GameConfig config, boolean revealFinished) {
            STORE.refreshRound(this);
            boolean ended = status == RoundStatus.CRASHED || status == RoundStatus.FINISHED;
            StringBuilder json = new StringBuilder("{");
            json.append("\"id\":\"").append(esc(id)).append("\",")
                    .append("\"mode\":\"").append(mode).append("\",")
                    .append("\"stake\":").append(stake).append(',')
                    .append("\"booster\":").append(booster).append(',')
                    .append("\"boosterLevel\":").append(boosterLevel).append(',')
                    .append("\"boosterActive\":").append(boosterActive).append(',')
                    .append("\"levels\":").append(levels).append(',')
                    .append("\"status\":\"").append(status).append("\",")
                    .append("\"currentLevel\":").append(currentLevel(config)).append(',')
                    .append("\"currentMultiplier\":").append(money(currentMultiplier(config))).append(',')
                    .append("\"potentialPayout\":").append(money(potentialPayout(config))).append(',')
                    .append("\"cashoutMultiplier\":").append(money(cashoutMultiplier)).append(',')
                    .append("\"lockedPayout\":").append(money(lockedPayout)).append(',')
                    .append("\"points\":").append(points).append(',')
                    .append("\"cashoutAvailable\":").append(status == RoundStatus.RUNNING && currentLevel(config) >= 1).append(',')
                    .append("\"fairnessHash\":\"").append(fairnessHash).append("\"");
            if (ended && revealFinished) {
                json.append(",\"crashMultiplier\":").append(money(crashMultiplier))
                        .append(",\"serverSeed\":\"").append(esc(serverSeed)).append("\"")
                        .append(",\"reward\":").append(reward == null ? "null" : reward.toJson());
            }
            json.append('}');
            return json.toString();
        }

        String historyJson() {
            boolean won = lockedPayout > 0;
            return "{" +
                    "\"id\":\"" + esc(id) + "\"," +
                    "\"finishedAt\":\"" + Instant.ofEpochMilli(finishedAt) + "\"," +
                    "\"mode\":\"" + mode + "\"," +
                    "\"stake\":" + stake + "," +
                    "\"result\":\"" + (won ? "win" : "loss") + "\"," +
                    "\"crashMultiplier\":" + money(crashMultiplier) + "," +
                    "\"cashoutMultiplier\":" + money(cashoutMultiplier) + "," +
                    "\"payout\":" + money(lockedPayout) + "," +
                    "\"points\":" + points + "," +
                    "\"booster\":\"x" + booster + "\"," +
                    "\"reward\":" + (reward == null ? "null" : reward.toJson()) +
                    "}";
        }
    }

    static final class Reward {
        String type;
        String label;
        int amount;

        static Reward generate(Round round, SecureRandom random) {
            Reward reward = new Reward();
            if (round.lockedPayout > 0 && random.nextBoolean()) {
                reward.type = "booster";
                reward.label = "x" + Math.min(4, Math.max(2, round.booster + 1));
                reward.amount = 1;
            } else {
                reward.type = "bonus_points";
                reward.label = "Бонус за высоту";
                reward.amount = Math.max(10, round.currentLevel(STORE.config) * 8);
            }
            return reward;
        }

        String toJson() {
            return "{\"type\":\"" + esc(type) + "\",\"label\":\"" + esc(label) + "\",\"amount\":" + amount + "}";
        }
    }

    static final class GameConfig {
        List<Integer> stakes = List.of(50, 150, 300, 600);
        int redLevels = 12;
        int greenLevels = 9;
        double redRisk = 1.25;
        double greenRisk = 0.92;
        double minCrashMultiplier = 1.25;
        double maxMultiplier = 8.0;
        double crashAlpha = 1.6;
        double redGrowthPerSecond = 0.46;
        double greenGrowthPerSecond = 0.34;
        double multiplierPerLevel = 0.45;
        int pointsPerLevel = 25;
        int pointsCashoutBonus = 50;
        int pointsBoosterBonus = 75;
        int minWinOfferAmount = 100;
        int popupTimeoutSeconds = 10;
        String fixedSeed = "";

        int levels(String mode) {
            return "red".equals(mode) ? redLevels : greenLevels;
        }

        double multiplierGrowthPerSecond(String mode) {
            return "red".equals(mode) ? redGrowthPerSecond : greenGrowthPerSecond;
        }

        static GameConfig load(Path path) throws IOException {
            GameConfig config = new GameConfig();
            if (Files.notExists(path)) {
                config.save(path);
                return config;
            }
            return config.applyPatch(Files.readString(path));
        }

        GameConfig applyPatch(String json) {
            GameConfig next = copy();
            next.redLevels = Json.readInt(json, "redLevels", redLevels);
            next.greenLevels = Json.readInt(json, "greenLevels", greenLevels);
            next.redRisk = Json.readDouble(json, "redRisk", redRisk);
            next.greenRisk = Json.readDouble(json, "greenRisk", greenRisk);
            next.minCrashMultiplier = Json.readDouble(json, "minCrashMultiplier", minCrashMultiplier);
            next.maxMultiplier = Json.readDouble(json, "maxMultiplier", maxMultiplier);
            next.crashAlpha = Json.readDouble(json, "crashAlpha", crashAlpha);
            next.redGrowthPerSecond = Json.readDouble(json, "redGrowthPerSecond", redGrowthPerSecond);
            next.greenGrowthPerSecond = Json.readDouble(json, "greenGrowthPerSecond", greenGrowthPerSecond);
            next.multiplierPerLevel = Json.readDouble(json, "multiplierPerLevel", multiplierPerLevel);
            next.pointsPerLevel = Json.readInt(json, "pointsPerLevel", pointsPerLevel);
            next.pointsCashoutBonus = Json.readInt(json, "pointsCashoutBonus", pointsCashoutBonus);
            next.pointsBoosterBonus = Json.readInt(json, "pointsBoosterBonus", pointsBoosterBonus);
            next.minWinOfferAmount = Json.readInt(json, "minWinOfferAmount", minWinOfferAmount);
            next.popupTimeoutSeconds = Json.readInt(json, "popupTimeoutSeconds", popupTimeoutSeconds);
            next.fixedSeed = Json.readString(json, "fixedSeed", fixedSeed);
            next.stakes = Json.readIntArray(json, "stakes", stakes);
            return next;
        }

        GameConfig copy() {
            GameConfig copy = new GameConfig();
            copy.stakes = new ArrayList<>(stakes);
            copy.redLevels = redLevels;
            copy.greenLevels = greenLevels;
            copy.redRisk = redRisk;
            copy.greenRisk = greenRisk;
            copy.minCrashMultiplier = minCrashMultiplier;
            copy.maxMultiplier = maxMultiplier;
            copy.crashAlpha = crashAlpha;
            copy.redGrowthPerSecond = redGrowthPerSecond;
            copy.greenGrowthPerSecond = greenGrowthPerSecond;
            copy.multiplierPerLevel = multiplierPerLevel;
            copy.pointsPerLevel = pointsPerLevel;
            copy.pointsCashoutBonus = pointsCashoutBonus;
            copy.pointsBoosterBonus = pointsBoosterBonus;
            copy.minWinOfferAmount = minWinOfferAmount;
            copy.popupTimeoutSeconds = popupTimeoutSeconds;
            copy.fixedSeed = fixedSeed;
            return copy;
        }

        void save(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            Files.writeString(path, toJsonPretty(), StandardCharsets.UTF_8);
        }

        String toJson() {
            return toJsonPretty().replace("\n", "").replace("  ", "");
        }

        String toJsonPretty() {
            return "{\n" +
                    "  \"stakes\": " + stakes + ",\n" +
                    "  \"redLevels\": " + redLevels + ",\n" +
                    "  \"greenLevels\": " + greenLevels + ",\n" +
                    "  \"redRisk\": " + redRisk + ",\n" +
                    "  \"greenRisk\": " + greenRisk + ",\n" +
                    "  \"minCrashMultiplier\": " + minCrashMultiplier + ",\n" +
                    "  \"maxMultiplier\": " + maxMultiplier + ",\n" +
                    "  \"crashAlpha\": " + crashAlpha + ",\n" +
                    "  \"redGrowthPerSecond\": " + redGrowthPerSecond + ",\n" +
                    "  \"greenGrowthPerSecond\": " + greenGrowthPerSecond + ",\n" +
                    "  \"multiplierPerLevel\": " + multiplierPerLevel + ",\n" +
                    "  \"pointsPerLevel\": " + pointsPerLevel + ",\n" +
                    "  \"pointsCashoutBonus\": " + pointsCashoutBonus + ",\n" +
                    "  \"pointsBoosterBonus\": " + pointsBoosterBonus + ",\n" +
                    "  \"minWinOfferAmount\": " + minWinOfferAmount + ",\n" +
                    "  \"popupTimeoutSeconds\": " + popupTimeoutSeconds + ",\n" +
                    "  \"fixedSeed\": \"" + esc(fixedSeed) + "\"\n" +
                    "}";
        }
    }

    static final class Json {
        static String readString(String json, String key, String fallback) {
            String pattern = "\"" + key + "\"";
            int keyIndex = json.indexOf(pattern);
            if (keyIndex < 0) return fallback;
            int colon = json.indexOf(':', keyIndex);
            int firstQuote = json.indexOf('"', colon + 1);
            if (colon < 0 || firstQuote < 0) return fallback;
            int secondQuote = json.indexOf('"', firstQuote + 1);
            if (secondQuote < 0) return fallback;
            return json.substring(firstQuote + 1, secondQuote);
        }

        static int readInt(String json, String key, int fallback) {
            return (int) Math.round(readDouble(json, key, fallback));
        }

        static double readDouble(String json, String key, double fallback) {
            String pattern = "\"" + key + "\"";
            int keyIndex = json.indexOf(pattern);
            if (keyIndex < 0) return fallback;
            int colon = json.indexOf(':', keyIndex);
            if (colon < 0) return fallback;
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            int end = start;
            while (end < json.length() && "-0123456789.".indexOf(json.charAt(end)) >= 0) end++;
            try {
                return Double.parseDouble(json.substring(start, end));
            } catch (Exception ignored) {
                return fallback;
            }
        }

        static List<Integer> readIntArray(String json, String key, List<Integer> fallback) {
            String pattern = "\"" + key + "\"";
            int keyIndex = json.indexOf(pattern);
            if (keyIndex < 0) return fallback;
            int open = json.indexOf('[', keyIndex);
            int close = json.indexOf(']', open);
            if (open < 0 || close < 0) return fallback;
            String[] parts = json.substring(open + 1, close).split(",");
            List<Integer> values = new ArrayList<>();
            for (String part : parts) {
                try {
                    values.add(Integer.parseInt(part.trim()));
                } catch (Exception ignored) {
                }
            }
            return values.isEmpty() ? fallback : values;
        }
    }

    static final class GameException extends RuntimeException {
        final int status;

        GameException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
