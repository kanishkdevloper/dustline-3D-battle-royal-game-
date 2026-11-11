package com.dustline.game

/**
 * First-person reload animation.
 *
 * MOTION DATA: baked from the "Reload" clip of "M4 - FPS Weapon Animations Pack (v.1)" by BarcodeGames
 * (https://sketchfab.com/barcodegames), licensed CC-BY-4.0 (http://creativecommons.org/licenses/by/4.0/),
 * source: https://sketchfab.com/3d-models/m4-fps-weapon-animations-pack-v1-662fc74dda2646cfb48fc610705768ef
 * Only the MOTION of five joints (gun, magazine, left hand, right hand) was sampled (56 frames) and re-targeted onto
 * Dustline's own guns / hands (tools/bake_reload.py). No mesh or texture of the pack is used.
 * CHANGES: motion re-sampled, scaled and re-targeted. The author does not endorse this game. Credit is in Settings > Account > Credits.
 *
 * Frame layout (15 floats per frame, metres / degrees, camera-aligned axes: +X right, +Y up, -Z forward):
 *   [0..2] gun position delta   [3..5] gun rotation (deg about X, Y, Z; apply Z, then Y, then X)
 *   [6..8] magazine delta       [9..11] left-hand delta    [12..14] right-hand delta
 * Deltas are measured against the aiming pose (frame 0) and are RELATIVE TO THE GUN, in the unscaled gun space.
 */
object ReloadAnim {
    const val N = 56
    const val OUT = 16
    private const val K = 15

    /** per-gun anchor points (gun space, metres, before the renderer's 0.8 model scale). Filled by rigFor(). */
    class Rig {
        var restX = 0f; var restY = 0f; var restZ = 0f      // left hand on the hand-guard
        var magX = 0f; var magY = 0f; var magZ = 0f         // centre of the magazine when seated
        var magH = 0.15f                                     // magazine height
        var dirY = -1f                                       // direction the magazine sits from the well (-1 = below, +1 = on top for the P90)
        var gripX = 0f; var gripY = 0f; var gripZ = 0f      // right hand (pistol grip)
    }

    // id -> magD (distance muzzle->mag centre), magTop (y of the mag top relative to the sight line), magH, gripD
    private class G(val magD: Float, val magTop: Float, val magH: Float, val gripD: Float, val top: Boolean = false)
    private val T = mapOf(
        "mp5" to G(.42f, -.03f, .17f, .54f), "ump" to G(.445f, -.035f, .20f, .58f), "mp40" to G(.445f, -.02f, .20f, .52f),
        "vector" to G(.265f, -.04f, .24f, .40f), "bizon" to G(.39f, -.055f, .09f, .54f), "p90" to G(.33f, .075f, .06f, .37f, true),
        "vss" to G(.465f, -.03f, .14f, .58f), "vsk94" to G(.425f, -.035f, .14f, .55f), "m590" to G(.55f, -.04f, .06f, .70f),
        "kar98k" to G(.60f, -.035f, .07f, .80f), "m24" to G(.60f, -.035f, .07f, .78f), "awm" to G(.595f, -.035f, .08f, .75f),
        "m82" to G(.79f, -.075f, .13f, .95f)
    )

    /** anchors of one gun from the values stored in its .gun file (zmin = -len/2, my = sight-line height) */
    fun rigFor(id: String, zmin: Float, my: Float, len: Float, r: Rig) {
        val g = T[id] ?: G(.42f, -.03f, .15f, .55f)
        r.magX = 0f; r.magZ = zmin + g.magD
        r.magH = g.magH; r.dirY = if (g.top) 1f else -1f
        r.magY = if (g.top) my + g.magTop else my + g.magTop - g.magH / 2f
        r.restX = -0.05f; r.restY = my - 0.05f; r.restZ = zmin + len * 0.30f
        r.gripX = 0.012f; r.gripY = my - 0.085f; r.gripZ = zmin + g.gripD + 0.02f
    }

