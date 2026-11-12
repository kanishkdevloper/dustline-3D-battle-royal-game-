package com.dustline.game

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Flow (same order as setFlow() in the HTML):
 * initialLoading -> login (CREATE YOUR ID, first run only) -> lobby <-> customize(character)
 * lobby START -> preMatchLoading -> (match engine: phase 2)
 */
class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var loading: LoadingView
    private lateinit var lobby: LobbyView
    private lateinit var charSel: CharacterSelectView
    private var idScreen: IdScreenView? = null
    private var account: Account? = null
    private lateinit var profile: Profile
    private var preloader: AssetPreloader? = null
    private var booted = false
    private var game: GameScreen? = null
    private var mapData: MapData? = null
    private var pendingMsg: String? = null
    private lateinit var storeView: StoreView
    private lateinit var settingsView: SettingsView
    private lateinit var hudEdit: HudEditView
    private lateinit var profileView: ProfileView
    private lateinit var resultView: ResultView
    private lateinit var exchangeView: ExchangeView     // tap on the coin counter: COINS -> DIAMONDS (200 coins = 1 diamond)
    private lateinit var levelUpView: LevelUpView       // LEVEL UP animation + coin reward, one per level gained
    private val pendingLevelUps = ArrayList<LevelUpView.Step>()
    private var sfx: SoundFx? = null
    private lateinit var avatar: LobbyAvatarView
    private lateinit var ads: AdManager        // lobby WATCH VIDEO (+5 diamonds): the 4 diamond ad units, used in turn (round-robin)
    private lateinit var coinAds: AdManager    // result screen WATCH VIDEO (2X COINS): its own ad unit(s), NOT the diamond units
    private lateinit var levelAds: AdManager   // LEVEL UP "2X" button: its own AdManager (own cooldown), real 2x-coin units
    private var adReward = 0
    /** system photo picker (no storage permission): the picture for the profile DP. Diamonds are taken only after it was saved. */
    private val dpPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> onDpPicked(uri) }
    private lateinit var matchAd: MatchAdManager   // ONE automatic full-screen interstitial per match, shown at the START of the 30 s PRE-MATCH warm-up (never inside the real match)
    private var matchAdShown = false
    private lateinit var bannerAds: BannerAdManager   // 3 banner ads per match (at 2:00 / 4:00 / 6:00 of real match time), only inside the match
    private val bannerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bannerTick = object : Runnable { override fun run() { bannerAds.tick(game); bannerHandler.postDelayed(this, 1000L) } }
    private var mapLoadFailed = false   // match-map loading showed an error: BACK must lead to the lobby, not only "press again to exit"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        setContentView(root)

        account = Store.load(this)
        Prefs.load(this)
        sfx = SoundFx(this)
        profile = Store.loadProfile(this)
        DpStore.load(this)
        ads = AdManager(this, AdManager.DIAMOND_UNIT_IDS, "diamonds")
        coinAds = AdManager(this, AdManager.COIN2X_UNIT_IDS, "coins2x")
        levelAds = AdManager(this, AdManager.LEVELUP_UNIT_IDS, "levelup2x")
        matchAd = MatchAdManager(this)
        bannerAds = BannerAdManager(this, root)
        bannerHandler.postDelayed(bannerTick, 1000L)

        lobby = LobbyView(this).apply {
            visibility = View.GONE
            onStart = { startMatchLoading() }
            onStore = { openStore(false) }
            onVault = { openStore(true) }
            onSettings = { openSettings(false) }
            onPlus = { setMessage("NO PURCHASES IN THIS GAME - WATCH A VIDEO FOR FREE DIAMONDS") }
            onWatchAd = { watchAd() }
            adReady = { ads.isReady() && game == null }
            onProfile = { openProfile() }
            onCoin = { openExchange() }
            onCharacter = { showCharacters() }
        }
        charSel = CharacterSelectView(this).apply {
            visibility = View.GONE
            onBack = { showLobby() }
            onPick = { ch -> profile.char = ch.id; Store.saveProfile(this@MainActivity, profile); this.profile = profile; invalidate() }
            onHair = { Store.saveProfile(this@MainActivity, profile); invalidate() }
        }
        storeView = StoreView(this).apply {
            visibility = View.GONE
            onClose = { visibility = View.GONE; showLobby() }
            onChanged = { account?.let { Store.save(this@MainActivity, it) }; Store.saveProfile(this@MainActivity, profile); avatar.charId = profile.char; invalidate() }
            onToast = { m -> lobby.setMessage(m) }
            onPreview = { id -> avatar.previewWear = id }
            onPreviewPants = { id -> avatar.previewPants = id }
            onRotate = { dx -> avatar.rotateBy(dx) }
            onCoin = { openExchange() }
            onSound = { s -> sfx?.play(s) }
        }
        settingsView = SettingsView(this).apply {
            visibility = View.GONE
            onClose = { closeSettings() }
            onSoundTest = { sfx?.play("pickup") }
            onEditHud = { settingsView.visibility = View.GONE; hudEdit.bringToFront(); hudEdit.visibility = View.VISIBLE; hudEdit.invalidate() }
            onAccountChanged = { account?.let { Store.save(this@MainActivity, it) }; invalidate() }
        }
        hudEdit = HudEditView(this).apply {
            visibility = View.GONE
            onClose = { visibility = View.GONE; Prefs.save(this@MainActivity); settingsView.visibility = View.VISIBLE; settingsView.invalidate() }
        }
        profileView = ProfileView(this).apply {
            visibility = View.GONE
            onClose = { visibility = View.GONE; showLobby() }
            onEditName = { editName() }
            onEditDp = { editDp() }
            onSaved = { account?.let { Store.save(this@MainActivity, it) } }
            onSettings = { openSettings(false) }
            onRotate = { dx -> avatar.rotateBy(dx) }
        }
        resultView = ResultView(this).apply {
            visibility = View.GONE; onBack = { leaveResult() }
            onWatchAd = { watchResultAd() }
            adReady = { coinAds.isReady() && visibility == View.VISIBLE && !resultLeaving }
        }
        exchangeView = ExchangeView(this).apply {
            visibility = View.GONE
            onClose = { close() }
            onSound = { s -> sfx?.play(s) }
            onChanged = { account?.let { Store.save(this@MainActivity, it) }; lobby.account = account; lobby.invalidate(); storeView.invalidate() }
        }
        levelUpView = LevelUpView(this).apply {
            visibility = View.GONE; onSound = { s -> sfx?.play(s) }
            onDouble = { st -> watchLevelAd(st) }
            adReady = { levelAds.isReady() && levelUpView.isPlaying }
        }
        loading = LoadingView(this).apply { onRetry = { beginBoot() } }
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        avatar = LobbyAvatarView(this).apply { visibility = View.GONE; charId = profile.char }
        root.addView(avatar, mp, mp)
        root.addView(lobby, mp, mp)
        root.addView(charSel, mp, mp)
        root.addView(storeView, mp, mp)
        root.addView(settingsView, mp, mp)
        root.addView(hudEdit, mp, mp)
        root.addView(profileView, mp, mp)
        root.addView(resultView, mp, mp)
        root.addView(exchangeView, mp, mp)
        root.addView(levelUpView, mp, mp)
        root.addView(loading, mp, mp)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { handleBack() }
        })
        beginBoot()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val c = WindowInsetsControllerCompat(window, window.decorView)
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun beginBoot() {
        loading.clearError(); loading.setIndeterminate(); loading.visibility = View.VISIBLE
        preloader?.cancel()
        preloader = AssetPreloader(this).also {
            it.start(object : AssetPreloader.Listener {
                override fun onProgress(p: Float) = loading.setProgress(p)
                override fun onDone() { booted = true; loading.postDelayed({ afterBoot() }, 500) }
                override fun onError(msg: String) = loading.showError(msg)
            })
        }
    }

    private fun afterBoot() {
        loading.visibility = View.GONE
        if (account == null) showIdScreen() else showLobby()
        // UMP consent form first (EEA / UK / CH), then AdMob init + preload of the rewarded videos - ads never start before consent is settled
        ConsentManager.gather(this) { ads.start(); coinAds.start(); levelAds.start(); matchAd.start(); bannerAds.start() }
    }

    /** Lobby WATCH VIDEO: full-screen rewarded ad. Reward = AdManager.REWARD_DIAMONDS (5) diamonds, saved at once. Closing the ad returns to the lobby - nothing restarts. */
    private fun watchAd() {
        if (game != null || lobby.visibility != View.VISIBLE || account == null) return
        adReward = 0
        ads.show(object : AdManager.Listener {
            override fun onReward(diamonds: Int) {
                val a = account ?: return
                a.diamonds += diamonds; Store.save(this@MainActivity, a)
                adReward = diamonds
            }
            override fun onClosed(rewarded: Boolean) {
                lobby.account = account; lobby.invalidate()
                if (rewarded && adReward > 0) lobby.setMessage("+$adReward FREE DIAMOND" + (if (adReward > 1) "S" else ""))
                else lobby.setMessage("WATCH THE FULL VIDEO TO GET DIAMONDS")
                adReward = 0
            }
            override fun onMessage(text: String) { lobby.setMessage(text) }
        })
    }

    /**
     * Result screen WATCH VIDEO (2X COINS): rewarded ad; when it is watched to the end the coins of THIS match are paid a second
     * time (total = 2x), saved at once and the last history entry is updated. Only once per match, never for a closed / failed ad.
     */
    private fun watchResultAd() {
        if (resultView.visibility != View.VISIBLE || resultView.doubled || resultLeaving) return
        if (resultView.matchCoins() <= 0) { resultView.setNote("NO COINS THIS MATCH TO DOUBLE"); return }   // button is always visible now -> never play an ad that pays nothing
        var paid = false
        coinAds.show(object : AdManager.Listener {
            override fun onReward(diamonds: Int) {
                if (paid || resultView.doubled) return
                val a = account ?: return
                val extra = resultView.matchCoins()
                if (extra <= 0) return
                paid = true
                a.coins += extra.toLong()
                if (a.history.isNotEmpty()) { val h = a.history[0]; a.history[0] = Hist(h.d, h.p, h.k, h.t, h.xp, h.c + extra, h.w, h.q) }
                Store.save(this@MainActivity, a)
                resultView.markDoubled()
            }
            override fun onClosed(rewarded: Boolean) {
                if (!rewarded && !resultView.doubled) resultView.setNote("WATCH THE FULL VIDEO TO DOUBLE COINS")
            }
            override fun onMessage(text: String) { resultView.setNote(text) }
        })
    }

    /**
     * LEVEL UP screen "2X": rewarded ad; ONLY when the video was watched to the end the coins of THIS level are paid a second time
     * (total = 2x), saved at once. Once per level. Closing the ad early / a failed ad pays nothing and the buttons stay.
     */
    private fun watchLevelAd(st: LevelUpView.Step) {
        if (!levelUpView.isPlaying || st.doubled) return
        var paid = false
        levelAds.show(object : AdManager.Listener {
            override fun onReward(diamonds: Int) {
                if (paid || st.doubled) return
                val a = account ?: return
                paid = true
                a.coins += st.coins.toLong()
                Store.save(this@MainActivity, a)
                lobby.account = a; lobby.invalidate()
                levelUpView.markDoubled(st)
            }
            override fun onClosed(rewarded: Boolean) {
                if (!rewarded && !st.doubled) levelUpView.setNote("WATCH THE FULL VIDEO TO GET 2X")
            }
            override fun onMessage(text: String) { levelUpView.setNote(text) }
        })
    }

    private fun showIdScreen() {
        val v = IdScreenView(this)
        idScreen = v
        v.onConfirm = { name ->
            account = Store.create(this, name)
            root.removeView(v); idScreen = null
            showLobby()
        }
        root.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        v.focusInput()
    }

    private fun showLobby() {
        charSel.visibility = View.GONE; storeView.visibility = View.GONE
        lobby.account = account; lobby.profile = profile
        avatar.panel = false; avatar.panelLeft = false; avatar.usePreview = false; avatar.fixedSpin = null
        avatar.charId = profile.char; avatar.visibility = View.VISIBLE; avatar.onResume()
        lobby.visibility = View.VISIBLE; lobby.invalidate()
    }

    private fun hideAvatar() { avatar.visibility = View.GONE; avatar.onPause() }

    private fun openStore(vault: Boolean) {
        lobby.visibility = View.GONE
        // 3D preview of the top on your character (right panel of the store, left panel of the vault)
        avatar.panel = true; avatar.panelLeft = vault; avatar.panelFrac = if (vault) StoreView.VAULT_AV else 1f - StoreView.AVATAR_X; avatar.usePreview = true; avatar.previewWear = Wear.id; avatar.previewPants = Wear.pants; avatar.charId = profile.char
        avatar.visibility = View.VISIBLE; avatar.onResume()
        storeView.account = account; storeView.profile = profile; storeView.open(vault); storeView.visibility = View.VISIBLE
        // VAULT comes in from the opposite side of the STORE: the panel slides in from the right while the character stands on the left
        storeView.animate().cancel()
        if (vault) { storeView.translationX = root.width * 0.28f; storeView.alpha = 0f; storeView.animate().translationX(0f).alpha(1f).setDuration(260L).start() }
        else { storeView.translationX = 0f; storeView.alpha = 1f }
    }

    private fun openSettings(inMatch: Boolean) {
        settingsView.account = account; settingsView.inMatch = inMatch; settingsView.bringToFront(); settingsView.visibility = View.VISIBLE; settingsView.invalidate()
        if (inMatch) game?.setPaused(true)
    }

    private fun closeSettings() {
        settingsView.visibility = View.GONE; Prefs.save(this)
        game?.setPaused(false); game?.applyGfx()
        account?.let { lobby.account = it; lobby.invalidate() }
        if (profileView.visibility == View.VISIBLE) profileView.invalidate()
    }

    /** NEW profile screen: the game's lobby background + the 3D character (right half) stay behind the profile UI. */
    private fun openProfile() {
        lobby.visibility = View.GONE
        avatar.panel = true; avatar.panelLeft = false; avatar.panelFrac = 0.5f; avatar.usePreview = false; avatar.charId = profile.char
        avatar.visibility = View.VISIBLE; avatar.onResume()
        profileView.account = account; profileView.profile = profile; profileView.open()
    }

    /**
     * pfGear / pfEdit of the HTML: change the name (3 - 16 characters).
     * The first name is free (CREATE YOUR ID). EVERY change after that costs Store.NAME_CHANGE_DIAMONDS (200) diamonds, each time.
     * Diamonds are taken only when the new name is really saved (a different, valid name); cancel / same name = no charge.
     */
    private fun editName() {
        val a = account ?: return
        val cost = Store.NAME_CHANGE_DIAMONDS
        if (a.diamonds < cost) {
            android.app.AlertDialog.Builder(this).setTitle("NOT ENOUGH DIAMONDS")
                .setMessage("Changing your name costs $cost diamonds.\nYou have ${a.diamonds}. Watch videos in the lobby to earn more diamonds.")
                .setPositiveButton("OK", null).show()
            return
        }
        val input = android.widget.EditText(this).apply { setText(a.name); filters = arrayOf(android.text.InputFilter.LengthFilter(16)); setSelectAllOnFocus(true) }
        val dlg = android.app.AlertDialog.Builder(this).setTitle("CHANGE NAME - $cost DIAMONDS")
            .setMessage("Every name change costs $cost diamonds (you have ${a.diamonds}).")
            .setView(input)
            .setPositiveButton("SAVE", null).setNegativeButton("CANCEL", null).create()
        dlg.setOnShowListener {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text.toString().replace(Regex("\\s+"), " ").trim()
                if (v.length < 3 || v.length > 16) { input.error = "NAME MUST BE 3 - 16 CHARACTERS"; return@setOnClickListener }
                if (v == a.name) { dlg.dismiss(); android.widget.Toast.makeText(this, "SAME NAME - NO DIAMONDS TAKEN", android.widget.Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (a.diamonds < cost) { input.error = "NEED $cost DIAMONDS"; return@setOnClickListener }
                a.diamonds -= cost
                a.name = v; Store.save(this, a)
                lobby.account = a; lobby.invalidate(); profileView.invalidate(); dlg.dismiss()
                android.widget.Toast.makeText(this, "NAME CHANGED  -$cost DIAMONDS", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
    }

    /**
     * Profile photo (DP) instead of the avatar. EVERY change costs DpStore.COST (200) diamonds, each time.
     * The dialog shows the price first; the diamonds are taken only when the new photo was really saved (cancel / a broken file = no charge).
     */
    private fun editDp() {
        val a = account ?: return
        val cost = DpStore.COST
        if (a.diamonds < cost) {
            android.app.AlertDialog.Builder(this).setTitle("NOT ENOUGH DIAMONDS")
                .setMessage("Changing your profile photo costs $cost diamonds.\nYou have ${a.diamonds}. Watch videos in the lobby to earn more diamonds.")
                .setPositiveButton("OK", null).show()
            return
        }
        val b = android.app.AlertDialog.Builder(this).setTitle("PROFILE PHOTO - $cost DIAMONDS")
            .setMessage("Use a photo from your phone instead of the avatar. Every photo change costs $cost diamonds (you have ${a.diamonds}). Diamonds are taken only after the photo is saved.")
            .setPositiveButton("CHOOSE PHOTO") { _, _ ->
                try { dpPicker.launch("image/*") }
                catch (_: Throwable) { android.widget.Toast.makeText(this, "NO GALLERY APP FOUND", android.widget.Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("CANCEL", null)
        if (DpStore.bitmap != null) b.setNeutralButton("USE AVATAR (FREE)") { _, _ ->
            DpStore.clear(this); profileView.dpChanged(); lobby.invalidate()
            android.widget.Toast.makeText(this, "AVATAR RESTORED", android.widget.Toast.LENGTH_SHORT).show()
        }
        b.show()
    }

    private fun onDpPicked(uri: Uri?) {
        if (uri == null) return                                   // picker closed: nothing happens, nothing is charged
        val a = account ?: return
        val cost = DpStore.COST
        if (a.diamonds < cost) { android.widget.Toast.makeText(this, "NEED $cost DIAMONDS", android.widget.Toast.LENGTH_SHORT).show(); return }
        if (!DpStore.saveFrom(this, uri)) {
            android.widget.Toast.makeText(this, "COULD NOT USE THIS PHOTO - TRY ANOTHER", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        a.diamonds -= cost
        Store.save(this, a)
        lobby.account = a; lobby.invalidate(); profileView.invalidate(); profileView.dpChanged()
        android.widget.Toast.makeText(this, "PHOTO CHANGED  -$cost DIAMONDS", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showCharacters() {
        hideAvatar(); lobby.visibility = View.GONE
        charSel.account = account; charSel.profile = profile
        charSel.visibility = View.VISIBLE; charSel.invalidate()
    }

    /** START -> loading screen with REAL progress while the island map is parsed -> 3D match (OpenGL ES). */
    private fun startMatchLoading() {
        hideAvatar(); lobby.visibility = View.GONE
        mapLoadFailed = false
        loading.clearError(); loading.setLabel("Loading..."); loading.setProgress(0f); loading.visibility = View.VISIBLE
        val cached = mapData
        if (cached != null) { loading.setProgress(1f); loading.postDelayed({ beginMatch(cached); loading.visibility = View.GONE }, 500); return }
        Thread {
            try {
                val map = MapData(this) { p -> runOnUiThread { loading.setProgress(p * 0.9f) } }
                runOnUiThread {
                    mapData = map
                    beginMatch(map)
                    loading.setProgress(1f)
                    loading.postDelayed({ loading.visibility = View.GONE }, 600)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    mapLoadFailed = true
                    loading.showError("Map error: ${e.message ?: e.javaClass.simpleName}")
                    loading.onRetry = { loading.onRetry = { beginBoot() }; startMatchLoading() }
                }
            }
        }.start()
    }

    private fun beginMatch(map: MapData, warm: Boolean = true) {
        game?.let { it.onPause(); root.removeView(it) }
        val g = GameScreen(this, map, sfx, { r -> leaveMatch(r) }, { r -> applyResult(r) }, { restartMatch() }, { openSettings(true) }, warm, { mapData?.let { beginMatch(it, false) } }, { sum -> showResult(sum) })
        g.setCharacter(profile.char)
        game = g
        root.addView(g, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // one ad per match: it plays at the START of the 30 s pre-match warm-up (lobby wait). The real match (warm = false) never shows an ad.
        if (warm) { matchAdShown = false; scheduleWarmupAd(g, 0) }
    }

    /**
     * The ONE full-screen ad of a match. Shown right when the PRE-MATCH warm-up (the 30 s wait before the real match) begins, as soon as an ad is ready
     * (retries for ~16 s while it loads). The game pauses while the ad is open, so the 30 s warm-up timer only continues after the ad is closed.
     * If none is ready in time, the warm-up simply runs without an ad. Never shown twice per match and NEVER once the real match has started.
     */
    private fun scheduleWarmupAd(g: GameScreen, tries: Int) {
        g.postDelayed({
            if (matchAdShown || game !== g || !g.inWarmup()) return@postDelayed      // already shown / left the match / warm-up over (real match started)
            if (settingsView.visibility == View.VISIBLE) { if (tries < 8) scheduleWarmupAd(g, tries + 1); return@postDelayed }
            if (matchAd.isReady()) { matchAdShown = true; matchAd.show() }
            else if (tries < 8) scheduleWarmupAd(g, tries + 1)
        }, if (tries == 0) 1200L else 2000L)
    }

    private fun restartMatch() { mapData?.let { beginMatch(it) } }

    /** XP / coins / level-up exactly like __dlXp in the HTML; saved on the device. */
    private fun applyResult(r: MatchResult) {
        val a = account ?: return
        val before = a.level
        a.xp += r.xp; a.coins += r.coins
        a.record(r, r.coins)
        val after = a.level
        // every level gained pays its own random coin reward (50 - 200, steps of 10) and gets its own LEVEL UP animation (played when the lobby is back)
        if (after > before) {
            var total = 0L
            for (lv in before + 1..after) {
                val coins = (5 + kotlin.random.Random.nextInt(16)) * 10
                total += coins
                pendingLevelUps.add(LevelUpView.Step(lv - 1, lv, coins))
            }
            a.coins += total
        }
        Store.save(this, a)
    }

    /** Plays the LEVEL UP animation for every level gained in the last match (one after the other), then refreshes the coin counter. */
    private fun playLevelUps() {
        if (pendingLevelUps.isEmpty()) return
        val list = ArrayList(pendingLevelUps); pendingLevelUps.clear()
        levelUpView.play(list) { lobby.account = account; lobby.invalidate() }
    }

    /** Tap on the coin counter (lobby / store): the COINS -> DIAMONDS panel. */
    private fun openExchange() {
        val a = account ?: return
        if (game != null || loading.visibility == View.VISIBLE || levelUpView.isPlaying) return
        exchangeView.account = a
        exchangeView.open()
    }

    /** EXIT / LOBBY. A quit result (after 30 s) gives half XP, no placement bonus. */
    private fun leaveMatch(quit: MatchResult?) {
        if (quit != null) applyResult(quit)
        exitMatch()
        pendingMsg?.let { lobby.setMessage(it); pendingMsg = null }
        playLevelUps()
    }

    private fun exitMatch() {
        game?.let { it.onPause(); root.removeView(it) }
        game = null
        showLobby()
    }

    /** WINNER celebration / spectate BACK -> result screen: kills, damage and "#place / players" next to the player's own character. */
    private fun showResult(sum: MatchSummary) {
        if (game == null) return
        game?.let { it.onPause(); root.removeView(it) }
        game = null
        lobby.visibility = View.GONE; charSel.visibility = View.GONE; storeView.visibility = View.GONE
        avatar.panel = false; avatar.panelLeft = false; avatar.usePreview = false
        avatar.fixedSpin = Math.PI.toFloat()   // turned half a circle: the character faces the camera (0.35 showed his back)
        avatar.charId = profile.char; avatar.visibility = View.VISIBLE; avatar.onResume()
        resultView.show(sum, account?.name ?: "PLAYER")
    }

    /** BACK on the result screen: loading screen with a running bar, then the lobby (home screen). */
    private var resultLeaving = false
    private fun leaveResult() {
        if (resultLeaving) return
        resultLeaving = true
        loading.clearError(); loading.setLabel("Loading..."); loading.setProgress(0f); loading.visibility = View.VISIBLE; loading.bringToFront()
        val t0 = System.currentTimeMillis(); val dur = 1800L
        val tick = object : Runnable {
            override fun run() {
                val f = ((System.currentTimeMillis() - t0).toFloat() / dur).coerceIn(0f, 1f)
                loading.setProgress(f)
                if (f < 1f) loading.postDelayed(this, 30L)
                else loading.postDelayed({
                    resultView.visibility = View.GONE
                    showLobby()
                    loading.visibility = View.GONE
                    pendingMsg?.let { lobby.setMessage(it); pendingMsg = null }
                    resultLeaving = false
                    playLevelUps()
                }, 250L)
            }
        }
        loading.post(tick)
    }

    private var lastBackMs = 0L

    /**
     * Back button / back gesture. With targetSdk 36 (Android 16) predictive back is always on and onBackPressed() is no
     * longer called, so the screens are walked here, topmost first: a sub-screen goes one step back, a running match opens
     * the pause menu (settings), and loading / login / lobby need a second press within 2 s to leave the game.
     */
    private fun handleBack() {
        when {
            levelUpView.isPlaying -> levelUpView.skipAll()
            exchangeView.visibility == View.VISIBLE -> exchangeView.close()
            loading.visibility == View.VISIBLE && mapLoadFailed -> { mapLoadFailed = false; loading.clearError(); loading.visibility = View.GONE; loading.onRetry = { beginBoot() }; showLobby() }
            loading.visibility == View.VISIBLE || idScreen != null -> confirmExit()
            resultView.visibility == View.VISIBLE -> leaveResult()
            hudEdit.visibility == View.VISIBLE -> hudEdit.onClose?.invoke()
            settingsView.visibility == View.VISIBLE -> settingsView.onClose?.invoke()
            profileView.visibility == View.VISIBLE -> profileView.back()
            storeView.visibility == View.VISIBLE -> storeView.onClose?.invoke()
            charSel.visibility == View.VISIBLE -> charSel.onBack?.invoke()
            game != null -> if (game?.finishNow() != true) openSettings(true)
            else -> confirmExit()
        }
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackMs < 2000L) {
            finish()
        } else {
            lastBackMs = now
            android.widget.Toast.makeText(this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() { Prefs.save(this); game?.onPause(); if (avatar.visibility == View.VISIBLE) avatar.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); game?.onResume(); if (avatar.visibility == View.VISIBLE) avatar.onResume() }

    override fun onDestroy() {
        preloader?.cancel(); sfx?.release()
        if (::ads.isInitialized) ads.stop()
        if (::coinAds.isInitialized) coinAds.stop()
        if (::levelAds.isInitialized) levelAds.stop()
        if (::matchAd.isInitialized) matchAd.stop()
        bannerHandler.removeCallbacksAndMessages(null)
        if (::bannerAds.isInitialized) bannerAds.stop()
        super.onDestroy()
    }
}
