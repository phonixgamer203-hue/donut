package de.donutspin.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** Zuletzt vom Server empfangener Zustand. Wird nur im Client-Thread gelesen und geschrieben. */
public final class ClientState {
    private ClientState() {}

    public record Row(String name, int tier, long stake, long payout, long round) {}

    public static int phase;              // 0 Setzen, 1 Spin, 2 Ergebnis
    public static long endMs;             // Zeitpunkt, an dem die aktuelle Phase endet
    public static long round, prevRound;
    public static String commit = "", prevSeed = "";
    public static long bal, bet, pay;
    public static int res = -1, bettors;
    public static long wag, ret, rounds, wins, losses, pushes, best;
    public static long hWag, hPaid, hRounds, hIssued;
    public static List<Row> hist = new ArrayList<>(), feed = new ArrayList<>(), big = new ArrayList<>();
    public static String msg = "";
    public static boolean msgOk;
    public static long msgUntil;
    public static int spinSerial;

    private static int lastPhase = -1;
    private static long lastRound = -1;

    /** @return true, wenn der Server die GUI oeffnen moechte */
    public static boolean update(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        int newPhase = o.get("phase").getAsInt();
        long newRound = o.get("round").getAsLong();
        phase = newPhase;
        endMs = System.currentTimeMillis() + o.get("left").getAsInt() * 50L;
        round = newRound;
        prevRound = o.get("prevRound").getAsLong();
        commit = o.get("commit").getAsString();
        prevSeed = o.get("prevSeed").getAsString();
        bal = o.get("bal").getAsLong();
        bet = o.get("bet").getAsLong();
        res = o.get("res").getAsInt();
        pay = o.get("pay").getAsLong();
        bettors = o.get("bettors").getAsInt();

        JsonObject st = o.getAsJsonObject("st");
        wag = st.get("wag").getAsLong(); ret = st.get("ret").getAsLong();
        rounds = st.get("rounds").getAsLong(); wins = st.get("wins").getAsLong();
        losses = st.get("losses").getAsLong(); pushes = st.get("pushes").getAsLong();
        best = st.get("best").getAsLong();
        JsonObject hs = o.getAsJsonObject("hs");
        hWag = hs.get("wag").getAsLong(); hPaid = hs.get("paid").getAsLong();
        hRounds = hs.get("rounds").getAsLong(); hIssued = hs.get("issued").getAsLong();

        hist = rows(o.getAsJsonArray("hist"));
        feed = rows(o.getAsJsonArray("feed"));
        big = rows(o.getAsJsonArray("big"));

        if (o.has("msg")) {
            msg = o.get("msg").getAsString();
            msgOk = o.get("ok").getAsBoolean();
            msgUntil = System.currentTimeMillis() + 4000;
        }
        if (newPhase == 1 && (lastPhase != 1 || lastRound != newRound)) spinSerial++;
        lastPhase = newPhase;
        lastRound = newRound;
        return o.get("open").getAsBoolean();
    }

    private static List<Row> rows(JsonArray arr) {
        List<Row> out = new ArrayList<>();
        for (JsonElement e : arr) {
            JsonArray a = e.getAsJsonArray();
            out.add(new Row(a.get(0).getAsString(), a.get(1).getAsInt(), a.get(2).getAsLong(), a.get(3).getAsLong(), a.get(4).getAsLong()));
        }
        return out;
    }
}
