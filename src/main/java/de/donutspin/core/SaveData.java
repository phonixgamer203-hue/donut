package de.donutspin.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Alles, was gespeichert wird (JSON, oeffentliche Felder). Nur virtuelle, nicht uebertragbare Punkte. */
public final class SaveData {
    public Map<String, PlayerData> players = new HashMap<>();
    public House house = new House();
    public List<BigWin> bigWins = new ArrayList<>();
    public List<RoundRecord> rounds = new ArrayList<>();
    public long roundCounter = 0;

    public static final class PlayerData {
        public String name = "?";
        public long balance;
        public long wagered, returned, bestProfit;
        public int rounds, wins, losses, pushes;
        public List<HistoryEntry> history = new ArrayList<>();
    }

    public static final class HistoryEntry {
        public long round, stake, payout;
        public int tier;
        public HistoryEntry() {}
        public HistoryEntry(long round, int tier, long stake, long payout) {
            this.round = round; this.tier = tier; this.stake = stake; this.payout = payout;
        }
    }

    /** Virtuelle Betreiberbilanz. Invariante: Summe(Guthaben) + (wagered - paid) == issued. */
    public static final class House {
        public long wagered, paid, rounds, issued;
    }

    public static final class BigWin {
        public String name = "?";
        public long payout, stake, round;
        public int tier;
    }

    public static final class RoundRecord {
        public long round, wagered, paid;
        public int bets;
        public String seed = "", commit = "";
    }
}
