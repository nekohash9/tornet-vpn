package dev.nekohash.tornet

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.nekohash.tornet.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exitCountries: List<String> = emptyList()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startTor() }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                TorNetService.start(this)
            } else {
                binding.vpnSwitch.isChecked = false
                saveSettings()
                Toast.makeText(this, R.string.vpn_denied, Toast.LENGTH_LONG).show()
                TorNetService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        setupLanguage()

        val saved = RotationSettings.load(this)
        binding.minIntervalInput.setText(saved.minInterval.toString())
        binding.maxIntervalInput.setText(saved.maxInterval.toString())
        binding.countInput.setText(saved.count.toString())
        binding.vpnSwitch.isChecked = saved.vpn
        binding.rotateSwitch.isChecked = saved.rotate
        renderRotationInputs()
        binding.rotateSwitch.setOnCheckedChangeListener { _, _ ->
            renderRotationInputs()
            binding.applyButton.isEnabled = true
        }
        binding.appsButton.setOnClickListener { startActivity(Intent(this, AppsActivity::class.java)) }
        binding.vpnSettingsButton.setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) }
        }
        exitCountries = ExitCountries.parse(saved.exitCountries)
        renderExitButton()
        binding.exitButton.setOnClickListener { pickExitCountries() }
        binding.bridgeGroup.check(radioFor(saved.bridgeMode))
        binding.customBridgesInput.setText(saved.customBridges)
        binding.bridgeGroup.setOnCheckedChangeListener { _, _ -> binding.applyButton.isEnabled = true }
        binding.customBridgesInput.doAfterTextChanged { binding.applyButton.isEnabled = true }
        binding.fetchBridgesButton.setOnClickListener { fetchBridges() }
        binding.bridgesBotButton.setOnClickListener {
            // Telegram обычно доступен, а бот выдаёт свежие obfs4/webtunnel-мосты.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/GetBridgesBot"))) }
        }
        binding.vpnSwitch.setOnCheckedChangeListener { _, _ -> binding.applyButton.isEnabled = true }

        binding.lanSwitch.isChecked = saved.shareLan
        binding.lanSwitch.setOnCheckedChangeListener { _, checked ->
            // Применяется сразу, без «Применить».
            RotationSettings.save(this, RotationSettings.load(this).copy(shareLan = checked))
            if (TorNetStore.state.value.status == TorStatus.ON) {
                TorNetService.send(this, TorNetService.ACTION_SYNC_LAN)
            }
        }
        binding.telegramButton.setOnClickListener { openTelegram() }
        binding.lanShareButton.setOnClickListener { shareLanLink() }
        binding.lanText.setOnClickListener {
            lanAddress()?.let { copyText("${it.first}:${it.second}") }
        }

        binding.startStopButton.setOnClickListener {
            if (TorNetStore.state.value.status == TorStatus.OFF) requestPermissionAndStart()
            else TorNetService.send(this, TorNetService.ACTION_STOP)
        }
        binding.newIpButton.setOnClickListener { TorNetService.send(this, TorNetService.ACTION_NEW_IP) }
        binding.applyButton.setOnClickListener {
            startTor()
            binding.applyButton.isEnabled = false
        }
        listOf(binding.minIntervalInput, binding.maxIntervalInput, binding.countInput).forEach { input ->
            input.doAfterTextChanged { binding.applyButton.isEnabled = true }
        }

        binding.socksText.setOnClickListener { copyProxy("socks5") }
        binding.httpText.setOnClickListener { copyProxy("http") }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TorNetStore.state.collect { render(it) } }
                launch {
                    // Обратный отсчёт до следующей смены.
                    while (isActive) {
                        renderCountdown(TorNetStore.state.value)
                        delay(1000)
                    }
                }
            }
        }
    }

    /** Выбор стран выхода: список популярных стран + уже выбранные. */
    private fun pickExitCountries() {
        val codes = (exitCountries + ExitCountries.COMMON).distinct()
        val labels = codes.map { ExitCountries.label(it) }.toTypedArray()
        val checked = codes.map { it in exitCountries }.toBooleanArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_pick_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                exitCountries = codes.filterIndexed { i, _ -> checked[i] }
                onExitChanged()
            }
            .setNeutralButton(R.string.exit_any) { _, _ ->
                exitCountries = emptyList()
                onExitChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onExitChanged() {
        renderExitButton()
        binding.applyButton.isEnabled = true
        // Если Tor уже работает, применяем сразу.
        if (TorNetStore.state.value.status != TorStatus.OFF) startTor()
    }

    private fun renderExitButton() {
        binding.exitButton.text = if (exitCountries.isEmpty()) getString(R.string.exit_any)
        else exitCountries.joinToString(", ") { ExitCountries.label(it) }
    }

    /** Переключатель языка: Системный / English / Русский. Экран пересоздаётся сам. */
    private fun setupLanguage() {
        val buttons = mapOf(
            L10n.SYSTEM to R.id.langSystem,
            "en" to R.id.langEn,
            "ru" to R.id.langRu,
        )
        binding.languageGroup.check(buttons[L10n.current()] ?: R.id.langSystem)
        binding.languageGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val language = buttons.entries.first { it.value == id }.key
            if (language != L10n.current()) L10n.set(language)
        }
    }

    private fun requestPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Без разрешения сервис всё равно работает, просто уведомление будет скрыто.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startTor()
        }
    }

    private fun startTor() {
        saveSettings()
        if (binding.vpnSwitch.isChecked) {
            // Системный диалог «Разрешить подключение к VPN?» — только при первом включении.
            val consent = VpnService.prepare(this)
            if (consent != null) {
                vpnPermission.launch(consent)
                return
            }
        }
        TorNetService.start(this)
    }

    override fun onResume() {
        super.onResume()
        binding.appsButton.text = getString(R.string.apps_button, AppsActivity.summary(AppRules.load(this)))
    }

    /** Поля интервала и количества имеют смысл только при включённой автосмене. */
    private fun renderRotationInputs() {
        val on = binding.rotateSwitch.isChecked
        listOf(binding.minIntervalInput, binding.maxIntervalInput, binding.countInput).forEach { it.isEnabled = on }
    }

    private fun saveSettings() {
        fun read(text: CharSequence?, default: Int) = text?.toString()?.toIntOrNull() ?: default
        val s = RotationSettings(
            binding.rotateSwitch.isChecked,
            read(binding.minIntervalInput.text, 60),
            read(binding.maxIntervalInput.text, 60),
            read(binding.countInput.text, 0),
            binding.vpnSwitch.isChecked,
            shareLan = binding.lanSwitch.isChecked,
            bridgeMode = modeFor(binding.bridgeGroup.checkedRadioButtonId),
            customBridges = binding.customBridgesInput.text?.toString().orEmpty(),
            exitCountries = exitCountries.joinToString(","),
        ).normalized()
        RotationSettings.save(this, s)
        binding.minIntervalInput.setText(s.minInterval.toString())
        binding.maxIntervalInput.setText(s.maxInterval.toString())
        binding.countInput.setText(s.count.toString())
    }

    private fun render(state: TorNetState) = with(binding) {
        val via = if (state.transport.isNotEmpty()) " · ${state.transport}" else ""
        statusText.text = when (state.status) {
            TorStatus.OFF -> getString(R.string.status_off)
            TorStatus.STARTING -> getString(R.string.status_starting, state.bootstrap, state.bootstrapSummary) + via
            TorStatus.ON -> getString(R.string.status_on) + via
            TorStatus.STOPPING -> getString(R.string.status_stopping)
        }
        bootstrapProgress.isVisible = state.status == TorStatus.STARTING
        bootstrapProgress.setProgressCompat(state.bootstrap, true)

        ipText.text = state.currentIp ?: getString(R.string.dash)
        countryText.isVisible = state.exitCountry != null
        countryText.text = state.exitCountry?.let { ExitCountries.label(it) }.orEmpty()
        changesText.text = if (state.status == TorStatus.ON) {
            if (state.maxChanges > 0) getString(R.string.changes_of, state.changes, state.maxChanges)
            else getString(R.string.changes_inf, state.changes)
        } else ""

        startStopButton.text = getString(
            if (state.status == TorStatus.OFF) R.string.start else R.string.stop,
        )
        startStopButton.isEnabled = state.status != TorStatus.STOPPING
        newIpButton.isEnabled = state.status == TorStatus.ON
        applyButton.isVisible = state.status == TorStatus.ON
        telegramButton.isEnabled = state.status == TorStatus.ON
        val lan = lanAddress(state)
        lanText.isVisible = state.lanPort > 0
        lanShareButton.isVisible = lan != null
        lanText.text = if (lan != null) getString(R.string.lan_address, lan.first, lan.second)
        else getString(R.string.lan_no_network, state.lanPort)
        vpnText.isVisible = state.vpnActive

        val socks = state.socksPort.takeIf { it > 0 }?.toString() ?: "9050"
        val http = state.httpPort.takeIf { it > 0 }?.toString() ?: "8118"
        socksText.text = getString(R.string.socks_line, socks)
        httpText.text = getString(R.string.http_line, http)

        logText.text = state.log.asReversed().joinToString("\n")
        renderCountdown(state)
    }

    /** Открывает в Telegram диалог «Подключить прокси» с нашим SOCKS5. */
    private fun openTelegram() {
        val port = TorNetStore.state.value.socksPort
        if (TorNetStore.state.value.status != TorStatus.ON || port <= 0) {
            Toast.makeText(this, R.string.tor_not_running, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("tg://socks?server=127.0.0.1&port=$port")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.tg_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLanLink() {
        val (ip, port) = lanAddress() ?: return
        val link = "https://t.me/socks?server=$ip&port=$port"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        startActivity(Intent.createChooser(send, getString(R.string.lan_send_link)))
    }

    private fun lanAddress(state: TorNetState = TorNetStore.state.value): Pair<String, Int>? {
        if (state.lanPort <= 0) return null
        return Network.lanIpv4()?.let { it to state.lanPort }
    }

    private fun copyText(value: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("proxy", value))
        Toast.makeText(this, getString(R.string.copied, value), Toast.LENGTH_SHORT).show()
    }

    private fun fetchBridges() {
        binding.fetchBridgesButton.isEnabled = false
        binding.fetchBridgesButton.setText(R.string.fetching_bridges)
        lifecycleScope.launch {
            // Если Tor уже подключён — запрос идёт через него и проходит при любых блокировках.
            val state = TorNetStore.state.value
            val port = if (state.status == TorStatus.ON) state.httpPort else 0
            val result = withContext(Dispatchers.IO) { runCatching { Moat.fetchBridges(this@MainActivity, port) } }
            binding.fetchBridgesButton.isEnabled = true
            binding.fetchBridgesButton.setText(R.string.fetch_bridges)
            result.onSuccess { bridges ->
                if (bridges.isNotEmpty()) {
                    binding.customBridgesInput.setText(bridges.joinToString("\n") { it.raw })
                    if (binding.bridgeGroup.checkedRadioButtonId != R.id.bridgeAuto) {
                        binding.bridgeGroup.check(R.id.bridgeCustom)
                    }
                }
                Toast.makeText(this@MainActivity, getString(R.string.fetched_bridges, bridges.size), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, getString(R.string.fetch_failed_hint), Toast.LENGTH_LONG).show()
                TorNetStore.log(getString(R.string.fetch_failed, it.message))
            }
        }
    }

    private fun radioFor(mode: BridgeMode) = when (mode) {
        BridgeMode.DIRECT -> R.id.bridgeDirect
        BridgeMode.OBFS4 -> R.id.bridgeObfs4
        BridgeMode.SNOWFLAKE -> R.id.bridgeSnowflake
        BridgeMode.DNSTT -> R.id.bridgeDnstt
        BridgeMode.MEEK -> R.id.bridgeMeek
        BridgeMode.CUSTOM -> R.id.bridgeCustom
        else -> R.id.bridgeAuto
    }

    private fun modeFor(radioId: Int) = when (radioId) {
        R.id.bridgeDirect -> BridgeMode.DIRECT
        R.id.bridgeObfs4 -> BridgeMode.OBFS4
        R.id.bridgeSnowflake -> BridgeMode.SNOWFLAKE
        R.id.bridgeDnstt -> BridgeMode.DNSTT
        R.id.bridgeMeek -> BridgeMode.MEEK
        R.id.bridgeCustom -> BridgeMode.CUSTOM
        else -> BridgeMode.AUTO
    }

    private fun renderCountdown(state: TorNetState) {
        val left = (state.nextChangeAt - System.currentTimeMillis()) / 1000
        binding.nextChangeText.text =
            if (state.status == TorStatus.ON && state.nextChangeAt > 0) getString(R.string.next_change, left.coerceAtLeast(0))
            else ""
    }

    private fun copyProxy(kind: String) {
        val state = TorNetStore.state.value
        if (state.status != TorStatus.ON) return
        val port = if (kind == "socks5") state.socksPort else state.httpPort
        val value = "$kind://127.0.0.1:$port"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("proxy", value))
        Toast.makeText(this, getString(R.string.copied, value), Toast.LENGTH_SHORT).show()
    }
}
