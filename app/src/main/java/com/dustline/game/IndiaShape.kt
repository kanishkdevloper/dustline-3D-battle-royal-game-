package com.dustline.game

import android.graphics.Path
import android.graphics.RectF

/**
 * LOOK ONLY: the outline of the map of India (traced from assets/ui/india_mask.png) used as the SHAPE of the minimap window
 * instead of a circle. Points are stored in 1/1000 units inside a square (0..1000 x 0..1000) that is centred on India.
 * [PIC_*] = where that square sits inside the (warped) map picture, as picture UV 0..1, so the picture can be zoomed to fill the window exactly.
 */
object IndiaShape {
    private const val DATA = "844,976;835,984;835,993;841,1000;852,1000;857,995;857,980;853,976|834,939;826,944;824,952;829,961;839,962;846,948;838,939|178,924;166,929;166,937;173,943;184,937;184,929|810,909;804,913;807,924;815,925;822,920;822,913;819,909|803,854;797,859;795,868;801,879;808,880;816,875;818,861;810,854|147,850;140,854;139,864;144,869;151,869;156,865;158,857;154,852|822,769;815,769;811,773;805,786;801,837;811,848;816,848;826,838;830,816;831,785|234,0;193,7;178,15;171,26;171,45;197,76;212,125;231,150;235,161;234,174;222,192;212,219;189,237;174,257;154,275;143,293;120,299;109,306;90,339;79,386;67,401;48,405;23,426;22,431;29,442;53,463;49,475;52,488;80,518;97,531;120,536;136,533;155,522;163,524;166,590;173,631;181,652;190,695;208,733;222,776;253,837;260,842;265,854;278,903;291,935;299,941;321,948;351,921;374,913;382,903;381,890;401,873;404,831;414,808;418,782;412,741;415,709;430,698;465,683;482,672;535,622;550,612;561,599;607,567;624,559;656,520;686,517;701,510;706,498;702,467;682,431;678,412;679,399;693,369;720,376;729,392;747,404;789,405;795,410;795,418;777,427;766,442;766,472;782,475;789,469;795,469;800,476;804,501;811,507;829,509;839,499;841,480;846,471;850,446;871,430;882,408;903,392;920,356;933,343;955,333;969,322;978,305;971,297;947,288;924,264;867,261;824,290;789,305;784,312;784,318;790,327;790,333;771,339;721,339;709,335;706,329;707,303;697,295;676,297;669,301;664,313;665,341;656,348;624,347;567,331;536,316;520,316;490,306;454,286;426,264;431,248;445,238;449,231;449,226;444,219;423,204;386,188;380,178;380,173;388,162;423,146;431,133;424,65;415,59;370,48;363,50;340,49;313,41;290,29;271,8;250,0"
    const val PIC_CU = 0.49951f   // centre of the India square in picture UV
    const val PIC_CV = 0.50049f
    const val PIC_SIDE = 0.71777f // side of the India square in picture UV

    private val polys: List<FloatArray> by lazy {
        val out = ArrayList<FloatArray>()
        for (part in DATA.split('|')) {
            val pts = part.split(';')
            val f = FloatArray(pts.size * 2)
            for ((i, s) in pts.withIndex()) { val xy = s.split(','); f[i * 2] = xy[0].toFloat() / 1000f; f[i * 2 + 1] = xy[1].toFloat() / 1000f }
            out.add(f)
        }
        out
    }

    /** The minimap window: a SQUARE with side 2 * [half] around ([cx], [cy]) (it used to be the India outline). */
    fun path(cx: Float, cy: Float, half: Float, into: Path = Path()): Path {
        into.reset()
        into.addRect(cx - half, cy - half, cx + half, cy + half, Path.Direction.CW)
        return into
    }

    /** Where the WHOLE map picture must be drawn so that its India lands exactly on the window [w] (the window is the India square). */
    fun pictureRect(w: RectF, into: RectF = RectF()): RectF {
        val sz = w.width() / PIC_SIDE
        val l = w.centerX() - PIC_CU * sz; val t = w.centerY() - PIC_CV * sz
        into.set(l, t, l + sz, t + sz)
        return into
    }
}
