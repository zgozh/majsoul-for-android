// SPDX-License-Identifier: GPL-3.0-only
package com.zheyou.majsoul.epsilon;

import com.epsilon.agent.policy.ClientSeat;
import com.epsilon.agent.policy.ModelFamily;
import com.epsilon.agent.policy.Policy;
import com.epsilon.agent.policy.PolicyOptions;
import com.epsilon.agent.policy.Series;
import com.epsilon.mahjong.action.Action;
import com.epsilon.mahjong.action.Meld;
import com.epsilon.mahjong.client.LiveTable;
import com.epsilon.mahjong.engine.TurnEvent;
import com.epsilon.mahjong.rules.RoundStart;
import com.epsilon.mahjong.rules.Variant;
import com.epsilon.mahjong.tile.Face;
import com.epsilon.mahjong.tile.Tiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/** Replays a seat's visible MJAI events through Epsilon's live table. */
public final class EpsilonMjai {
    private static final Variant RULES = Variant.TENHOU_3P;

    private final int seat;
    private final LiveTable live;

    private EpsilonMjai(ClientSeat policy, int seat) {
        this.seat = seat;
        live = new LiveTable(RULES, seat, policy);
    }

    /** Keeps the loaded policy on the Android inference worker for subsequent decisions. */
    public static final class Session implements AutoCloseable {
        private final Policy policy;
        private final ClientSeat client;

        public Session(String modelDirectory) throws Exception {
            System.setProperty("ai.djl.default_engine", "PyTorch");
            policy = ModelFamily.of(Series.PRISM)
                    .open(Path.of(modelDirectory), RULES, PolicyOptions.defaults());
            client = new ClientSeat(policy);
        }

        public String react(String requestJson) {
            var request = JsonParser.parseString(requestJson).getAsJsonObject();
            var adapter = new EpsilonMjai(client, integer(request, "player_id"));
            return adapter.react(request.getAsJsonArray("events")).toString();
        }

        @Override public void close() { policy.close(); }
    }

    private JsonObject react(JsonArray events) {
        List<JsonObject> replay = new ArrayList<>(events.size());
        for (JsonElement value : events) {
            replay.add(value.getAsJsonObject());
        }
        boolean started = false;
        int kans = 0;
        int indicators = 1;
        String previous = "";
        for (int i = 0; i < replay.size(); i++) {
            JsonObject event = replay.get(i);
            String type = name(event, "type");
            if (type.equals("start_kyoku")) {
                startRound(event);
                started = true;
                kans = 0;
                indicators = 1;
            } else if (started) {
                if (type.equals("tsumo") && isSupplementAction(previous)) {
                    // Epsilon allows one delayed open-kan indicator. Mahjong Soul can
                    // batch several at the end of the same chain of replacement draws.
                    boolean openKan = previous.equals("kakan") || previous.equals("daiminkan");
                    int due = 1 + kans - (openKan ? 1 : 0) - indicators;
                    indicators += feedDelayedIndicators(replay, i, integer(event, "actor"), due);
                }
                feed(event);
                switch (type) {
                    case "ankan", "kakan", "daiminkan" -> kans++;
                    case "dora" -> indicators++;
                }
            }
            if (!type.equals("dora")) {
                previous = type;
            }
        }
        if (!started) {
            throw new IllegalArgumentException("Missing start_kyoku");
        }
        int code = live.decide(action -> true);
        if (code == LiveTable.NO_DECISION) {
            return action("none");
        }
        return encode(code);
    }

    private int feedDelayedIndicators(List<JsonObject> replay, int draw, int actor, int due) {
        int supplied = 0;
        boolean discarded = false;
        for (int i = draw + 1; supplied < due && i < replay.size();) {
            JsonObject next = replay.get(i);
            String type = name(next, "type");
            if (type.equals("dora")) {
                feed(replay.remove(i));
                supplied++;
                continue;
            }
            if (discarded || !(type.equals("tsumo") || type.equals("dahai") || isSupplementAction(type))
                    || integer(next, "actor") != actor) {
                break;
            }
            discarded = type.equals("dahai");
            i++;
        }
        return supplied;
    }

