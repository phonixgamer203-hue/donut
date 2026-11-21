package de.donutspin.core;

import de.donutspin.core.SaveData.*;

import java.util.*;

/**
 * Rundenlogik ohne Minecraft-Abhaengigkeit. Eine Runde = 60 s:
 * 44 s Setzen, 9 s Spin-Animation, 7 s Ergebnis. Einsaetze werden nur reserviert und erst bei der
 * Abrechnung verbucht - ein Absturz mitten in der Runde kostet deshalb keine Punkte.
 */
public final class GameEngine {
    public enum Phase { BETTING, SPINNING, RESULT }
    public enum BetResult { OK, TOO_LOW, TOO_HIGH, NO_FUNDS, WRONG_PHASE, ALREADY_BET }

    public static final int BET_TICKS = 880, SPIN_TICKS = 180, RESULT_TICKS = 140;
    public static final long MIN_BET = 1_000_000L, MAX_BET = 500_000_000L, START_BALANCE = 10_000_000L;

    public static final class Bet {
        public final String id, name;
        public final long stake;
        public int tier = -1;
        public long payout;
        Bet(String id, String name, long stake) { this.id = id; this.name = name; this.stake = stake; }
    }

    public interface Listener {
        void onPhase(GameEngine e);
        void onSettled(GameEngine e, List<Bet> bets);
    }

    private final SaveData data;
    private final Listener listener;
    private final Map<String, Bet> bets = new LinkedHashMap<>();
    private List<Bet> lastResults = new ArrayList<>();
    private Phase phase = Phase.BETTING;
    private int ticksLeft = BET_TICKS;
    private long round;
    private byte[] seed;
    private String commit;

    public GameEngine(SaveData data, Listener listener) {
        this.data = data;
        this.listener = listener;
        newRoundSeed();
    }

    // ---------- Zugriff ----------
    public SaveData data() { return data; }
    public Phase phase() { return phase; }
    public int ticksLeft() { return ticksLeft; }
    public long round() { return round; }
    public String commit() { return commit; }
    public Collection<Bet> bets() { return Collections.unmodifiableCollection(bets.values()); }
    public Bet bet(String id) { return bets.get(id); }
    public List<Bet> lastResults() { return lastResults; }
    public RoundRecord lastRecord() {
        return data.rounds.isEmpty() ? null : data.rounds.get(data.rounds.size() - 1);
    }

    public PlayerData account(String id, String name) {
        PlayerData p = data.players.get(id);
        if (p == null) {
            p = new PlayerData();
            p.balance = START_BALANCE;
            data.house.issued += START_BALANCE;
            data.players.put(id, p);
        }
        if (name != null) p.name = name;
        return p;
    }

    /** Guthaben abzueglich des in dieser Runde reservierten Einsatzes. */
    public long available(String id) {
        PlayerData p = account(id, null);
        Bet b = bets.get(id);
        return b == null ? p.balance : p.balance - b.stake;
    }

    // ---------- Aktionen ----------
    public BetResult placeBet(String id, String name, long amount) {
        PlayerData acc = account(id, name);
        if (phase != Phase.BETTING) return BetResult.WRONG_PHASE;
        if (bets.containsKey(id)) return BetResult.ALREADY_BET;
        if (amount < MIN_BET) return BetResult.TOO_LOW;
        if (amount > MAX_BET) return BetResult.TOO_HIGH;
        if (acc.balance < amount) return BetResult.NO_FUNDS;
        bets.put(id, new Bet(id, acc.name, amount));
        return BetResult.OK;
    }

    public boolean cancelBet(String id) {
        return phase == Phase.BETTING && bets.remove(id) != null;
    }

    public void give(String id, String name, long amount) {
        PlayerData p = account(id, name);
        p.balance += amount;
        data.house.issued += amount;
    }

    public void setBalance(String id, String name, long amount) {
        PlayerData p = account(id, name);
        data.house.issued += amount - p.balance;
        p.balance = amount;
    }

    // ---------- Takt (20 pro Sekunde) ----------
    public void tick() {
        if (--ticksLeft > 0) return;
        switch (phase) {
            case BETTING -> {
                if (bets.isEmpty()) ticksLeft = BET_TICKS;   // niemand gesetzt: Runde laeuft weiter
                else startSpin();
            }
            case SPINNING -> settle();
            case RESULT -> startBetting();
        }
    }

    private void newRoundSeed() {
        seed = Fairness.newSeed();
        commit = Fairness.commit(seed);
        round = data.roundCounter + 1;
    }

    private void startBetting() {
        bets.clear();
        newRoundSeed();
        phase = Phase.BETTING;
        ticksLeft = BET_TICKS;
        listener.onPhase(this);
    }

    private void startSpin() {
        for (Bet b : bets.values()) {
            b.tier = PayoutTable.forRoll(Fairness.roll(seed, b.id)).index;
            b.payout = PayoutTable.payout(b.stake, b.tier);
        }
        phase = Phase.SPINNING;
        ticksLeft = SPIN_TICKS;
        listener.onPhase(this);
    }

    private void settle() {
        long wag = 0, paid = 0;
        List<Bet> list = new ArrayList<>(bets.values());
        for (Bet b : list) {
            PlayerData a = account(b.id, b.name);
            long nb = a.balance - b.stake + b.payout;
            if (nb < 0) { data.house.issued += -nb; nb = 0; }   // nur wenn ein Admin waehrend der Runde Punkte entzog
            a.balance = nb;
            a.wagered += b.stake;
            a.returned += b.payout;
            a.rounds++;
            long profit = b.payout - b.stake;
            if (profit > 0) { a.wins++; if (profit > a.bestProfit) a.bestProfit = profit; }
            else if (profit < 0) a.losses++;
            else a.pushes++;
            a.history.add(0, new HistoryEntry(round, b.tier, b.stake, b.payout));
            while (a.history.size() > 20) a.history.remove(a.history.size() - 1);
            wag += b.stake;
            paid += b.payout;

            if (profit > 0) {
                BigWin w = new BigWin();
                w.name = b.name; w.payout = b.payout; w.stake = b.stake; w.tier = b.tier; w.round = round;
                data.bigWins.add(w);
            }
        }
        data.bigWins.sort((x, y) -> Long.compare(y.payout, x.payout));
        while (data.bigWins.size() > 5) data.bigWins.remove(data.bigWins.size() - 1);

        data.house.wagered += wag;
        data.house.paid += paid;
        data.house.rounds++;

        RoundRecord rr = new RoundRecord();
        rr.round = round; rr.seed = Fairness.hex(seed); rr.commit = commit;
        rr.bets = list.size(); rr.wagered = wag; rr.paid = paid;
        data.rounds.add(rr);
        while (data.rounds.size() > 200) data.rounds.remove(0);
        data.roundCounter = round;

        list.sort((x, y) -> Long.compare(y.payout, x.payout));
        lastResults = list;
        phase = Phase.RESULT;
        ticksLeft = RESULT_TICKS;
        listener.onPhase(this);
        listener.onSettled(this, list);
    }
}
