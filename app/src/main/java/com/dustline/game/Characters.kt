package com.dustline.game

import android.graphics.Color

/** The only playable character of the Android build: SURVIVOR (other skins were removed on request). */
class Outfit(val shirt: String, val vest: String, val pants: String, val skin: String, val hair: String, val head: String, val headColor: String)
/** price = COINS to buy this character in STORE > PRESET (0 = free / owned from the start, e.g. SURVIVOR). A new Character added to ALL shows up in PRESET by itself. */
class Character(val id: String, val name: String, val o: Outfit, val price: Int = 0)

object Characters {
    val ALL = listOf(
        Character("survivor", "SURVIVOR", Outfit("#6a5a3a", "#3b2f1e", "#3b5170", "#c58f6b", "#3b2718", "none", "#3b2f1e"))
    )
    /** HAIRSTYLES / HAIRS of the wardrobe in the HTML */
    val HAIR_STYLES = listOf("short", "buzz", "tied", "bald")
    val HAIRS = listOf("#17110d", "#2a1c12", "#3d2a1a", "#5e4228", "#8b6a3a", "#101010", "#7a7a78")
    fun defaultHairStyle(id: String) = "short"
    fun col(s: String) = Color.parseColor(s)
}