    private static boolean isSupplementAction(String type) {
        return switch (type) {
            case "ankan", "kakan", "daiminkan", "kita", "nukidora" -> true;
            default -> false;
        };
    }

    private void startRound(JsonObject event) {
        JsonArray hands = event.getAsJsonArray("tehais");
        JsonArray scores = event.getAsJsonArray("scores");
        if (hands.size() != RULES.seats() || scores.size() != RULES.seats()) {
            throw new IllegalArgumentException("Epsilon requires a three-player table");
        }
        int wind = Face.parse(name(event, "bakaze")).kind() - Tiles.TON;
        int round = wind * RULES.seats() + integer(event, "kyoku") - 1;
        if (integer(event, "oya") != RULES.dealer(round)) {
            throw new IllegalArgumentException("Dealer differs from Tenhou seat order");
        }
        var start = new RoundStart(RULES, round, integer(event, "honba"), integer(event, "kyotaku"),
                new int[]{scores.get(0).getAsInt(), scores.get(1).getAsInt(), scores.get(2).getAsInt()});
        List<Face> faces = faces(hands.get(seat).getAsJsonArray());
        int[] hand = new int[faces.size()];
        boolean[] used = new boolean[Tiles.PHYSICAL_IDS];
        for (int i = 0; i < hand.length; i++) {
            hand[i] = copy(faces.get(i), tile -> !used[tile]);
            used[hand[i]] = true;
        }
        live.startRound(start, hand, representative(name(event, "dora_marker")));
    }

    private void feed(JsonObject event) {
        String type = name(event, "type");
        switch (type) {
            case "tsumo" -> {
                int actor = integer(event, "actor");
                int tile = actor == seat
                        ? copy(Face.parse(name(event, "pai")), id -> !live.view().holds(id))
                        : TurnEvent.NONE;
                live.draw(actor, tile);
            }
            case "dahai" -> discard(event);
            case "pon", "daiminkan" -> {
                int actor = integer(event, "actor");
                var meld = Meld.call(Meld.Type.valueOf(type.toUpperCase(Locale.ROOT)),
                        Meld.RelativeSource.of(actor, integer(event, "target"), RULES.seats()),
                        Face.parse(name(event, "pai")), faces(event.getAsJsonArray("consumed")));
                live.call(actor, meld.id());
            }
            case "ankan" -> live.closedKan(integer(event, "actor"),
                    faces(event.getAsJsonArray("consumed")).get(0).kind());
            case "kakan" -> live.addedKan(integer(event, "actor"), Face.parse(name(event, "pai")).kind());
            case "kita", "nukidora" -> {
                int actor = integer(event, "actor");
                live.nuki(actor, actor == seat ? copy(Face.parse("N"), live.view()::holds) : TurnEvent.NONE);
            }
            case "dora" -> live.dora(representative(name(event, "dora_marker")));
            case "reach" -> live.declareRiichi(integer(event, "actor"));
            case "reach_accepted" -> live.acceptRiichi(integer(event, "actor"));
            case "start_game", "none", "end_kyoku", "end_game", "hora", "ryukyoku" -> { }
            default -> throw new IllegalArgumentException("Unsupported sanma event: " + type);
        }
    }

    private void discard(JsonObject event) {
        int actor = integer(event, "actor");
        String pai = name(event, "pai");
        boolean tsumogiri = event.get("tsumogiri").getAsBoolean();
        if (actor == seat && !tsumogiri && isOnlyDrawnCopy(pai)) {
            // Mahjong Soul can label the dealer's opening draw as a hand discard.
            tsumogiri = true;
        }
        live.discard(actor, representative(pai), tsumogiri);
    }

