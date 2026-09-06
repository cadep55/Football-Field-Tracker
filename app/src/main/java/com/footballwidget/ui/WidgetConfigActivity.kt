package com.footballwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.footballwidget.R
import com.footballwidget.data.*
import com.footballwidget.widget.FootballWidgetProvider
import java.util.concurrent.Executors

class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_config)

        findViewById<TextView>(R.id.test_mode_button).setOnClickListener {
            selectTeam(WidgetConfig(
                teamId = "21",
                teamAbbreviation = "TEST",
                league = "nfl",
                teamDisplayName = "Super Bowl LIX (Test)",
                testMode = true,
                testGameId = EspnApi.TEST_GAME_ID
            ))
        }

        val recyclerView = findViewById<RecyclerView>(R.id.teams_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        val searchBox = findViewById<EditText>(R.id.search_box)
        val loadingText = findViewById<TextView>(R.id.loading_text)

        executor.execute {
            val nflTeams = EspnApi.fetchNflTeams()
            val cfbTeams = EspnApi.fetchCfbTeams()

            val allItems = mutableListOf<ListItem>()

            if (nflTeams.isNotEmpty()) {
                allItems.add(ListItem.Header("NFL"))
                nflTeams.forEach { allItems.add(ListItem.Team(it, "nfl")) }
            }
            if (cfbTeams.isNotEmpty()) {
                allItems.add(ListItem.Header("COLLEGE FOOTBALL"))
                cfbTeams.forEach { allItems.add(ListItem.Team(it, "college-football")) }
            }

            runOnUiThread {
                loadingText.visibility = View.GONE

                if (allItems.isEmpty()) {
                    loadingText.text = "Could not load teams. Check your internet connection and try again."
                    loadingText.visibility = View.VISIBLE
                    return@runOnUiThread
                }

                val adapter = TeamAdapter(allItems) { team, league ->
                    selectTeam(WidgetConfig(
                        teamId = team.id,
                        teamAbbreviation = team.abbreviation,
                        league = league,
                        teamDisplayName = team.displayName
                    ))
                }
                recyclerView.adapter = adapter

                searchBox.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val query = s.toString().trim().lowercase()
                        if (query.isEmpty()) {
                            adapter.updateList(allItems)
                        } else {
                            val filtered = allItems.filter { item ->
                                when (item) {
                                    is ListItem.Team -> {
                                        item.info.displayName.lowercase().contains(query) ||
                                        item.info.abbreviation.lowercase().contains(query)
                                    }
                                    is ListItem.Header -> false
                                }
                            }
                            adapter.updateList(filtered)
                        }
                    }
                })
            }
        }
    }

    private fun selectTeam(config: WidgetConfig) {
        WidgetPrefs.saveConfig(this, widgetId, config)
        FootballWidgetProvider.updateWidget(this, widgetId)
        LiveUpdateService.scheduleCheck(this)

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }

    sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class Team(val info: TeamInfo, val league: String) : ListItem()
    }

    private class TeamAdapter(
        private var items: List<ListItem>,
        private val onSelect: (TeamInfo, String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_TEAM = 1
        }

        fun updateList(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.Header -> TYPE_HEADER
                is ListItem.Team -> TYPE_TEAM
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 24; bottomMargin = 8 }
                    setPadding(16, 0, 16, 0)
                    textSize = 12f
                    setTextColor(Color.parseColor("#E74C3C"))
                    letterSpacing = 0.1f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                HeaderViewHolder(tv)
            } else {
                val tv = TextView(parent.context).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 2 }
                    setPadding(42, 32, 42, 32)
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#1C1C30"))
                }
                TeamViewHolder(tv)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> {
                    (holder as HeaderViewHolder).text.text = item.title
                }
                is ListItem.Team -> {
                    val vh = holder as TeamViewHolder
                    vh.text.text = "${item.info.displayName} (${item.info.abbreviation})"
                    vh.text.setOnClickListener { onSelect(item.info, item.league) }
                }
            }
        }

        override fun getItemCount() = items.size

        class HeaderViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
        class TeamViewHolder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}
