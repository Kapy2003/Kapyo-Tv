package com.famelack.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.famelack.tv.KapyoApp
import com.famelack.tv.R
import com.famelack.tv.data.model.Category
import com.famelack.tv.data.model.Channel
import com.famelack.tv.data.model.Country
import com.famelack.tv.data.repository.ChannelRepository
import com.famelack.tv.data.repository.ChannelState
import com.famelack.tv.data.storage.PreferencesManager
import com.famelack.tv.ui.browse.CategoryAdapter
import com.famelack.tv.ui.browse.ChannelAdapter
import com.famelack.tv.ui.player.PlayerEventListener
import com.famelack.tv.ui.player.TvPlayerManager
import com.famelack.tv.ui.settings.SettingsDialog
import com.famelack.tv.util.MemoryMonitor
import com.famelack.tv.util.OtaManager
import com.famelack.tv.util.UpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), PlayerEventListener {

    private lateinit var prefs: PreferencesManager
    private lateinit var repository: ChannelRepository
    private lateinit var playerManager: TvPlayerManager
    private lateinit var otaManager: OtaManager

    // Containers
    private lateinit var containerBrowse: View
    private lateinit var containerPlayer: View

    // Top Bar Views
    private lateinit var tvTopCountryBadge: TextView
    private lateinit var btnVoiceSearch: ImageButton
    private lateinit var etSearch: EditText
    private lateinit var btnSettings: Button

    // Browse Lists
    private lateinit var rvLanguages: RecyclerView
    private lateinit var rvCategories: RecyclerView
    private lateinit var rvChannels: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmptyState: TextView

    // Player Views
    private lateinit var surfaceView: SurfaceView
    private lateinit var pbPlayerBuffering: ProgressBar
    private lateinit var playerOsdBanner: View
    private lateinit var tvOsdChannelNum: TextView
    private lateinit var tvOsdChannelName: TextView
    private lateinit var tvOsdResolution: TextView
    private lateinit var tvOsdCategory: TextView
    private lateinit var playerErrorOverlay: View
    private lateinit var tvErrorTitle: TextView
    private lateinit var tvErrorDetails: TextView
    private lateinit var tvErrorPrompt: TextView

    // Adapters
    private lateinit var languageAdapter: CategoryAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    // In-memory Filter State (ONE country at a time)
    private var allChannels: List<Channel> = emptyList()
    private var filteredChannels: List<Channel> = emptyList()
    private var currentLanguageId: String = Category.ID_ALL
    private var currentCategoryId: String = Category.ID_ALL
    private var currentSearchQuery: String = ""

    // Player & OSD State
    private var isPlayingVideo = false
    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { playerOsdBanner.visibility = View.GONE }
    private val autoSkipRunnable = Runnable { jumpToNextChannel() }

    private val voiceSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                etSearch.setText(spokenText)
                etSearch.setSelection(spokenText.length)
                currentSearchQuery = spokenText.trim()
                applyFilter()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Instant 512MB RAM splash transition: switch from splash windowBackground to main TV theme
        setTheme(R.style.Theme_Kapyo)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = application as KapyoApp
        prefs = app.preferencesManager
        repository = app.channelRepository
        playerManager = TvPlayerManager(this, this)
        otaManager = OtaManager(this)

        initViews()
        setupAdapters()
        observeData()

        updateCountryBadge()

        // Load channels immediately from local cache or network
        lifecycleScope.launch {
            repository.loadChannels(prefs.selectedCountryCode)

            // Auto-detect country based on internet geo-location if not manually chosen
            if (!prefs.isCountryUserSelected) {
                val geoCountry = repository.detectGeoCountry(this@MainActivity)
                if (geoCountry != null && geoCountry.code != prefs.selectedCountryCode && !prefs.isCountryUserSelected) {
                    Log.i(TAG, "Detected internet geo-country: ${geoCountry.name} (${geoCountry.code})")
                    prefs.selectedCountryCode = geoCountry.code
                    prefs.selectedCountryName = geoCountry.name
                    updateCountryBadge()
                    repository.loadChannels(geoCountry.code, forceRefresh = false)
                }
            }
        }

        // Lightweight background OTA check (delayed so startup stays instant on 512MB RAM)
        lifecycleScope.launch {
            delay(5000)
            val update = otaManager.checkForUpdate()
            if (update != null && update.versionName != prefs.lastDismissedUpdateVersion) {
                showUpdateAvailableDialog(update)
            }
        }
    }

    private fun initViews() {
        containerBrowse = findViewById(R.id.container_browse)
        containerPlayer = findViewById(R.id.container_player)

        tvTopCountryBadge = findViewById(R.id.tv_top_country_badge)
        btnVoiceSearch = findViewById(R.id.btn_voice_search)
        etSearch = findViewById(R.id.et_search)
        btnSettings = findViewById(R.id.btn_settings)

        rvLanguages = findViewById(R.id.rv_languages)
        rvCategories = findViewById(R.id.rv_categories)
        rvChannels = findViewById(R.id.rv_channels)
        pbLoading = findViewById(R.id.pb_loading)
        tvEmptyState = findViewById(R.id.tv_empty_state)

        surfaceView = findViewById(R.id.player_surface_view)
        pbPlayerBuffering = findViewById(R.id.player_buffering)
        playerOsdBanner = findViewById(R.id.player_osd_banner)
        tvOsdChannelNum = findViewById(R.id.tv_osd_channel_num)
        tvOsdChannelName = findViewById(R.id.tv_osd_channel_name)
        tvOsdResolution = findViewById(R.id.tv_osd_resolution)
        tvOsdCategory = findViewById(R.id.tv_osd_category)
        playerErrorOverlay = findViewById(R.id.player_error_overlay)
        tvErrorTitle = findViewById(R.id.tv_error_title)
        tvErrorDetails = findViewById(R.id.tv_error_details)
        tvErrorPrompt = findViewById(R.id.tv_error_prompt)

        tvTopCountryBadge.setOnClickListener {
            showHomeCountryPicker()
        }

        btnVoiceSearch.setOnClickListener {
            startVoiceSearch()
        }

        btnSettings.setOnClickListener {
            openSettings()
        }

        // Cursor & remote navigation in search box
        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        hideKeyboard()
                        etSearch.clearFocus()
                        rvLanguages.requestFocus()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (etSearch.selectionStart == 0) {
                            hideKeyboard()
                            etSearch.clearFocus()
                            btnVoiceSearch.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val textLen = etSearch.text?.length ?: 0
                        if (etSearch.selectionStart >= textLen) {
                            hideKeyboard()
                            etSearch.clearFocus()
                            btnSettings.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        hideKeyboard()
                        etSearch.clearFocus()
                        rvChannels.requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint))
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Voice search is not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etSearch.windowToken, 0)
    }

    private fun setupAdapters() {
        // 1. Language Row Adapter
        languageAdapter = CategoryAdapter(R.drawable.bg_chip_language) { language ->
            currentLanguageId = language.id
            // Reset category selection when language changes
            currentCategoryId = Category.ID_ALL
            categoryAdapter.setSelectedCategory(Category.ID_ALL)
            updateCategoriesForCurrentLanguage()
            applyFilter()
        }
        rvLanguages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = languageAdapter
            setHasFixedSize(true)
        }

        // 2. Category / Genre Row Adapter (scoped to selected language)
        categoryAdapter = CategoryAdapter(R.drawable.bg_chip_category) { category ->
            currentCategoryId = category.id
            applyFilter()
        }
        rvCategories.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = categoryAdapter
            setHasFixedSize(true)
        }

        // 3. Channels Grid (4 columns)
        channelAdapter = ChannelAdapter(
            isFavoriteChecker = { channelId -> prefs.isFavorite(prefs.selectedCountryCode, channelId) },
            onChannelClick = { channel, index ->
                startPlayback(channel, index)
            },
            onChannelLongClick = { channel, _ ->
                val isFav = prefs.toggleFavorite(prefs.selectedCountryCode, channel.id)
                val msg = if (isFav) "${channel.name} added to Favorites" else "${channel.name} removed from Favorites"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                updateCategoriesForCurrentLanguage()
            }
        )
        rvChannels.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = channelAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(12)
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.channelState.collect { state ->
                    when (state) {
                        is ChannelState.Loading -> {
                            if (allChannels.isEmpty()) {
                                pbLoading.visibility = View.VISIBLE
                                tvEmptyState.visibility = View.GONE
                            }
                        }
                        is ChannelState.Success -> {
                            pbLoading.visibility = View.GONE
                            allChannels = state.channels
                            updateLanguagesAndCategories()
                            applyFilter()
                        }
                        is ChannelState.Error -> {
                            pbLoading.visibility = View.GONE
                            if (allChannels.isEmpty()) {
                                tvEmptyState.visibility = View.VISIBLE
                                tvEmptyState.text = state.message
                            } else {
                                Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateLanguagesAndCategories() {
        val languages = repository.computeLanguages(allChannels)
        languageAdapter.submitList(languages, currentLanguageId)
        updateCategoriesForCurrentLanguage()
    }

    private fun updateCategoriesForCurrentLanguage() {
        val categories = repository.computeCategories(allChannels, currentLanguageId, prefs.selectedCountryCode)
        categoryAdapter.submitList(categories, currentCategoryId)
    }

    private fun applyFilter() {
        var list = allChannels

        // Step 1: Language Filter
        if (currentLanguageId != Category.ID_ALL) {
            list = list.filter { it.language.equals(currentLanguageId, ignoreCase = true) }
        }

        // Step 2: Category / Genre Filter within that language
        when (currentCategoryId) {
            Category.ID_ALL -> { /* All in this language */ }
            Category.ID_FAVORITES -> {
                val favs = prefs.getFavorites(prefs.selectedCountryCode)
                list = list.filter { favs.contains(it.id) }
            }
            Category.ID_RECENT -> {
                val recents = prefs.getRecentChannelIds(prefs.selectedCountryCode)
                val orderMap = recents.withIndex().associate { it.value to it.index }
                list = list.filter { orderMap.containsKey(it.id) }
                    .sortedBy { orderMap[it.id] }
            }
            else -> {
                list = list.filter { it.category.equals(currentCategoryId, ignoreCase = true) }
            }
        }

        // Step 3: Search Query
        if (currentSearchQuery.isNotEmpty()) {
            val q = currentSearchQuery.lowercase()
            list = list.filter {
                it.name.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                (it.language != null && it.language.lowercase().contains(q))
            }
        }

        // Step 4: Deterministic, predictable sorting
        filteredChannels = if (currentCategoryId == Category.ID_RECENT) {
            list // Keep chronological recency order
        } else if (currentSearchQuery.isNotEmpty()) {
            val q = currentSearchQuery.lowercase()
            list.sortedWith(compareBy(
                { !it.name.lowercase().startsWith(q) }, // Prefix matches first
                { it.name.lowercase() }                 // Then alphabetically A-Z
            ))
        } else {
            list.sortedBy { it.name.lowercase() }       // Alphabetical A-Z across grid
        }

        channelAdapter.submitList(filteredChannels) {
            if (filteredChannels.isEmpty()) {
                tvEmptyState.visibility = View.VISIBLE
                tvEmptyState.text = if (currentCategoryId == Category.ID_FAVORITES) {
                    getString(R.string.empty_favorites)
                } else {
                    getString(R.string.no_channels_found)
                }
            } else {
                tvEmptyState.visibility = View.GONE
            }
        }
    }

    private fun updateCountryBadge() {
        val country = Country.findByCode(this, prefs.selectedCountryCode)
        tvTopCountryBadge.text = country.name
    }

    private fun showHomeCountryPicker() {
        val countries = Country.getCountryList(this)
        val items = countries.map { "${it.name} (${it.channelCount} channels)" }.toTypedArray()
        val currentIdx = countries.indexOfFirst { it.code.equals(prefs.selectedCountryCode, ignoreCase = true) }
            .coerceAtLeast(0)

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(R.string.change_country)
            .setSingleChoiceItems(items, currentIdx) { dialog, which ->
                val selected = countries[which]
                switchCountry(selected)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun switchCountry(country: Country) {
        prefs.isCountryUserSelected = true
        prefs.selectedCountryCode = country.code
        prefs.selectedCountryName = country.name
        updateCountryBadge()

        allChannels = emptyList()
        filteredChannels = emptyList()
        currentLanguageId = Category.ID_ALL
        currentCategoryId = Category.ID_ALL
        languageAdapter.setSelectedCategory(Category.ID_ALL)
        categoryAdapter.setSelectedCategory(Category.ID_ALL)
        channelAdapter.submitList(emptyList())

        lifecycleScope.launch {
            repository.loadChannels(country.code, forceRefresh = false)
        }
    }

    private fun openSettings() {
        val dialog = SettingsDialog(
            context = this,
            prefs = prefs,
            onCountryChanged = { newCountry ->
                switchCountry(newCountry)
            },
            onRefreshRequested = {
                lifecycleScope.launch {
                    repository.loadChannels(prefs.selectedCountryCode, forceRefresh = true)
                }
            },
            onCacheCleared = {
                repository.clearAllCaches()
                lifecycleScope.launch {
                    repository.loadChannels(prefs.selectedCountryCode, forceRefresh = true)
                }
            },
            onCheckUpdateRequested = {
                checkOtaUpdates(manual = true)
            }
        )
        dialog.show()
    }

    // ==================== Video Playback (Screen Kept On) ====================

    private fun startPlayback(channel: Channel, index: Int) {
        isPlayingVideo = true
        // Keep TV display on while channel is playing (never sleep/screensaver)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView.keepScreenOn = true

        containerBrowse.visibility = View.GONE
        containerPlayer.visibility = View.VISIBLE
        playerErrorOverlay.visibility = View.GONE

        hideKeyboard()

        playerManager.setPlaylist(filteredChannels)
        playerManager.initPlayer(surfaceView, prefs.maxResolution)
        playerManager.playChannelAtIndex(index)
    }

    private fun stopPlayback() {
        isPlayingVideo = false
        // Restore standard screen sleep timer when playback stops
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView.keepScreenOn = false

        osdHandler.removeCallbacksAndMessages(null)
        playerManager.release()
        containerPlayer.visibility = View.GONE
        containerBrowse.visibility = View.VISIBLE

        rvChannels.requestFocus()
    }

    private fun showOsdBanner() {
        playerOsdBanner.visibility = View.VISIBLE
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 3000)
    }

    private fun jumpToNextChannel() {
        osdHandler.removeCallbacks(autoSkipRunnable)
        playerErrorOverlay.visibility = View.GONE
        val next = playerManager.nextChannel()
        if (next == null) {
            Toast.makeText(this, "No more channels", Toast.LENGTH_SHORT).show()
        }
    }

    private fun jumpToPreviousChannel() {
        osdHandler.removeCallbacks(autoSkipRunnable)
        playerErrorOverlay.visibility = View.GONE
        val prev = playerManager.previousChannel()
        if (prev == null) {
            Toast.makeText(this, "No channels", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== PlayerEventListener ====================

    override fun onPlaybackStarted(channel: Channel, index: Int) {
        prefs.addRecentChannelId(prefs.selectedCountryCode, channel.id)
        playerErrorOverlay.visibility = View.GONE

        tvOsdChannelNum.text = "CH ${index + 1}/${filteredChannels.size}"
        tvOsdChannelName.text = channel.name
        val langSuffix = if (!channel.language.isNullOrEmpty()) " • ${channel.language}" else ""
        tvOsdCategory.text = "${channel.category}$langSuffix"
        tvOsdResolution.text = channel.resolution ?: "LIVE"

        showOsdBanner()
    }

    override fun onBuffering(isBuffering: Boolean) {
        pbPlayerBuffering.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    override fun onStreamError(errorMsg: String, canRetry: Boolean, retryAttempt: Int) {
        if (canRetry) {
            playerErrorOverlay.visibility = View.VISIBLE
            tvErrorTitle.text = getString(R.string.stream_unavailable)
            tvErrorDetails.text = "Attempting to reconnect ($retryAttempt/2)..."
            tvErrorPrompt.visibility = View.GONE
        }
    }

    override fun onStreamUnavailable(channel: Channel) {
        playerErrorOverlay.visibility = View.VISIBLE
        tvErrorTitle.text = getString(R.string.stream_unavailable)
        tvErrorDetails.text = "${channel.name} is currently offline or unreachable."
        tvErrorPrompt.visibility = View.VISIBLE
        tvErrorPrompt.text = getString(R.string.next_channel_prompt)

        osdHandler.removeCallbacks(autoSkipRunnable)
        osdHandler.postDelayed(autoSkipRunnable, 3500)
    }

    // ==================== TV Remote Key Handling ====================

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isPlayingVideo) {
            when (keyCode) {
                // Left & Right to switch channels
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    jumpToNextChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    jumpToPreviousChannel()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    jumpToNextChannel()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    jumpToPreviousChannel()
                    return true
                }
                // Up / Down shows OSD info banner
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showOsdBanner()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (playerErrorOverlay.visibility == View.VISIBLE) {
                        jumpToNextChannel()
                    } else {
                        showOsdBanner()
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    stopPlayback()
                    return true
                }
            }
        } else {
            // In Browse Mode
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (etSearch.hasFocus()) {
                    hideKeyboard()
                    etSearch.clearFocus()
                    if (currentSearchQuery.isNotEmpty()) {
                        etSearch.setText("")
                    }
                    rvChannels.requestFocus()
                    return true
                }

                // Show exit warning confirmation dialog
                showExitConfirmationDialog()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Exit Kapyo Tv?")
            .setMessage("Are you sure you want to exit the application?")
            .setPositiveButton("Exit") { _, _ ->
                finish()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                rvChannels.requestFocus()
            }
            .show()
    }

    // ==================== OTA Update Handling ====================

    private fun checkOtaUpdates(manual: Boolean) {
        if (manual) {
            Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val update = otaManager.checkForUpdate()
            if (update != null) {
                showUpdateAvailableDialog(update)
            } else if (manual) {
                AlertDialog.Builder(this@MainActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle("Kapyo Tv is Up to Date")
                    .setMessage("You are running the latest version (${otaManager.getCurrentVersion()}).")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showUpdateAvailableDialog(update: UpdateInfo) {
        val sizeMb = if (update.sizeBytes > 0) String.format("%.1f MB", update.sizeBytes / (1024.0 * 1024.0)) else ""
        val sizeText = if (sizeMb.isNotEmpty()) " (Size: $sizeMb)" else ""

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Update Available: v${update.versionName}$sizeText")
            .setMessage("A new version of Kapyo Tv is ready to install.\n\nChanges:\n${update.changelog}\n\nWould you like to download and update now?")
            .setPositiveButton("Update Now") { _, _ ->
                startOtaDownload(update)
            }
            .setNegativeButton("Later") { dialog, _ ->
                prefs.lastDismissedUpdateVersion = update.versionName
                dialog.dismiss()
            }
            .show()
    }

    private fun startOtaDownload(update: UpdateInfo) {
        val progressDialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Downloading Update")
            .setMessage("Downloading Kapyo Tv v${update.versionName}...\n0%")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val apk = otaManager.downloadApk(update) { percent ->
                progressDialog.setMessage("Downloading Kapyo Tv v${update.versionName}...\n$percent%")
            }
            progressDialog.dismiss()

            if (apk != null && apk.exists()) {
                try {
                    otaManager.installApk(apk)
                } catch (e: Exception) {
                    Log.e(TAG, "Install launch failed", e)
                    Toast.makeText(this@MainActivity, "Failed to launch package installer", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this@MainActivity, "Update download failed. Check network.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== Lifecycle ====================

    override fun onResume() {
        super.onResume()
        MemoryMonitor.logHeapStats("MainActivity onResume")
    }

    override fun onPause() {
        super.onPause()
        if (isPlayingVideo) {
            stopPlayback()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView.keepScreenOn = false
        MemoryMonitor.logHeapStats("MainActivity onPause")
    }

    override fun onStop() {
        super.onStop()
        if (isPlayingVideo) {
            stopPlayback()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView.keepScreenOn = false
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView.keepScreenOn = false
        osdHandler.removeCallbacksAndMessages(null)
        playerManager.release()
        MemoryMonitor.logHeapStats("MainActivity onDestroy")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MemoryMonitor.logHeapStats("MainActivity onLowMemory")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
