// SPDX-License-Identifier: AGPL-3.0-or-later
package com.zheyou.majsoul

import org.json.JSONArray
import org.json.JSONObject

/** Adapts the Web recommendation's visible-tile and shanten calculations. */
internal object RecommendationDetails {
    private val meldTypes = setOf("chi", "pon", "daiminkan", "ankan")
    private const val WINDS_AND_DRAGONS = "ESWNPFC"

    fun explain(request: JSONObject, action: JSONObject): List<String> {
        val type = action.getString("type")
        val position = runCatching { readPosition(request) }.getOrNull()
        val detail = note(type, action, position)
        val lines = mutableListOf<String>()
        if (position != null && type != "hora") {
            runCatching { shapeAfter(type, action, position) }.getOrNull()?.let { lines.addAll(it) }
        }
        if (detail != null) lines += detail
        return lines.ifEmpty { listOf("当前局面暂无可核对的解读") }
    }

    private class Position(val players: Int, val seat: Int, val dealer: Int, val wind: Int) {
        val hand = IntArray(34)
        val visible = IntArray(34)
        val discards = Array(players) { BooleanArray(34) }
        val riichi = BooleanArray(players)
        var melds = 0

        fun record(tile: String, change: Int, reveal: Boolean): Int {
            val kind = tileKind(tile)
            require(kind >= 0 && (players == 4 || kind !in 1..7))
            hand[kind] += change
            if (reveal) visible[kind]++
            require(hand[kind] in 0..4 && visible[kind] <= 4)
            return kind
        }

        fun recordMeld(type: String, consumed: JSONArray, own: Boolean) {
            val size = when (type) {
                "ankan" -> 4
                "daiminkan" -> 3
                else -> 2
            }
            require(consumed.length() == size)
            for (i in 0 until size) record(consumed.getString(i), if (own) -1 else 0, !own)
            if (own) require(++melds <= 4)
        }
    }

    private fun readPosition(request: JSONObject): Position? {
        val events = request.getJSONArray("events")
        val start = (events.length() - 1 downTo 0).firstOrNull {
            events.getJSONObject(it).optString("type") == "start_kyoku"
        } ?: return null
        val round = events.getJSONObject(start)
        val players = round.getJSONArray("scores").length()
        val seat = request.getInt("player_id")
        require(players in 3..4 && seat in 0 until players)
        val position = Position(players, seat, round.getInt("oya"), tileKind(round.getString("bakaze")))
        val initial = round.getJSONArray("tehais").getJSONArray(seat)
        require(initial.length() == 13)
        for (i in 0 until initial.length()) position.record(initial.getString(i), 1, true)
        position.record(round.getString("dora_marker"), 0, true)
        for (i in start + 1 until events.length()) {
            val event = events.getJSONObject(i)
            val type = event.optString("type")
            val actor = event.optInt("actor", -1)
            val own = actor == seat
            when (type) {
                "tsumo" -> if (own) position.record(event.getString("pai"), 1, true)
                "dahai" -> {
                    val kind = position.record(event.getString("pai"), if (own) -1 else 0, !own)
                    position.discards[actor][kind] = true
                }
                in meldTypes -> position.recordMeld(type, event.getJSONArray("consumed"), own)
                "kakan", "kita", "nukidora" -> {
                    val tile = if (type == "kakan") event.getString("pai") else "N"
                    position.record(tile, if (own) -1 else 0, !own)
                }
                "dora" -> position.record(event.getString("dora_marker"), 0, true)
                "reach_accepted" -> position.riichi[actor] = true
            }
        }
        return position
    }

