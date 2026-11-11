package com.dustline.game

import kotlin.math.max
import kotlin.math.min

/** numbers shown on the result screen after a match (win or elimination) */
class MatchSummary(val won: Boolean, val place: Int, val total: Int, val kills: Int, val damage: Int, val time: Float, val xp: Int, val coins: Int)

class MatchResult(val won: Boolean, val quit: Boolean, val place: Int, val kills: Int, val time: Float, val xp: Int, val coins: Int, val label: String)

/** Same payout as window.__dlXp in the HTML. */
object Rewards {
    fun compute(kills: Int, place: Int, won: Boolean, time: Float, quit: Boolean): MatchResult? {
        val t = max(0f, time)
        if (quit && t < 30f) return null
        val mins = (t / 60f).toInt()
        var xp = 100 + mins * 15 + kills * 25
        if (!quit) { if (place in 1..3) xp += 100 else if (place in 4..10) xp += 50; if (won) xp += 150 } else xp /= 2
        xp = min(1000, xp)
        val dn = if (quit) 0 else 30; val tm = mins * 3; val el = kills * 10
        val t3 = if (!quit && place in 1..3) 60 else 0; val wn = if (!quit && won) 100 else 0
        val pf = if (quit) 0 else max(-10, min(30, (kills - 7) * 5))
        var sum = dn + tm + el + t3 + wn + pf
        if (quit) sum = (tm + el) / 2
        sum = max(0, sum)
        val label = listOf("MATCH" to dn, "TIME" to tm, "ELIMS" to el, "TOP3" to t3, "WIN" to wn, "PERF" to pf)
            .filter { it.second != 0 }.joinToString(" | ") { it.first + " " + (if (it.second > 0) "+" else "") + it.second }
        return MatchResult(won, quit, place, kills, t, xp, sum, label)
    }
}
