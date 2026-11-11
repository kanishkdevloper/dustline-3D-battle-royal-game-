package com.dustline.game

/** Same table as GUNS in the HTML: [id,name,slot,mag,damage,delay,reload,spread,effectiveRange,recoil,class] + extras. */
class Gun(
    val id: String, val name: String, val slot: Int, val mag: Int, val damage: Int,
    val delay: Float, val reload: Float, val spread: Float, val eff: Float, val recoil: Float,
    val cls: String, val semi: Boolean = false, val body: Int? = null, val head: Int? = null, val pellets: Int = 1
)

object Weapons {
    val ALL = listOf(
        Gun("mp5", "MP5", 0, 30, 17, .075f, 1.5f, .012f, 75f, .005f, "smg"),
        Gun("ump", "UMP", 0, 25, 22, .1f, 1.7f, .013f, 70f, .006f, "smg"),
        Gun("mp40", "MP40", 0, 32, 19, .11f, 2.0f, .014f, 62f, .006f, "smg"),
        Gun("vector", "VECTOR", 1, 25, 21, .052f, 1.7f, .018f, 60f, .005f, "smg"),
        Gun("bizon", "BIZON", 1, 64, 15, .085f, 2.6f, .014f, 65f, .005f, "smg"),
        Gun("p90", "P90", 1, 50, 16, .065f, 2.1f, .015f, 75f, .004f, "smg"),
        Gun("vss", "VSS", 2, 10, 55, .2f, 2.4f, .004f, 170f, .01f, "smg"),
        Gun("m590", "M590", 0, 7, 26, .9f, 3.2f, .05f, 16f, .03f, "shotgun", semi = true, pellets = 8),
        Gun("vsk94", "VSK-94", 2, 10, 62, .24f, 2.4f, .004f, 260f, .012f, "dmr", semi = true, body = 62, head = 300),
        Gun("kar98k", "Kar98k", 2, 5, 132, 1.1f, 3.0f, .0025f, 450f, .04f, "sniper", semi = true, body = 132, head = 560),
        Gun("m24", "M24 Sniper", 2, 5, 144, 1.3f, 3.1f, .002f, 500f, .045f, "sniper", semi = true, body = 144, head = 650),
        Gun("awm", "AWM", 2, 5, 150, 1.5f, 3.3f, .0015f, 550f, .05f, "sniper", semi = true, body = 150, head = 724),
        Gun("m82", "M82 Sniper", 2, 5, 160, 1.05f, 3.6f, .002f, 650f, .06f, "amr", semi = true, body = 160, head = 825)
    )
}
