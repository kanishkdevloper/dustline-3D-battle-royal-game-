package com.dustline.game

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*

/** #idScreen: "CREATE YOUR ID" - name 3..16 chars, CONFIRM. */
class IdScreenView(ctx: Context) : FrameLayout(ctx) {
    var onConfirm: ((String) -> Unit)? = null
    private val input: EditText
    private val err: TextView

    init {
        val d = resources.displayMetrics.density
        setBackgroundDrawable(object : android.graphics.drawable.Drawable() {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(c: Canvas) {
                val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
                p.shader = LinearGradient(0f, 0f, w, h, 0xFF1B1426.toInt(), 0xFF0B0911.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
                p.shader = RadialGradient(w * 0.8f, h * 0.1f, w * 0.5f, 0x59AA5014, 0x00AA5014, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
                p.shader = RadialGradient(w * 0.1f, h * 0.95f, w * 0.55f, 0x66642D7D, 0x00642D7D, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
            }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(f: ColorFilter?) {}
            @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.OPAQUE
        })
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (14 * d).toInt(), (22 * d).toInt(), (14 * d).toInt())
            setBackgroundColor(0xEB0E0B16.toInt())
        }
        fun tv(t: String, size: Float, col: Int, sp: Float, bold: Boolean = true) = TextView(ctx).apply {
            text = t; textSize = size; setTextColor(col); letterSpacing = sp
            typeface = if (bold) Ui.FONT_BOLD else Ui.FONT_MED
        }
        box.addView(tv("CREATE YOUR ID", 24f, Color.WHITE, 0.14f))
        box.addView(tv("CHOOSE YOUR IN-GAME NAME", 12f, 0xFFB9B2C6.toInt(), 0.12f, false))
        input = EditText(ctx).apply {
            hint = "Enter name"; setHintTextColor(0x88FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 20f
            typeface = Ui.FONT_BOLD; letterSpacing = 0.08f; maxLines = 1; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(android.text.InputFilter.LengthFilter(16))
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            background = GradientDrawable().apply { setColor(0x8C000000.toInt()); setStroke((2 * d).toInt(), 0xFF5B5367.toInt()) }
            setOnFocusChangeListener { v, f -> (v.background as GradientDrawable).setStroke((2 * d).toInt(), if (f) Ui.GOLD else 0xFF5B5367.toInt()) }
        }
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * d).toInt()).apply { topMargin = (10 * d).toInt() })
        err = tv("", 12f, 0xFFFF6A5A.toInt(), 0.08f, false).apply { minHeight = (18 * d).toInt() }
        box.addView(err)
        val go = Button(ctx).apply {
            text = "CONFIRM"; textSize = 22f; typeface = Ui.FONT_BOLD; letterSpacing = 0.28f; setTextColor(Ui.INK)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.GOLD_LIGHT, Ui.GOLD, Ui.GOLD_DARK))
            stateListAnimator = null
        }
        box.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (50 * d).toInt()))
        go.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        addView(box, LayoutParams((420 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    private fun submit() {
        val v = input.text.toString().replace(Regex("\\s+"), " ").trim()
        if (v.length < 3 || v.length > 16) { err.text = "NAME MUST BE 3 - 16 CHARACTERS"; return }
        err.text = ""
        onConfirm?.invoke(v)
    }

    fun focusInput() { input.requestFocus() }
}
