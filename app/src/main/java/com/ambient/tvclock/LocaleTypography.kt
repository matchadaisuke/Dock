package com.ambient.tvclock

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/**
 * Keeps CJK text within one font family instead of mixing the app's Latin-only
 * Manrope/JetBrains Mono glyphs with an unrelated system fallback mid-string.
 *
 * Latin-only labels keep the intended display fonts. When a TextView contains
 * Japanese/Chinese/Korean glyphs, the complete run (digits and punctuation
 * included) moves to Android's system sans family, so baseline, weight and
 * punctuation metrics stay coherent.
 */
object LocaleTypography {

    private data class OriginalStyle(
        val typeface: Typeface,
        val letterSpacing: Float,
    )

    private val originals = WeakHashMap<TextView, OriginalStyle>()
    private val watchers = WeakHashMap<TextView, TextWatcher>()

    fun apply(root: View) {
        when (root) {
            is TextView -> attach(root)
            is ViewGroup -> {
                for (index in 0 until root.childCount) {
                    apply(root.getChildAt(index))
                }
            }
        }
    }

    private fun attach(textView: TextView) {
        if (watchers.containsKey(textView)) return

        originals[textView] = OriginalStyle(
            typeface = textView.typeface ?: Typeface.DEFAULT,
            letterSpacing = textView.letterSpacing,
        )

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyForText(textView, s)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        watchers[textView] = watcher
        textView.addTextChangedListener(watcher)
        applyForText(textView, textView.text)
    }

    private fun applyForText(textView: TextView, text: CharSequence?) {
        val original = originals[textView] ?: return
        if (containsCjk(text)) {
            val style = original.typeface.style
            textView.typeface = Typeface.create("sans-serif", style)
            // Wide tracking designed for Latin eyebrow text looks broken in Japanese.
            if (original.letterSpacing > CJK_MAX_LETTER_SPACING) {
                textView.letterSpacing = CJK_MAX_LETTER_SPACING
            }
        } else {
            textView.typeface = original.typeface
            textView.letterSpacing = original.letterSpacing
        }
    }

    private fun containsCjk(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        for (char in text) {
            val code = char.code
            if (code in 0x3040..0x30FF || // Hiragana + Katakana
                code in 0x3400..0x4DBF || // CJK Extension A
                code in 0x4E00..0x9FFF || // CJK Unified Ideographs
                code in 0xAC00..0xD7AF    // Hangul syllables
            ) {
                return true
            }
        }
        return false
    }

    private const val CJK_MAX_LETTER_SPACING = 0.02f
}
