package dev.nekohash.tornet

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.nekohash.tornet.databinding.ActivityAppsBinding
import dev.nekohash.tornet.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздельное туннелирование: какие приложения идут через Tor. */
class AppsActivity : AppCompatActivity() {

    private data class AppItem(val packageName: String, val label: String, val icon: Drawable?)

    private lateinit var binding: ActivityAppsBinding
    private var mode = AppsMode.ALL
    private val selected = mutableSetOf<String>()
    private var apps: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        val rules = AppRules.load(this)
        mode = rules.mode
        selected += rules.packages
        binding.modeGroup.check(
            when (mode) {
                AppsMode.ALL -> R.id.modeAll
                AppsMode.ONLY -> R.id.modeOnly
                AppsMode.EXCEPT -> R.id.modeExcept
            },
        )
        binding.modeGroup.setOnCheckedChangeListener { _, id ->
            mode = when (id) {
                R.id.modeOnly -> AppsMode.ONLY
                R.id.modeExcept -> AppsMode.EXCEPT
                else -> AppsMode.ALL
            }
            save()
            render()
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.searchInput.doAfterTextChanged { filter() }
        render()
        loadApps()
    }

    override fun onPause() {
        super.onPause()
        // Если VPN уже работает — пересоздаём интерфейс под новые правила.
        if (changed && TorNetStore.state.value.vpnActive) {
            TorNetService.send(this, TorNetService.ACTION_SYNC_VPN)
        }
        changed = false
    }

    private fun loadApps() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val pm = packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    .map { AppItem(it.packageName, it.loadLabel(pm).toString(), runCatching { it.loadIcon(pm) }.getOrNull()) }
            }
            // Удалённые приложения больше не храним в правилах.
            val installed = loaded.map { it.packageName }.toSet()
            if (selected.retainAll(installed)) save()
            apps = loaded.sortedWith(compareBy<AppItem>({ it.packageName !in selected }, { it.label.lowercase() }))
            binding.loading.isVisible = false
            filter()
        }
    }

    private fun filter() {
        val query = binding.searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        shown = if (query.isEmpty()) apps
        else apps.filter { query in it.label.lowercase() || query in it.packageName.lowercase() }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
    }

    private fun render() {
        binding.hintText.text = when {
            mode == AppsMode.ALL -> getString(R.string.apps_hint_all)
            mode == AppsMode.ONLY && selected.isEmpty() -> getString(R.string.apps_hint_only_empty)
            mode == AppsMode.ONLY -> getString(R.string.apps_hint_only, selected.size)
            else -> getString(R.string.apps_hint_except, selected.size)
        }
        // В режиме «Все» список ни на что не влияет — приглушаем его.
        binding.list.alpha = if (mode == AppsMode.ALL) 0.4f else 1f
        binding.searchLayout.alpha = binding.list.alpha
    }

    private fun save() {
        AppRules.save(this, AppRules(mode, selected.toSet()))
        changed = true
    }

    private val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val item = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return object : RecyclerView.ViewHolder(item.root) {}.also { it.itemView.tag = item }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = holder.itemView.tag as ItemAppBinding
            val app = shown[position]
            item.icon.setImageDrawable(app.icon)
            item.label.text = app.label
            item.packageName.text = app.packageName
            item.check.isChecked = app.packageName in selected
            item.root.setOnClickListener {
                if (mode == AppsMode.ALL) {
                    // Выбор приложения сам переключает в режим «Только выбранные».
                    binding.modeGroup.check(R.id.modeOnly)
                }
                if (!selected.add(app.packageName)) selected.remove(app.packageName)
                item.check.isChecked = app.packageName in selected
                save()
                render()
            }
        }
    }

    companion object {
        /** Краткое описание правил для главного экрана. */
        fun summary(rules: AppRules): String = when (rules.effectiveMode) {
            AppsMode.ALL -> L10n.s(R.string.apps_summary_all)
            AppsMode.ONLY -> L10n.s(R.string.apps_summary_only, rules.packages.size)
            AppsMode.EXCEPT -> L10n.s(R.string.apps_summary_except, rules.packages.size)
        }
    }
}
