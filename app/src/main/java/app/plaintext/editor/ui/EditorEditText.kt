package app.plaintext.editor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/** EditText that reports selection changes and lets a hardware Tab / Shift+Tab key indent instead of moving focus. */
class EditorEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {

    var onSelection: ((Int, Int) -> Unit)? = null
    /** Return true if the key was handled. Argument: shift pressed. */
    var onTabKey: ((Boolean) -> Boolean)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelection?.invoke(selStart, selEnd)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isAltPressed) {
            if (onTabKey?.invoke(event.isShiftPressed) == true) return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
