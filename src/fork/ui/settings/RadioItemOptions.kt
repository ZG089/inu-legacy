package desu.inugram.ui.settings

import android.view.Gravity
import android.view.View
import org.telegram.messenger.LocaleController
import org.telegram.ui.Cells.TextCell
import org.telegram.ui.Components.ItemOptions

object RadioItemOptions {
    fun show(
        fragment: SettingsPageActivity,
        anchor: View,
        items: List<CharSequence>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ) {
        val options = ItemOptions.makeOptions(fragment, anchor).setLongPressSelectionEnabled(false)
        items.forEachIndexed { index, text ->
            options.addChecked(index == selectedIndex, text) {
                if (index == selectedIndex) return@addChecked
                (anchor as? TextCell)?.setValue(text, true)
                anchor.requestLayout()
                onSelect(index)
                fragment.listView.adapter.update(true)
            }
        }
        options.setGravity(if (LocaleController.isRTL) Gravity.LEFT else Gravity.RIGHT)
        options.show()
    }
}
