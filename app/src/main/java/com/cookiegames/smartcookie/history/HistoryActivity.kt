/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Created by CookieJarApps 10/01/2020 */

package com.cookiegames.smartcookie.history

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.*
import android.widget.Filter
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import butterknife.ButterKnife
import com.cookiegames.smartcookie.AppTheme
import com.cookiegames.smartcookie.R
import com.cookiegames.smartcookie.database.HistoryEntry
import com.cookiegames.smartcookie.database.history.HistoryRepository
import com.cookiegames.smartcookie.di.injector
import com.cookiegames.smartcookie.dialog.LightningDialogBuilder
import com.cookiegames.smartcookie.favicon.FaviconModel
import com.cookiegames.smartcookie.preference.UserPreferences
import com.cookiegames.smartcookie.utils.ThemeUtils
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class HistoryActivity : AppCompatActivity(), SearchView.OnQueryTextListener {
    @JvmField
    @Inject
    var mUserPreferences: UserPreferences? = null

    @JvmField
    @Inject
    var dialogBuilder: LightningDialogBuilder? = null

    @Inject
    internal lateinit var historyRepository: HistoryRepository

    @Inject
    internal lateinit var faviconModel: FaviconModel

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var arrayAdapter: CustomAdapter
    private var historyList: List<HistoryEntry> = emptyList()
    private val compositeDisposable = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        injector.inject(this)

        val color: Int
        if (mUserPreferences?.useTheme === AppTheme.LIGHT) {
            setTheme(R.style.Theme_SettingsTheme)
            color = ThemeUtils.getColorBackground(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        } else if (mUserPreferences?.useTheme === AppTheme.DARK) {
            setTheme(R.style.Theme_SettingsTheme_Dark)
            color = ThemeUtils.getColorBackground(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        } else {
            setTheme(R.style.Theme_SettingsTheme_Black)
            color = ThemeUtils.getColorBackground(this)
            window.setBackgroundDrawable(ColorDrawable(color))
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        ButterKnife.bind(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        list = findViewById(R.id.history)
        emptyView = findViewById(R.id.empty_history_view)

        list.layoutManager = LinearLayoutManager(this)
        arrayAdapter = CustomAdapter(
            historyList,
            faviconModel,
            onItemClick = { entry ->
                val i = Intent(ACTION_VIEW).apply {
                    data = Uri.parse(entry.url)
                    setPackage(packageName)
                }
                startActivity(i)
            },
            onItemLongClick = { entry ->
                dialogBuilder?.showLongPressedHistoryLinkDialog(this@HistoryActivity, entry.url)
            },
            onItemDeleteClick = { entry, position ->
                compositeDisposable.add(
                    historyRepository.deleteHistoryEntry(entry.url)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe({
                            arrayAdapter.removeItemAt(position)
                            updateEmptyState()
                        }, { e ->
                            Log.e("HistoryActivity", "Error deleting entry", e)
                        })
                )
            }
        )
        list.adapter = arrayAdapter

        loadHistory()
    }

    private fun loadHistory() {
        compositeDisposable.add(
            historyRepository
                .lastHundredVisitedHistoryEntries()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ listEntries ->
                    historyList = listEntries
                    arrayAdapter.updateData(listEntries)
                    updateEmptyState()
                }, { e ->
                    Log.e("HistoryActivity", "Error loading history", e)
                })
        )
    }

    private fun updateEmptyState() {
        val isEmpty = arrayAdapter.itemCount == 0
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        list.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    fun dataChanged() {
        loadHistory()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.action_clear_history -> {
                AlertDialog.Builder(this)
                    .setTitle(R.string.title_clear_history)
                    .setMessage("¿Deseas borrar todo el historial de navegación?")
                    .setPositiveButton(R.string.action_delete) { _, _ ->
                        compositeDisposable.add(
                            historyRepository.deleteHistory()
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({
                                    dataChanged()
                                    Toast.makeText(this, R.string.message_clear_history, Toast.LENGTH_SHORT).show()
                                }, { e ->
                                    Log.e("HistoryActivity", "Error clearing history", e)
                                })
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        compositeDisposable.clear()
        arrayAdapter.cleanup()
    }

    class CustomAdapter(
        private var dataSet: List<HistoryEntry>,
        private val faviconModel: FaviconModel,
        private val onItemClick: (HistoryEntry) -> Unit,
        private val onItemLongClick: (HistoryEntry) -> Unit,
        private val onItemDeleteClick: (HistoryEntry, Int) -> Unit
    ) : RecyclerView.Adapter<CustomAdapter.ViewHolder>() {

        private var filtered: MutableList<HistoryEntry> = dataSet.toMutableList()
        private var oldList: MutableList<HistoryEntry> = dataSet.toMutableList()
        private val faviconFetchSubscriptions = HashMap<String, Disposable>()

        fun updateData(newList: List<HistoryEntry>) {
            dataSet = newList
            oldList = newList.toMutableList()
            filtered = newList.toMutableList()
            notifyDataSetChanged()
        }

        fun removeItemAt(position: Int) {
            if (position in 0 until dataSet.size) {
                val item = dataSet[position]
                val mutable = dataSet.toMutableList()
                mutable.removeAt(position)
                dataSet = mutable
                oldList.remove(item)
                filtered.remove(item)
                notifyItemRemoved(position)
            }
        }

        fun cleanup() {
            for (sub in faviconFetchSubscriptions.values) {
                sub.dispose()
            }
            faviconFetchSubscriptions.clear()
        }

        fun getFilter(): Filter {
            return object : Filter() {
                override fun performFiltering(charSequence: CharSequence): FilterResults {
                    val charString = charSequence.toString()
                    if (charString.isEmpty()) {
                        filtered = oldList
                    } else {
                        val filteredList = ArrayList<HistoryEntry>()
                        val queryLower = charString.toLowerCase(Locale.getDefault())
                        for (row in oldList) {
                            if (row.title.toLowerCase(Locale.getDefault()).contains(queryLower) ||
                                row.url.toLowerCase(Locale.getDefault()).contains(queryLower)
                            ) {
                                filteredList.add(row)
                            }
                        }
                        filtered = filteredList
                    }
                    val filterResults = FilterResults()
                    filterResults.values = filtered
                    return filterResults
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(charSequence: CharSequence?, filterResults: FilterResults) {
                    dataSet = (filterResults.values as? MutableList<HistoryEntry>) ?: ArrayList()
                    notifyDataSetChanged()
                }
            }
        }

        fun getItem(position: Int) = dataSet[position]

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val favicon: ImageView = view.findViewById(R.id.historyFavicon)
            val title: TextView = view.findViewById(R.id.historyTitle)
            val url: TextView = view.findViewById(R.id.historyUrl)
            val delete: ImageButton = view.findViewById(R.id.historyDelete)
        }

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(viewGroup.context)
                .inflate(R.layout.history_row, viewGroup, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = dataSet[position]
            val displayTitle = if (entry.title.isNotBlank()) entry.title.trim() else formatHost(entry.url)
            holder.title.text = displayTitle
            holder.url.text = formatSubtitle(entry.url, entry.lastTimeVisited)

            holder.favicon.tag = entry.url
            val defaultIcon = faviconModel.createDefaultBitmapForTitle(displayTitle)
            holder.favicon.setImageBitmap(defaultIcon)

            faviconFetchSubscriptions[entry.url]?.dispose()
            faviconFetchSubscriptions[entry.url] = faviconModel
                .faviconForUrl(entry.url, displayTitle)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ bitmap ->
                    if (holder.favicon.tag == entry.url) {
                        holder.favicon.setImageBitmap(bitmap)
                    }
                }, {
                    // Retain default letter avatar
                })

            holder.itemView.setOnClickListener {
                onItemClick(entry)
            }

            holder.itemView.setOnLongClickListener {
                onItemLongClick(entry)
                true
            }

            holder.delete.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION && currentPos < dataSet.size) {
                    onItemDeleteClick(dataSet[currentPos], currentPos)
                }
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            (holder.favicon.tag as? String)?.let { url ->
                faviconFetchSubscriptions.remove(url)?.dispose()
            }
        }

        override fun getItemCount() = dataSet.size

        private fun formatHost(url: String): String {
            return try {
                val host = Uri.parse(url).host?.removePrefix("www.")
                if (!host.isNullOrBlank()) host else url
            } catch (e: Exception) {
                url
            }
        }

        private fun formatSubtitle(url: String, timeMillis: Long): String {
            val host = try {
                val uri = Uri.parse(url)
                val h = uri.host?.removePrefix("www.") ?: ""
                val path = uri.path?.trim('/') ?: ""
                if (path.isNotEmpty() && path.length < 25 && !path.contains("/")) {
                    "$h/$path"
                } else if (h.isNotEmpty()) {
                    h
                } else {
                    url
                }
            } catch (e: Exception) {
                url
            }
            val timeFormatted = formatTime(timeMillis)
            return if (timeFormatted.isNotEmpty()) "$host · $timeFormatted" else host
        }

        private fun formatTime(timeMillis: Long): String {
            if (timeMillis <= 0) return ""
            val now = System.currentTimeMillis()
            val diff = now - timeMillis
            return try {
                when {
                    diff < 60 * 1000 -> "Ahora"
                    DateUtils.isToday(timeMillis) -> {
                        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                        "Hoy " + sdf.format(Date(timeMillis))
                    }
                    diff < 48 * 60 * 60 * 1000 && !DateUtils.isToday(timeMillis) -> {
                        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                        "Ayer " + sdf.format(Date(timeMillis))
                    }
                    else -> {
                        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
                        sdf.format(Date(timeMillis))
                    }
                }
            } catch (e: Exception) {
                ""
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history, menu)

        val searchItem: MenuItem? = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(this)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onQueryTextChange(query: String?): Boolean {
        arrayAdapter.getFilter().filter(query)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }
}