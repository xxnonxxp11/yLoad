package com.cookiegames.smartcookie.browser

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import com.cookiegames.smartcookie.IncognitoActivity
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.browser.activity.BrowserActivity
import com.cookiegames.smartcookie.database.Bookmark
import com.cookiegames.smartcookie.download.DownloadActivity
import com.cookiegames.smartcookie.history.HistoryActivity
import com.cookiegames.smartcookie.settings.activity.SettingsActivity
import com.cookiegames.smartcookie.utils.IntentUtils
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * BottomSheet menu modeled directly after Via Browser's 10-item quick action grid.
 */
class ViaMenuBottomSheet(private val activity: BrowserActivity) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_via_menu, null)
        dialog.setContentView(view)

        // Make bottom sheet container transparent to preserve rounded top corners
        try {
            dialog.window?.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.background = ColorDrawable(Color.TRANSPARENT)
        } catch (e: Exception) {}

        val currentTab = activity.tabsManager.currentTab

        // 1. Modo nocturno (Invert / Dark Mode toggle)
        view.findViewById<View>(R.id.btn_via_night_mode).setOnClickListener {
            dialog.dismiss()
            activity.userPreferences.invertColors = !activity.userPreferences.invertColors
            currentTab?.reload()
        }

        // 2. Marcadores (Open Bookmark Drawer)
        view.findViewById<View>(R.id.btn_via_bookmarks).setOnClickListener {
            dialog.dismiss()
            activity.drawer_layout.openDrawer(activity.getBookmarkDrawer())
        }

        // 3. Historial (History Activity)
        view.findViewById<View>(R.id.btn_via_history).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, HistoryActivity::class.java))
        }

        // 4. Descargas (Downloads Activity / Page)
        view.findViewById<View>(R.id.btn_via_downloads).setOnClickListener {
            dialog.dismiss()
            if (activity.userPreferences.useNewDownloader) {
                activity.startActivity(Intent(activity, DownloadActivity::class.java))
            } else {
                currentTab?.loadDownloadsPage()
            }
        }

        // 5. Modo incógnito (Launch Incognito Mode)
        view.findViewById<View>(R.id.btn_via_incognito).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(IncognitoActivity.createIntent(activity, null))
        }

        // 6. Compartir (Share URL)
        view.findViewById<View>(R.id.btn_via_share).setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                IntentUtils(activity).shareUrl(tab.url, tab.title)
            }
        }

        // 7. Añadir marcador (Add Bookmark Dialog)
        view.findViewById<View>(R.id.btn_via_add_bookmark).setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                val bookmark = Bookmark.Entry(tab.url, tab.title, 0, Bookmark.Folder.Root)
                activity.bookmarksDialogBuilder.showAddBookmarkDialog(activity, activity, bookmark)
            }
        }

        // 8. Sitio de escritorio (Toggle Desktop User Agent)
        view.findViewById<View>(R.id.btn_via_desktop).setOnClickListener {
            dialog.dismiss()
            currentTab?.let { tab ->
                tab.toggleDesktopUA()
                tab.reload()
                val isDesktop = tab.toggleDesktop
                Toast.makeText(activity, if (isDesktop) "Sitio de escritorio activado" else "Sitio móvil activado", Toast.LENGTH_SHORT).show()
            }
        }

        // 9. Herramientas (Find in page)
        view.findViewById<View>(R.id.btn_via_tools).setOnClickListener {
            dialog.dismiss()
            activity.findInPage()
        }

        // 10. Ajustes (Settings Activity)
        view.findViewById<View>(R.id.btn_via_settings).setOnClickListener {
            dialog.dismiss()
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }

        // Salir (Close app)
        view.findViewById<View>(R.id.btn_via_exit).setOnClickListener {
            dialog.dismiss()
            activity.finish()
        }

        // Cerrar menú
        view.findViewById<View>(R.id.btn_via_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