    private boolean isOnlyDrawnCopy(String pai) {
        int drawn = live.drawn();
        if (drawn == TurnEvent.NONE || !face(drawn).equals(pai)) {
            return false;
        }
        for (int i = 0; i < Tiles.COPIES; i++) {
            int tile = Tiles.physicalId(Tiles.kind(drawn), i);
            if (tile != drawn && live.view().holds(tile) && face(tile).equals(pai)) {
                return false;
            }
        }
        return true;
    }

    private JsonObject encode(int code) {
        Action choice = Action.of(code);
        var last = live.view().last();
        int[] tiles = new int[4];
        int count = live.tiles(code, tiles);
        JsonObject result;
        switch (choice.type()) {
            case DAHAI, RIICHI_DAHAI -> {
                result = action(choice.type() == Action.Type.DAHAI ? "dahai" : "reach");
                result.addProperty("pai", face(tiles[0]));
                if (choice.type() == Action.Type.DAHAI) {
                    result.addProperty("tsumogiri", choice.selection().tsumogiri());
                }
            }
            case PON, DAIMINKAN -> {
                result = action(choice.type().name().toLowerCase(Locale.ROOT));
                result.addProperty("pai", Face.of(last.tile(), last.red()).mjai());
                result.addProperty("target", last.seat());
                result.add("consumed", consumed(tiles, count));
            }
            case ANKAN -> {
                result = action("ankan");
                result.add("consumed", consumed(tiles, count));
            }
            case KAKAN -> {
                result = action("kakan");
                result.addProperty("pai", face(tiles[0]));
                result.add("consumed", existingPon(choice.tile()));
            }
            case TSUMO_AGARI, RON_AGARI -> {
                result = action("hora");
                result.addProperty("target", choice.type() == Action.Type.TSUMO_AGARI ? seat : last.seat());
            }
            case NUKI -> {
                result = action("kita");
                result.addProperty("pai", "N");
            }
            case KYUSHU_KYUHAI -> result = action("ryukyoku");
            case PASS -> result = action("none");
            default -> throw new IllegalArgumentException("Unsupported sanma action: " + choice);
        }
        return result;
    }

    private JsonArray existingPon(int kind) {
        for (int i = 0; i < live.view().meldCount(seat); i++) {
            Meld meld = Meld.of(live.view().meld(seat, i));
            if (meld.type() == Meld.Type.PON && meld.base() == kind) {
                var result = new JsonArray();
                for (int tile = 0; tile < 3; tile++) {
                    result.add(Face.of(kind, tile == 0 && meld.red()).mjai());
                }
                return result;
            }
        }
        throw new IllegalStateException("Kakan without a matching pon");
    }

    private JsonObject action(String type) {
        JsonObject result = new JsonObject();
        result.addProperty("type", type);
        if (!type.equals("none")) {
            result.addProperty("actor", seat);
        }
        return result;
    }

    private JsonArray consumed(int[] tiles, int count) {
        var result = new JsonArray();
        for (int i = 0; i < count; i++) {
            result.add(face(tiles[i]));
        }
        return result;
    }

    private String face(int tile) {
        return Face.of(Tiles.kind(tile), RULES.tiles().red(tile)).mjai();
    }

    private int representative(String face) {
        return copy(Face.parse(face), tile -> true);
    }

    private int copy(Face face, IntPredicate available) {
        for (int i = 0; i < Tiles.COPIES; i++) {
            int tile = Tiles.physicalId(face.kind(), i);
            if (RULES.tiles().contains(tile) && RULES.tiles().red(tile) == face.red() && available.test(tile)) {
                return tile;
            }
        }
        throw new IllegalArgumentException("No available copy of " + face.mjai());
    }

    private static List<Face> faces(JsonArray values) {
        List<Face> result = new ArrayList<>(values.size());
        for (JsonElement tile : values) {
            result.add(Face.parse(tile.getAsString()));
        }
        return result;
    }

    private static String name(JsonObject event, String key) {
        return event.get(key).getAsString();
    }

    private static int integer(JsonObject event, String key) {
        return event.get(key).getAsInt();
    }
}