    private fun sm(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t)
    }

    private const val INV = 1f / 0.8f   // deltas are real metres; the renderer multiplies gun-space by 0.8

    /**
     * u = 0..1 over the whole reload. out (OUT = 16):
     *  [0..2] gun position delta (m)  [3..5] gun rotation (deg X,Y,Z)
     *  [6..8] magazine centre   [9] magazine visible (1)   [10..12] left hand   [13..15] right hand
     * positions [6..15] are in unscaled gun space.
     */
    fun sample(u0: Float, r: Rig, o: FloatArray) {
        val u = u0.coerceIn(0f, 1f)
        val f = u * (N - 1); val i = f.toInt().coerceIn(0, N - 2); val t = f - i
        val a = i * K; val b = a + K
        fun v(k: Int) = D[a + k] + (D[b + k] - D[a + k]) * t
        o[0] = v(0); o[1] = v(1); o[2] = v(2)
        o[3] = v(3); o[4] = v(4); o[5] = v(5)
        // magazine: seated position + the pack's mag motion
        val mx = r.magX + v(6) * INV; val my = r.magY + v(7) * INV; val mz = r.magZ + v(8) * INV
        o[6] = mx; o[7] = my; o[8] = mz; o[9] = 1f
        // left hand: follows the pack's hand motion from the hand-guard, and while the magazine is out it HOLDS the magazine
        val lx = r.restX + v(9) * INV; val ly = r.restY + v(10) * INV; val lz = r.restZ + v(11) * INV
        val hold = sm(0.16f, 0.30f, u) * (1f - sm(0.56f, 0.66f, u))
        val hx = mx - 0.035f; val hy = my + r.dirY * r.magH * 0.30f; val hz = mz - 0.01f
        o[10] = lx + (hx - lx) * hold; o[11] = ly + (hy - ly) * hold; o[12] = lz + (hz - lz) * hold
        o[13] = r.gripX + v(12) * INV; o[14] = r.gripY + v(13) * INV; o[15] = r.gripZ + v(14) * INV
    }

    /** blend in / out (kept for compatibility) */
    fun weight(u: Float): Float = minOf(sm(0f, 0.1f, u), 1f - sm(0.9f, 1f, u))

    private val D = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        0.0000f, -0.0010f, -0.0007f, -0.1146f, 0.0159f, 0.5571f, 0.0011f, -0.0002f, 0.0002f, 0.0004f, -0.0012f, 0.0001f, 0.0010f, 0.0008f, 0.0002f,
        0.0000f, -0.0055f, -0.0056f, -0.7071f, 0.1574f, 3.4237f, 0.0065f, -0.0012f, 0.0014f, 0.0026f, -0.0071f, 0.0008f, 0.0059f, 0.0053f, 0.0010f,
        0.0000f, -0.0060f, -0.0126f, -1.0786f, 0.3563f, 4.6021f, 0.0086f, -0.0017f, 0.0022f, 0.0028f, -0.0112f, 0.0016f, 0.0084f, 0.0079f, 0.0015f,
        -0.0000f, 0.0024f, -0.0189f, 0.4944f, 0.4489f, 1.9960f, 0.0032f, 0.0007f, -0.0009f, -0.0059f, -0.0185f, 0.0113f, 0.0051f, -0.0007f, -0.0011f,
        -0.0000f, 0.0147f, -0.0217f, 4.0444f, 1.0694f, -5.1821f, -0.0114f, 0.0076f, -0.0076f, -0.0451f, -0.0541f, 0.0665f, -0.0056f, -0.0200f, -0.0078f,
        -0.0000f, 0.0252f, -0.0181f, 7.7084f, 2.9059f, -14.1306f, -0.0292f, 0.0185f, -0.0139f, -0.1325f, -0.1336f, 0.1865f, -0.0201f, -0.0395f, -0.0166f,
        -0.0000f, 0.0300f, -0.0101f, 9.8651f, 5.0200f, -21.0833f, -0.0428f, 0.0284f, -0.0171f, -0.1406f, -0.1530f, 0.2168f, -0.0319f, -0.0511f, -0.0233f,
        -0.0000f, 0.0302f, -0.0015f, 9.7895f, 5.8053f, -24.4779f, -0.0491f, 0.0318f, -0.0168f, -0.0966f, -0.1355f, 0.2030f, -0.0375f, -0.0521f, -0.0240f,
        -0.0001f, 0.0274f, 0.0036f, 8.8757f, 5.7036f, -25.8990f, -0.0515f, 0.0314f, -0.0153f, -0.0614f, -0.1162f, 0.1953f, -0.0397f, -0.0487f, -0.0218f,
        -0.0001f, 0.0228f, 0.0053f, 7.9138f, 4.9814f, -24.4955f, -0.0489f, 0.0279f, -0.0139f, -0.0520f, -0.1170f, 0.1964f, -0.0370f, -0.0443f, -0.0190f,
        -0.0001f, 0.0172f, 0.0062f, 7.0984f, 4.2548f, -22.2897f, -0.0449f, 0.0240f, -0.0126f, -0.0456f, -0.1218f, 0.1985f, -0.0331f, -0.0402f, -0.0165f,
        -0.0002f, 0.0114f, 0.0066f, 6.6639f, 4.0769f, -22.2405f, -0.0447f, 0.0231f, -0.0119f, -0.0454f, -0.1218f, 0.1999f, -0.0329f, -0.0383f, -0.0155f,
        -0.0003f, 0.0063f, 0.0067f, 7.4728f, 4.7034f, -23.7631f, -0.0499f, 0.0212f, -0.0139f, -0.0521f, -0.1236f, 0.1969f, -0.0355f, -0.0423f, -0.0177f,
        -0.0005f, -0.0002f, 0.0067f, 7.2054f, 4.2809f, -20.9307f, -0.0625f, -0.0272f, -0.0196f, -0.0614f, -0.1751f, 0.1914f, -0.0300f, -0.0405f, -0.0168f,
        -0.0013f, -0.0094f, 0.0067f, 3.7997f, 3.7126f, -19.9683f, -0.1067f, -0.1460f, -0.0126f, -0.1009f, -0.2839f, 0.2069f, -0.0248f, -0.0268f, -0.0095f,
        -0.0022f, -0.0137f, 0.0066f, 1.1219f, 2.5390f, -21.0645f, -0.2062f, -0.3273f, 0.0357f, -0.1900f, -0.4296f, 0.2734f, -0.0273f, -0.0150f, -0.0037f,
        -0.0027f, -0.0142f, 0.0066f, 0.8845f, 2.3924f, -18.6742f, -0.2367f, -0.4356f, 0.0998f, -0.2002f, -0.5006f, 0.3484f, -0.0231f, -0.0134f, -0.0032f,
        -0.0027f, -0.0091f, 0.0065f, 2.7830f, 3.2565f, -17.3452f, -0.1962f, -0.3809f, 0.2232f, -0.1407f, -0.4238f, 0.4742f, -0.0197f, -0.0216f, -0.0072f,
        -0.0023f, -0.0001f, 0.0064f, 5.8593f, 3.9722f, -16.8491f, -0.1686f, -0.3368f, 0.2387f, -0.1057f, -0.3852f, 0.4913f, -0.0200f, -0.0343f, -0.0137f,
        -0.0017f, 0.0102f, 0.0063f, 8.8963f, 4.7733f, -18.3734f, -0.1624f, -0.3123f, 0.2170f, -0.0950f, -0.3705f, 0.4708f, -0.0247f, -0.0470f, -0.0209f,
        -0.0011f, 0.0193f, 0.0063f, 10.8551f, 5.8176f, -21.9130f, -0.1740f, -0.2838f, 0.2040f, -0.1037f, -0.3483f, 0.4580f, -0.0328f, -0.0558f, -0.0264f,
        -0.0006f, 0.0250f, 0.0062f, 11.2953f, 6.6910f, -25.6697f, -0.2052f, -0.2340f, 0.1211f, -0.1507f, -0.3003f, 0.3735f, -0.0404f, -0.0586f, -0.0284f,
        -0.0003f, 0.0254f, 0.0061f, 10.9637f, 7.0145f, -28.1000f, -0.2221f, -0.1744f, 0.0184f, -0.1920f, -0.2509f, 0.2648f, -0.0450f, -0.0577f, -0.0280f,
        -0.0002f, 0.0221f, 0.0061f, 10.1195f, 6.8588f, -29.6236f, -0.1992f, -0.1214f, -0.0164f, -0.1900f, -0.2220f, 0.2186f, -0.0477f, -0.0544f, -0.0258f,
        -0.0002f, 0.0174f, 0.0061f, 9.2248f, 6.3321f, -29.7204f, -0.1569f, -0.0760f, -0.0341f, -0.1689f, -0.2009f, 0.1807f, -0.0477f, -0.0505f, -0.0232f,
        0.0000f, 0.0134f, 0.0061f, 8.3602f, 5.5003f, -28.2726f, -0.1142f, -0.0451f, -0.0366f, -0.1331f, -0.1830f, 0.1661f, -0.0452f, -0.0464f, -0.0204f,
        0.0002f, 0.0113f, 0.0062f, 7.6692f, 4.7326f, -26.5295f, -0.0879f, -0.0394f, -0.0343f, -0.1009f, -0.1810f, 0.1709f, -0.0423f, -0.0429f, -0.0182f,
        0.0004f, 0.0114f, 0.0067f, 7.8125f, 4.6570f, -26.1942f, -0.0860f, -0.0415f, -0.0349f, -0.0967f, -0.1847f, 0.1710f, -0.0420f, -0.0433f, -0.0184f,
        0.0002f, 0.0120f, 0.0065f, 8.3007f, 4.9074f, -26.7138f, -0.0883f, -0.0388f, -0.0337f, -0.0979f, -0.1848f, 0.1712f, -0.0433f, -0.0453f, -0.0197f,
        -0.0002f, 0.0085f, 0.0061f, 9.2095f, 5.3105f, -26.8469f, -0.0833f, -0.0268f, -0.0302f, -0.0926f, -0.1744f, 0.1740f, -0.0440f, -0.0491f, -0.0221f,
        -0.0014f, -0.0044f, 0.0059f, 9.6346f, 5.0135f, -24.9097f, -0.0485f, 0.0313f, -0.0168f, -0.0539f, -0.1162f, 0.1902f, -0.0411f, -0.0503f, -0.0228f,
        -0.0012f, 0.0054f, 0.0061f, 8.3198f, 4.0533f, -23.5509f, -0.0457f, 0.0270f, -0.0148f, -0.0498f, -0.1191f, 0.1940f, -0.0388f, -0.0443f, -0.0190f,
        -0.0009f, 0.0134f, 0.0063f, 7.9874f, 3.8124f, -24.2333f, -0.0465f, 0.0269f, -0.0142f, -0.0517f, -0.1174f, 0.1949f, -0.0407f, -0.0426f, -0.0180f,
        -0.0006f, 0.0153f, 0.0065f, 8.8379f, 3.6525f, -22.3143f, -0.0428f, 0.0266f, -0.0157f, -0.0548f, -0.1285f, 0.1892f, -0.0381f, -0.0455f, -0.0198f,
        -0.0004f, 0.0123f, 0.0064f, 8.7726f, 3.6447f, -22.3450f, -0.0429f, 0.0265f, -0.0156f, -0.0984f, -0.1713f, 0.1699f, -0.0381f, -0.0453f, -0.0196f,
        -0.0003f, 0.0051f, 0.0054f, 6.9413f, 3.2727f, -22.8813f, -0.0442f, 0.0235f, -0.0125f, -0.1737f, -0.2663f, 0.1338f, -0.0377f, -0.0381f, -0.0153f,
        -0.0003f, -0.0059f, 0.0026f, 3.4641f, 2.1179f, -20.8240f, -0.0410f, 0.0153f, -0.0064f, -0.1717f, -0.2774f, 0.1378f, -0.0321f, -0.0234f, -0.0075f,
        -0.0002f, -0.0198f, -0.0034f, -0.6303f, 1.0595f, -15.8793f, -0.0324f, 0.0048f, 0.0013f, -0.1104f, -0.1566f, 0.1094f, -0.0215f, -0.0050f, 0.0003f,
        -0.0002f, -0.0266f, -0.0111f, -3.6737f, 0.8640f, -9.9416f, -0.0215f, -0.0029f, 0.0075f, -0.0447f, -0.0538f, 0.0475f, -0.0101f, 0.0100f, 0.0048f,
        -0.0002f, -0.0218f, -0.0167f, -4.2164f, 1.0942f, -4.4494f, -0.0108f, -0.0056f, 0.0086f, -0.0081f, -0.0078f, 0.0068f, -0.0011f, 0.0151f, 0.0054f,
        -0.0001f, -0.0119f, -0.0168f, -2.9338f, 0.7022f, -0.7123f, -0.0025f, -0.0044f, 0.0059f, -0.0028f, -0.0077f, 0.0037f, 0.0020f, 0.0120f, 0.0039f,
        -0.0001f, -0.0021f, -0.0118f, -0.8053f, -0.0702f, 0.8243f, 0.0017f, -0.0013f, 0.0016f, 0.0009f, -0.0036f, 0.0006f, 0.0010f, 0.0039f, 0.0013f,
        -0.0001f, 0.0035f, -0.0057f, 0.8989f, -0.4997f, 1.2322f, 0.0032f, 0.0014f, -0.0018f, 0.0025f, 0.0009f, -0.0014f, -0.0000f, -0.0030f, -0.0012f,
        -0.0001f, 0.0031f, -0.0022f, 1.0598f, -0.3546f, 1.1027f, 0.0027f, 0.0017f, -0.0021f, 0.0019f, 0.0016f, -0.0014f, 0.0004f, -0.0038f, -0.0015f,
        -0.0001f, 0.0009f, -0.0005f, 0.3756f, -0.0646f, 0.2683f, 0.0006f, 0.0006f, -0.0007f, 0.0004f, 0.0007f, -0.0004f, 0.0002f, -0.0014f, -0.0006f,
        -0.0000f, -0.0017f, 0.0000f, -0.5523f, 0.0778f, -0.5662f, -0.0012f, -0.0008f, 0.0011f, -0.0007f, -0.0008f, 0.0006f, -0.0006f, 0.0020f, 0.0008f,
        -0.0000f, -0.0036f, 0.0000f, -1.2421f, 0.0042f, -0.6093f, -0.0012f, -0.0019f, 0.0025f, -0.0005f, -0.0028f, 0.0011f, -0.0009f, 0.0049f, 0.0019f,
        -0.0000f, -0.0043f, -0.0003f, -1.3307f, -0.1066f, -0.0760f, 0.0000f, -0.0021f, 0.0026f, 0.0003f, -0.0039f, 0.0010f, -0.0006f, 0.0056f, 0.0021f,
        -0.0000f, -0.0037f, -0.0005f, -1.1475f, -0.1653f, 0.2690f, 0.0008f, -0.0018f, 0.0023f, 0.0007f, -0.0038f, 0.0007f, -0.0003f, 0.0050f, 0.0019f,
        -0.0000f, -0.0024f, -0.0004f, -0.7925f, -0.1294f, 0.1129f, 0.0004f, -0.0013f, 0.0016f, 0.0005f, -0.0026f, 0.0005f, -0.0004f, 0.0034f, 0.0013f,
        -0.0000f, -0.0011f, -0.0001f, -0.4531f, -0.0569f, -0.1770f, -0.0003f, -0.0007f, 0.0009f, 0.0000f, -0.0011f, 0.0003f, -0.0005f, 0.0018f, 0.0008f,
        -0.0000f, -0.0001f, -0.0000f, -0.2238f, -0.0108f, -0.3331f, -0.0006f, -0.0003f, 0.0004f, -0.0002f, -0.0002f, 0.0002f, -0.0006f, 0.0007f, 0.0004f,
        0.0000f, 0.0000f, 0.0000f, -0.0883f, -0.0032f, -0.2467f, -0.0005f, -0.0001f, 0.0002f, -0.0002f, 0.0001f, 0.0001f, -0.0004f, 0.0002f, 0.0001f,
        0.0000f, 0.0000f, 0.0000f, -0.0198f, -0.0004f, -0.0864f, -0.0002f, -0.0000f, 0.0000f, -0.0001f, 0.0001f, 0.0000f, -0.0001f, 0.0000f, 0.0000f,
        0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f
    )
}
