package de.donutspin;

import com.google.gson.*;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.donutspin.core.*;
import de.donutspin.core.GameEngine.Bet;
import de.donutspin.core.GameEngine.BetResult;
import de.donutspin.core.SaveData.*;
import de.donutspin.net.ActionPayload;
import de.donutspin.net.StatePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Server-Seite: Rundentakt, Netzwerk, Befehle, Speicherung. Funktioniert auch im Einzelspieler (integrierter Server). */
public class DonutSpinMod implements ModInitializer {
    public static final String MOD_ID = "donutspin";
    private static final Logger LOG = LoggerFactory.getLogger("DonutSpin");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static MinecraftServer server;
    private static GameEngine engine;
    private static Path saveFile;
    private static final Set<UUID> viewers = new HashSet<>();
    private static int tickCounter;
    private static boolean dirty;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPayload.TYPE, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE,
            (payload, context) -> handleAction(context.player(), payload.json()));

        ServerLifecycleEvents.SERVER_STARTED.register(DonutSpinMod::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            save();
            engine = null;
            viewers.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(DonutSpinMod::onTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        LOG.info("DonutSMP Spin Casino geladen (Soll-RTP {}%)", PayoutTable.rtpPercent());
    }

    // ------------------------------------------------------------------ Lebenszyklus
    private static void onStarted(MinecraftServer s) {
        server = s;
        saveFile = s.getWorldPath(LevelResource.ROOT).resolve("donutspin.json");
        SaveData data = null;
        try {
            if (Files.exists(saveFile)) {
                data = GSON.fromJson(Files.readString(saveFile, StandardCharsets.UTF_8), SaveData.class);
            }
        } catch (IOException | JsonParseException e) {
            LOG.error("donutspin.json konnte nicht gelesen werden - Sicherungskopie wird angelegt", e);
            try { Files.copy(saveFile, saveFile.resolveSibling("donutspin.json.broken"), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
        }
        if (data == null) data = new SaveData();
        tickCounter = 0;
        dirty = false;
        engine = new GameEngine(data, new GameEngine.Listener() {
            @Override public void onPhase(GameEngine e) { broadcast(); }
            @Override public void onSettled(GameEngine e, List<Bet> bets) { announce(bets); dirty = true; }
        });
    }

    private static synchronized void save() {
        if (engine == null || saveFile == null) return;
        try {
            Path tmp = saveFile.resolveSibling("donutspin.json.tmp");
            Files.writeString(tmp, GSON.toJson(engine.data()), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, saveFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, saveFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.error("Speichern fehlgeschlagen", e);
        }
    }

    private static void onTick(MinecraftServer s) {
        GameEngine e = engine;
        if (e == null) return;
        e.tick();
        tickCounter++;
        if (tickCounter % 20 == 0) broadcast();
        if (dirty) { save(); dirty = false; }
        else if (tickCounter % 6000 == 0) save();
    }

    // ------------------------------------------------------------------ Ergebnis-Meldungen
    private static void announce(List<Bet> bets) {
        for (Bet b : bets) {
            ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(b.id));
            if (p == null) continue;
            Tier t = PayoutTable.TIERS[b.tier];
            long profit = b.payout - b.stake;
            String col = profit > 0 ? "\u00a7a" : profit < 0 ? "\u00a7c" : "\u00a7e";
            p.sendSystemMessage(Component.literal("\u00a76[Spin] \u00a7f" + t.label() + " \u00a77\u00b7 Einsatz " + Amounts.fmt(b.stake)
                + " \u2192 Auszahlung " + Amounts.fmt(b.payout) + " " + col + "(" + Amounts.signed(profit) + ")"));
            if (profit > 0) p.playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8f, 1.0f);
            else if (profit < 0) p.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 0.6f, 1.0f);
        }
    }

    // ------------------------------------------------------------------ Netzwerk
    private static void broadcast() {
        if (engine == null || server == null) return;
        for (UUID id : new ArrayList<>(viewers)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) viewers.remove(id);
            else send(p, false, null, true);
        }
    }

    private static void send(ServerPlayer p, boolean open, String msg, boolean ok) {
        if (engine == null || !ServerPlayNetworking.canSend(p, StatePayload.TYPE)) return;
        ServerPlayNetworking.send(p, new StatePayload(stateJson(p, open, msg, ok)));
    }

    private static void handleAction(ServerPlayer p, String json) {
        if (engine == null) return;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            switch (o.get("a").getAsString()) {
                case "sync" -> { viewers.add(p.getUUID()); send(p, false, null, true); }
                case "close" -> viewers.remove(p.getUUID());
                case "cancel" -> {
                    boolean done = engine.cancelBet(id(p));
                    send(p, false, done ? "Einsatz storniert." : "Kein Einsatz zum Stornieren.", done);
                }
                case "bet" -> {
                    long amt = o.get("amt").getAsLong();
                    BetResult r = engine.placeBet(id(p), name(p), amt);
                    send(p, false, describe(r, amt), r == BetResult.OK);
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            LOG.warn("Ungueltige Aktion von {}: {}", name(p), json);
        }
    }

    private static String describe(BetResult r, long amt) {
        return switch (r) {
            case OK -> "Einsatz platziert: " + Amounts.fmt(amt);
            case TOO_LOW -> "Mindesteinsatz ist " + Amounts.fmt(GameEngine.MIN_BET);
            case TOO_HIGH -> "H\u00f6chsteinsatz ist " + Amounts.fmt(GameEngine.MAX_BET);
            case NO_FUNDS -> "Nicht genug Guthaben.";
            case WRONG_PHASE -> "Gerade nicht m\u00f6glich - warte auf die n\u00e4chste Runde.";
            case ALREADY_BET -> "Du hast in dieser Runde schon gesetzt.";
        };
    }

    private static String stateJson(ServerPlayer p, boolean open, String msg, boolean ok) {
        String id = id(p);
        PlayerData acc = engine.account(id, name(p));
        SaveData d = engine.data();
        Bet bet = engine.bet(id);
        GameEngine.Phase ph = engine.phase();

        JsonObject o = new JsonObject();
        o.addProperty("open", open);
        if (msg != null) { o.addProperty("msg", msg); o.addProperty("ok", ok); }
        o.addProperty("phase", ph.ordinal());
        o.addProperty("left", engine.ticksLeft());
        o.addProperty("round", engine.round());
        o.addProperty("commit", engine.commit());
        RoundRecord last = engine.lastRecord();
        o.addProperty("prevRound", last == null ? 0 : last.round);
        o.addProperty("prevSeed", last == null ? "" : last.seed);
        o.addProperty("bal", engine.available(id));
        o.addProperty("bet", bet == null ? 0 : bet.stake);
        o.addProperty("res", bet != null && ph != GameEngine.Phase.BETTING ? bet.tier : -1);
        o.addProperty("pay", bet != null && ph == GameEngine.Phase.RESULT ? bet.payout : 0);
        o.addProperty("bettors", engine.bets().size());

        JsonObject st = new JsonObject();
        st.addProperty("wag", acc.wagered); st.addProperty("ret", acc.returned);
        st.addProperty("rounds", acc.rounds); st.addProperty("wins", acc.wins);
        st.addProperty("losses", acc.losses); st.addProperty("pushes", acc.pushes);
        st.addProperty("best", acc.bestProfit);
        o.add("st", st);

        JsonObject hs = new JsonObject();
        hs.addProperty("wag", d.house.wagered); hs.addProperty("paid", d.house.paid);
        hs.addProperty("rounds", d.house.rounds); hs.addProperty("issued", d.house.issued);
        o.add("hs", hs);

        JsonArray hist = new JsonArray();
        for (int i = 0; i < Math.min(10, acc.history.size()); i++) {
            HistoryEntry h = acc.history.get(i);
            hist.add(row("", h.tier, h.stake, h.payout, h.round));
        }
        o.add("hist", hist);

        JsonArray feed = new JsonArray();
        List<Bet> lr = engine.lastResults();
        for (int i = 0; i < Math.min(6, lr.size()); i++) {
            Bet b = lr.get(i);
            feed.add(row(b.name, b.tier, b.stake, b.payout, 0));
        }
        o.add("feed", feed);

        JsonArray big = new JsonArray();
        for (int i = 0; i < Math.min(3, d.bigWins.size()); i++) {
            BigWin w = d.bigWins.get(i);
            big.add(row(w.name, w.tier, w.stake, w.payout, w.round));
        }
        o.add("big", big);
        return o.toString();
    }

    private static JsonArray row(String name, int tier, long stake, long payout, long round) {
        JsonArray a = new JsonArray();
        a.add(name); a.add(tier); a.add(stake); a.add(payout); a.add(round);
        return a;
    }

    private static String id(ServerPlayer p) { return p.getUUID().toString(); }
    private static String name(ServerPlayer p) { return p.getName().getString(); }

    // ------------------------------------------------------------------ Befehle
    private static boolean isAdmin(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (p == null) return true;                                   // Konsole / Command-Block
        MinecraftServer s = src.getServer();
        if (s.isSingleplayer()) {                                     // Einzelspieler: nur der Host
            List<ServerPlayer> l = s.getPlayerList().getPlayers();
            return !l.isEmpty() && l.get(0).getUUID().equals(p.getUUID());
        }
        try {                                                          // Server: Namen in donutspin-admins.txt
            Path f = s.getWorldPath(LevelResource.ROOT).resolve("donutspin-admins.txt");
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.trim().equalsIgnoreCase(name(p))) return true;
                }
            }
        } catch (IOException ignored) { }
        return false;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("spin")
            .executes(c -> openGui(c.getSource()))
            .then(Commands.literal("gui").executes(c -> openGui(c.getSource())))
            .then(Commands.literal("balance").executes(c -> balance(c.getSource())))
            .then(Commands.literal("stats").executes(c -> stats(c.getSource())))
            .then(Commands.literal("odds").executes(c -> odds(c.getSource())))
            .then(Commands.literal("house").executes(c -> house(c.getSource())))
            .then(Commands.literal("cancel").executes(c -> cancel(c.getSource())))
            .then(Commands.literal("bet").then(Commands.argument("amount", StringArgumentType.word())
                .executes(c -> bet(c.getSource(), StringArgumentType.getString(c, "amount")))))
            .then(Commands.literal("verify").then(Commands.argument("round", LongArgumentType.longArg(1))
                .executes(c -> verify(c.getSource(), LongArgumentType.getLong(c, "round")))))
            .then(Commands.literal("give").requires(DonutSpinMod::isAdmin)
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(c -> admin(c.getSource(), EntityArgument.getPlayer(c, "player"),
                            StringArgumentType.getString(c, "amount"), false)))))
            .then(Commands.literal("set").requires(DonutSpinMod::isAdmin)
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("amount", StringArgumentType.word())
                        .executes(c -> admin(c.getSource(), EntityArgument.getPlayer(c, "player"),
                            StringArgumentType.getString(c, "amount"), true)))))
        );
    }

    private static void ok(CommandSourceStack s, String msg) { s.sendSuccess(() -> Component.literal(msg), false); }
    private static int fail(CommandSourceStack s, String msg) { s.sendFailure(Component.literal(msg)); return 0; }

    private static int openGui(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        if (p == null || engine == null) return fail(s, "Nur im Spiel nutzbar.");
        if (!ServerPlayNetworking.canSend(p, StatePayload.TYPE)) {
            return fail(s, "Die Mod muss auch auf deinem Client installiert sein. Ohne GUI: /spin bet <Betrag>");
        }
        viewers.add(p.getUUID());
        send(p, true, null, true);
        return Command.SINGLE_SUCCESS;
    }

    private static int balance(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        if (p == null || engine == null) return fail(s, "Nur im Spiel nutzbar.");
        ok(s, "\u00a76[Spin] \u00a7fGuthaben: \u00a7a" + Amounts.full(engine.available(id(p))) + " Punkte");
        return Command.SINGLE_SUCCESS;
    }

    private static int stats(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        if (p == null || engine == null) return fail(s, "Nur im Spiel nutzbar.");
        PlayerData a = engine.account(id(p), name(p));
        double rtp = a.wagered == 0 ? 0 : a.returned * 100.0 / a.wagered;
        ok(s, "\u00a76[Spin] \u00a7fRunden " + a.rounds + " (\u00a7a" + a.wins + " Gewinne\u00a7f / \u00a7c" + a.losses + " Verluste\u00a7f / " + a.pushes
            + " gleich)\n\u00a77Eingesetzt " + Amounts.fmt(a.wagered) + " \u00b7 Ausgezahlt " + Amounts.fmt(a.returned)
            + " \u00b7 Netto " + Amounts.signed(a.returned - a.wagered)
            + String.format(Locale.ROOT, " \u00b7 eigene R\u00fcckzahlung %.2f%%", rtp));
        return Command.SINGLE_SUCCESS;
    }

    private static int odds(CommandSourceStack s) {
        StringBuilder sb = new StringBuilder("\u00a76[Spin] Auszahlungstabelle (Wurf 0-9999, Einsatz " + Amounts.fmt(GameEngine.MIN_BET)
            + "-" + Amounts.fmt(GameEngine.MAX_BET) + ")\n");
        for (Tier t : PayoutTable.TIERS) {
            sb.append("\u00a7f").append(t.label()).append(" \u00a77= ").append(t.chance()).append("  ");
        }
        sb.append(String.format(Locale.ROOT, "\n\u00a7eR\u00fcckzahlung %.3f%% \u00b7 Hausvorteil %.3f%% (Summe Gewicht\u00b7Multiplikator = %d / %d)",
            PayoutTable.rtpPercent(), 100 - PayoutTable.rtpPercent(), PayoutTable.rtpNumerator(), PayoutTable.TOTAL * 10));
        ok(s, sb.toString());
        return Command.SINGLE_SUCCESS;
    }

    private static int house(CommandSourceStack s) {
        if (engine == null) return fail(s, "Nicht bereit.");
        House h = engine.data().house;
        long bal = 0;
        for (PlayerData p : engine.data().players.values()) bal += p.balance;
        double rtp = h.wagered == 0 ? 0 : h.paid * 100.0 / h.wagered;
        boolean inv = bal + (h.wagered - h.paid) == h.issued;
        ok(s, "\u00a76[Spin] Betreiberbilanz (virtuell)\n\u00a7fRunden " + h.rounds + " \u00b7 Eins\u00e4tze " + Amounts.full(h.wagered)
            + " \u00b7 Auszahlungen " + Amounts.full(h.paid) + "\n\u00a7fBilanz " + Amounts.signed(h.wagered - h.paid)
            + String.format(Locale.ROOT, " \u00b7 tats\u00e4chliche R\u00fcckzahlung %.2f%% (Soll %.3f%%)", rtp, PayoutTable.rtpPercent())
            + "\n\u00a77Pr\u00fcfsumme: Guthaben " + Amounts.full(bal) + " + Bilanz = ausgegeben " + Amounts.full(h.issued) + (inv ? " \u00a7a\u2713" : " \u00a7c\u2717"));
        return Command.SINGLE_SUCCESS;
    }

    private static int bet(CommandSourceStack s, String text) {
        ServerPlayer p = s.getPlayer();
        if (p == null || engine == null) return fail(s, "Nur im Spiel nutzbar.");
        long amt = Amounts.parse(text);
        if (amt < 0) return fail(s, "Ung\u00fcltiger Betrag. Beispiele: 1M, 2.5M, 500M");
        BetResult r = engine.placeBet(id(p), name(p), amt);
        if (r != BetResult.OK) return fail(s, describe(r, amt));
        ok(s, "\u00a76[Spin] \u00a7a" + describe(r, amt) + " \u00a77(Hash dieser Runde: " + engine.commit().substring(0, 16) + "\u2026)");
        if (viewers.contains(p.getUUID())) send(p, false, describe(r, amt), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandSourceStack s) {
        ServerPlayer p = s.getPlayer();
        if (p == null || engine == null) return fail(s, "Nur im Spiel nutzbar.");
        if (!engine.cancelBet(id(p))) return fail(s, "Kein Einsatz zum Stornieren (oder Runde l\u00e4uft schon).");
        ok(s, "\u00a76[Spin] \u00a7fEinsatz storniert.");
        return Command.SINGLE_SUCCESS;
    }

    private static int verify(CommandSourceStack s, long round) {
        if (engine == null) return fail(s, "Nicht bereit.");
        for (RoundRecord r : engine.data().rounds) {
            if (r.round != round) continue;
            StringBuilder sb = new StringBuilder("\u00a76[Spin] Runde " + round + "\n\u00a77Vorab ver\u00f6ffentlichter Hash: \u00a7f" + r.commit
                + "\n\u00a77Seed (nach der Runde): \u00a7f" + r.seed
                + "\n\u00a77Pr\u00fcfung: SHA-256(Seed) = Hash; Wurf = erste 8 Bytes von SHA-256(Seed + \":\" + UUID) mod 10000");
            ServerPlayer p = s.getPlayer();
            if (p != null) {
                int roll = Fairness.roll(Fairness.fromHex(r.seed), id(p));
                Tier t = PayoutTable.forRoll(roll);
                sb.append("\n\u00a7fDein Wurf in dieser Runde w\u00e4re: ").append(roll).append(" \u2192 ").append(t.label());
            }
            ok(s, sb.toString());
            return Command.SINGLE_SUCCESS;
        }
        return fail(s, "Runde " + round + " ist nicht (mehr) protokolliert (die letzten 200 werden gespeichert).");
    }

    private static int admin(CommandSourceStack s, ServerPlayer target, String text, boolean set) {
        if (engine == null) return fail(s, "Nicht bereit.");
        long amt = Amounts.parse(text);
        if (amt < 0 && !(set && text.trim().equals("0"))) return fail(s, "Ung\u00fcltiger Betrag.");
        if (amt < 0) amt = 0;
        if (set) engine.setBalance(id(target), name(target), amt);
        else engine.give(id(target), name(target), amt);
        dirty = true;
        ok(s, name(target) + ": Guthaben " + (set ? "gesetzt auf " : "erh\u00f6ht um ") + Amounts.fmt(amt)
            + " (jetzt " + Amounts.fmt(engine.available(id(target))) + ")");
        if (viewers.contains(target.getUUID())) send(target, false, null, true);
        return Command.SINGLE_SUCCESS;
    }
}