    private fun shapeAfter(type: String, action: JSONObject, position: Position): List<String> {
        val prefix = when (type) {
            "dahai", "reach" -> {
                position.record(action.getString("pai"), -1, false)
                "切后"
            }
            "chi", "pon" -> "副露前"
            "ankan", "daiminkan" -> {
                position.recordMeld(type, action.getJSONArray("consumed"), own = true)
                "补牌前"
            }
            "kakan", "kita", "nukidora" -> {
                position.record(if (type == "kakan") action.getString("pai") else "N", -1, false)
                "补牌前"
            }
            else -> "当前"
        }
        val handSize = position.hand.sum() + 3 * position.melds
        if (handSize !in 13..14) return emptyList()
        val distance = shanten(position.hand, position.melds)
        if (distance < 0) return listOf("当前手牌已成和牌形")
        val summary = "$prefix $distance 向听" + if (distance == 0) " · 听牌" else ""
        if (handSize == 14) return listOf(summary)

        var remaining = 0
        val effective = mutableListOf<Int>()
        for (kind in position.hand.indices) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (position.visible[kind] >= 4 || (position.players == 3 && kind in 1..7)) continue
            position.hand[kind]++
            if (shanten(position.hand, position.melds) < distance) {
                effective += kind
                remaining += 4 - position.visible[kind]
            }
            position.hand[kind]--
        }
        val category = if (distance == 0) "和牌张" else "有效牌"
        val names = effective.take(4).joinToString("、") { tileName(kindCode(it)) }
        val suffix = if (effective.size > 4) "等 ${effective.size} 种" else ""
        return listOf(summary, if (effective.isEmpty()) "$category · 未见 0 张"
            else "$category $names$suffix · 未见 $remaining 张")
    }

    private fun note(type: String, action: JSONObject, position: Position?): String? = when (type) {
        "dahai" -> discardNote(action, position)
        "reach" -> "立直需付 1000 点，之后基本不能改牌形"
        "chi" -> "组成顺子，副露后无法立直，需确认有役"
        "pon" -> {
            val tile = tileKind(action.optString("pai"))
            val seatWind = position?.let { 27 + (it.seat - it.dealer + it.players) % it.players }
            if (tile >= 27 && (tile >= 31 || tile == seatWind || tile == position?.wind)) {
                "碰成役牌，副露后已有和牌役"
            } else {
                "组成刻子，副露后无法立直，需确认有役"
            }
        }
        "kita", "nukidora" -> "拔北计宝牌，补摸一张，保留门清"
        "ankan" -> "保留门清并补摸，杠宝牌也可能利于对手"
        "daiminkan" -> "补摸岭上牌，杠宝牌也可能利于对手"
        "kakan" -> "补摸并翻杠宝牌，对手可以抢杠和牌"
        "hora" -> if (action.optInt("actor", -1) == action.optInt("target", -2)) "已满足自摸和牌条件"
            else "当前可荣和，接受后结算"
        "ryukyoku" -> "流局结束本小局，进入下一小局"
        "none" -> "跳过可保留当前牌形和门清状态"
        else -> null
    }

    private fun discardNote(action: JSONObject, position: Position?): String? {
        if (position == null) return null
        if (position.riichi[position.seat]) return "已立直，通常只能打出刚摸到的牌"
        val tile = tileKind(action.optString("pai"))
        if (tile < 0) return null
        val safe = position.riichi.indices.filter {
            it != position.seat && position.riichi[it] && position.discards[it][tile]
        }.map {
            when ((it - position.seat + position.players) % position.players) {
                1 -> "下家"
                position.players - 1 -> "上家"
                else -> "对家"
            }
        }
        return if (safe.isEmpty()) null else "对已立直的${safe.joinToString("、")}是现物"
    }

    private fun shanten(hand: IntArray, fixed: Int): Int {
        var best = 8
        fun search(start: Int, sets: Int, parts: Int, pair: Int) {
            if (best == -1) return
            var kind = start
            while (kind < 34 && hand[kind] == 0) kind++
            if (kind == 34) {
                best = minOf(best, 8 - 2 * sets - minOf(parts, 4 - sets) - pair)
                return
            }
            val count = hand[kind]
            val rank = if (kind < 27) kind % 9 else 9
            hand[kind]--
            search(kind, sets, parts, pair)
            if (count >= 2) {
                hand[kind]--
                if (pair == 0) search(kind, sets, parts, 1)
                if (sets + parts < 4) search(kind, sets, parts + 1, pair)
                if (count >= 3 && sets < 4) {
                    hand[kind]--
                    search(kind, sets + 1, parts, pair)
                    hand[kind]++
                }
                hand[kind]++
            }
            if (rank < 8 && hand[kind + 1] > 0) {
                hand[kind + 1]--
                if (sets + parts < 4) search(kind, sets, parts + 1, pair)
                if (rank < 7 && hand[kind + 2] > 0 && sets < 4) {
                    hand[kind + 2]--
                    search(kind, sets + 1, parts, pair)
                    hand[kind + 2]++
                }
                hand[kind + 1]++
            }
            if (rank < 7 && hand[kind + 2] > 0 && sets + parts < 4) {
                hand[kind + 2]--
                search(kind, sets, parts + 1, pair)
                hand[kind + 2]++
            }
            hand[kind]++
        }
        search(0, fixed, 0, 0)
        if (fixed > 0) return best
        val orphans = intArrayOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)
        val sevenPairs = 6 - hand.count { it >= 2 } + (7 - hand.count { it > 0 }).coerceAtLeast(0)
        val thirteenOrphans = 13 - orphans.count { hand[it] > 0 } -
            if (orphans.any { hand[it] >= 2 }) 1 else 0
        return minOf(best, sevenPairs, thirteenOrphans)
    }

    private fun tileKind(tile: String): Int {
        if (tile.length == 1) return WINDS_AND_DRAGONS.indexOf(tile[0]).let { if (it < 0) -1 else 27 + it }
        if (tile.length !in 2..3) return -1
        val suit = "mps".indexOf(tile[1])
        val rank = tile[0].digitToIntOrNull() ?: return -1
        if (suit < 0 || (tile.length == 3 && (rank != 5 || tile[2] != 'r'))) return -1
        return suit * 9 + (if (rank == 0) 5 else rank) - 1
    }

    private fun kindCode(kind: Int): String =
        if (kind >= 27) WINDS_AND_DRAGONS[kind - 27].toString() else "${kind % 9 + 1}${"mps"[kind / 9]}"
}
