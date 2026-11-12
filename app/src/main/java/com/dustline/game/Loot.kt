package com.dustline.game

/** Ground items. Same families as the HTML: weapons, ammo (smg / sniper / sg), vests Lv1-3, medkit, armor plate. */
class Loot(val type: Int, val sub: Int, var x: Float, var y: Float, var z: Float) {
    var active = true
    /** WEAPON only: rounds left in the magazine when the player dropped it (-1 = fresh gun, full magazine) */
    var ammo = -1
    val spin = (Math.random() * 6.28).toFloat()
    /** Phase 28: items of a dead bot lie INSIDE a wooden crate (LootCrate) until you are near it. null = plain ground item. */
    var crate: LootCrate? = null
}

object LootType { const val WEAPON = 0; const val AMMO = 1; const val VEST = 2; const val MEDKIT = 3; const val ARMOR = 4; const val SCOPE = 5; const val GRENADE = 6
    const val VEST4 = 7; const val HELM4 = 8; const val SHROOM = 9 }

object AmmoInfo {
    val NAMES = arrayOf("SMG", "SNIPER", "SG")
    val CAP = intArrayOf(300, 60, 40)
    val BOX = intArrayOf(60, 10, 8)
    val COLORS = intArrayOf(0xFFE0B84F.toInt(), 0xFFE5484D.toInt(), 0xFFF08A24.toInt())
    val VEST_NAMES = arrayOf("", "Lv.1 Combat Vest", "Lv.2 Armor Vest", "Lv.3 Battle Vest")
    val VEST_COLORS = intArrayOf(0, 0xFF8FBF6A.toInt(), 0xFF5FB4E8.toInt(), 0xFFB57BFF.toInt())
    fun typeOf(g: Gun) = when (g.cls) { "shotgun" -> 2; "dmr", "sniper", "amr" -> 1; else -> 0 }
}

fun lootLabel(l: Loot): String = when (l.type) {
    LootType.WEAPON -> Weapons.ALL[l.sub].name
    LootType.AMMO -> AmmoInfo.NAMES[l.sub] + " AMMO"
    LootType.VEST -> AmmoInfo.VEST_NAMES[l.sub]
    LootType.MEDKIT -> "MEDKIT"
    LootType.SCOPE -> l.sub.toString() + "X SCOPE"
    LootType.GRENADE -> "GRENADE"
    LootType.VEST4 -> "Lv.4 Vest"
    LootType.HELM4 -> "Lv.4 Helmet"
    LootType.SHROOM -> "MUSHROOM"
    else -> "ARMOR PLATE"
}
