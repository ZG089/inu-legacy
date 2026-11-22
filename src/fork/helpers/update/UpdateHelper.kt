package desu.inugram.helpers.update

import android.content.DialogInterface
import android.os.Build
import android.os.Looper
import android.util.Base64
import androidx.core.view.doOnLayout
import desu.inugram.InuConfig
import desu.inugram.helpers.security.ParanoiaHelper
import desu.inugram.ui.ChangelogSheet
import desu.inugram.ui.settings.WhatsNewActivity
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BetaUpdate
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLoader
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.RichMessageLayout
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.messenger.Utilities
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.SerializedData
import org.telegram.tgnet.TLRPC
import org.telegram.tgnet.tl.TL_iv
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.LaunchActivity
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI

object UpdateHelper {
    const val USERNAME = "inu_legacy"
    private const val CHANNEL_ID = 4364828439L
    const val UPSTREAM_USERNAME = "InugramCI"
    private const val UPSTREAM_CHANNEL_ID = 3968318575L
    private const val GITHUB_REPO_FORK = "ZG089/inu-legacy"
    private const val GITHUB_REPO_UPSTREAM = "teidesu/inugram"
    private const val CHECK_INTERVAL_MS = 4L * 60 * 60 * 1000
    private const val INFLIGHT_TIMEOUT_MS = 60L * 1000

    val packageInfo by lazy {
        ApplicationLoader.applicationContext.packageManager.getPackageInfo(
            ApplicationLoader.applicationContext.packageName,
            0
        )
    }

    @JvmStatic
    val stockVersionName by lazy {
        packageInfo.versionName?.replace(Regex("-[0-9a-f]{7}$"), "") ?: ""
    }

    fun getVersionInfoString(): String {
        return LocaleController.formatString(
            R.string.InuVersion,
            packageInfo.versionCode,
            stockVersionName,
            BuildConfig.STOCK_VERSION_CODE,
            LocaleController.getString(
                if (BuildConfig.INU_PLUGINLESS) R.string.InuBuildPluginless else R.string.InuBuildFull
            )
        )
    }

    @JvmStatic
    fun getFullVersionInfo(): String {
        if (ParanoiaHelper.isDisguised()) {
            return "Telegram for Android v${stockVersionName} (${BuildConfig.STOCK_VERSION_CODE})\ndirect ${Build.CPU_ABI} ${Build.CPU_ABI2}"
        }
        return "${getVersionInfoString()}\nBuilt on: ${BuildVars.BUILD_DATE}"
    }

    private val APK_RE = Regex("^inugram(?:-legacy)?-(.+?)-(\\d+)(?:-(pluginless|full))?(?:-armeabi-v7a)?\\.apk$")
    private val SHORT_SHA_RE = Regex("-([0-9a-f]{7,40})$")

    @Volatile
    private var inflight = false

    @Volatile
    private var inflightSince = 0L

    @Volatile
    var pendingBetaUpdate: BetaUpdate? = null
        private set

    // cached source message of the current pending update, set by applyUpdate. lets
    // startDownload skip the resolver+RPC dance when the update was detected this session.
    @Volatile
    private var pendingSourceMessage: TLRPC.Message? = null

    // true between the click on Update and FileLoader.loadFile actually firing. lets the row
    // show the Downloading state immediately even while the async file-ref refresh dance is
    // still running.
    @Volatile
    var isPendingStart: Boolean = false
        private set

    @Volatile
    var downloadAccount: Int = UserConfig.selectedAccount
        private set

    // applyUpdate doesn't post appUpdateAvailable itself — the caller does it, via
    // revealPendingUpdate, once the changelog dialog is on screen (so the bar slides in behind
    // the dialog instead of visibly popping into the page underneath).
    @JvmStatic
    fun revealPendingUpdate() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable, true)
    }

    fun checkForCustomUpdate(force: Boolean, whenDone: Runnable?) {
        if (!InuConfig.UPDATES_ENABLED.value) {
            whenDone?.run()
            return
        }
        if (!force && System.currentTimeMillis() - InuConfig.UPDATE_LAST_CHECK_MS.value < CHECK_INTERVAL_MS) {
            whenDone?.run()
            return
        }
        check { whenDone?.run() }
    }

    // a pending update saved by an older build carries plain text there
    fun getChangelog(update: TLRPC.TL_help_appUpdate): TL_iv.RichMessage? {
        val bytes = runCatching { Base64.decode(update.text, Base64.NO_WRAP) }.getOrNull() ?: return null
        val data = SerializedData(bytes)
        val changelog = TL_iv.RichMessage.TLdeserialize(data, data.readInt32(false), false)
        data.cleanup()
        return changelog
    }

    fun clearPending() {
        pendingBetaUpdate = null
        pendingSourceMessage = null
        isPendingStart = false
        SharedConfig.pendingAppUpdate = null
        SharedConfig.saveConfig()
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable, false)
    }

    @JvmStatic
    fun clearPendingIfInstalled() {
        val pending = SharedConfig.pendingAppUpdate ?: return
        val current = currentBuild()
        if (pending.version == current.versionCode.toString()) {
            clearPending()
        }
    }

    fun startDownload(account: Int) {
        val update = SharedConfig.pendingAppUpdate ?: return
        val doc = update.document ?: return

        downloadAccount = account
        isPendingStart = true
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)

        val cached = pendingSourceMessage
        if (cached != null) {
            beginLoad(account, doc, MessageObject(account, cached, false, false))
            return
        }

        val messageId = update.id
        if (messageId <= 0) {
            refreshPendingAndStart(account)
            return
        }
        val mc = MessagesController.getInstance(account)
        // resolve first so the channel (with access_hash) is cached for getInputChannel
        mc.userNameResolver.resolve(USERNAME) { peerId ->
            AndroidUtilities.runOnUIThread {
                if (!isPendingStart) return@runOnUIThread
                if (peerId == null || peerId == 0L || peerId == Long.MAX_VALUE) {
                    stopPendingStart()
                    return@runOnUIThread
                }
                loadRichMessage(account, messageId) { msg ->
                    if (!isPendingStart) return@loadRichMessage
                    val freshDoc = msg?.let { extractApkInfo(it, currentVariant())?.document }
                    if (msg == null || freshDoc == null) {
                        beginLoad(account, doc, sourceMessageParent(messageId))
                    } else {
                        pendingSourceMessage = msg
                        beginLoad(account, freshDoc, MessageObject(account, msg, false, false))
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        if (isPendingStart) {
            isPendingStart = false
        } else {
            SharedConfig.pendingAppUpdate?.document?.let {
                FileLoader.getInstance(downloadAccount).cancelLoadFile(it)
            }
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    private fun refreshPendingAndStart(account: Int) {
        check {
            AndroidUtilities.runOnUIThread {
                if (!isPendingStart) return@runOnUIThread
                if ((SharedConfig.pendingAppUpdate?.id ?: 0) > 0) {
                    startDownload(account)
                } else {
                    stopPendingStart()
                }
            }
        }
    }

    private fun sourceMessageParent(messageId: Int) = "sent_${CHANNEL_ID}_${messageId}"

    private fun stopPendingStart() {
        isPendingStart = false
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    private fun beginLoad(account: Int, document: TLRPC.Document, parent: Any) {
        isPendingStart = false
        FileLoader.getInstance(account).loadFile(document, parent, FileLoader.PRIORITY_NORMAL, 1)
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading)
    }

    fun check(callback: ((CheckResult) -> Unit)?) {
        if (BuildConfig.INU_BUILD_TYPE == "debug") {
            callback?.invoke(CheckResult.UpToDate)
            return
        }
        check(currentVariant(), callback)
    }

    fun prepareVariantSwitch(callback: (VariantSwitchResult) -> Unit) {
        if (BuildConfig.INU_BUILD_TYPE == "debug") {
            callback(VariantSwitchResult.Unavailable)
            return
        }
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback(VariantSwitchResult.Error("Not logged in"))
            return
        }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) {
            callback(VariantSwitchResult.InFlight)
            return
        }
        inflight = true
        inflightSince = now
        searchReleases(account, 10) { messages, error ->
            if (messages == null) {
                finishVariantSwitch(callback, VariantSwitchResult.Error(error.orEmpty()))
                return@searchReleases
            }
            val latest = messages.firstOrNull()
            fun onLoaded(msg: TLRPC.Message?) {
                val info = msg?.let {
                    extractApkInfo(it, if (BuildConfig.INU_PLUGINLESS) "full" else "pluginless")
                }
                finishVariantSwitch(
                    callback,
                    if (msg == null || info == null || info.verCode < currentBuild().versionCode) VariantSwitchResult.Unavailable
                    else VariantSwitchResult.Ready(VariantSwitch(info.document, msg)),
                )
            }
            if (latest?.rich_message?.part == true) loadRichMessage(account, latest.id, ::onLoaded)
            else onLoaded(latest)
        }
    }

    fun startVariantSwitchDownload(account: Int, variantSwitch: VariantSwitch) {
        FileLoader.getInstance(account).loadFile(
            variantSwitch.document,
            MessageObject(account, variantSwitch.sourceMessage, false, false),
            FileLoader.PRIORITY_NORMAL,
            1,
        )
    }

    fun cancelVariantSwitchDownload(account: Int, variantSwitch: VariantSwitch) {
        FileLoader.getInstance(account).cancelLoadFile(variantSwitch.document)
    }

    private fun check(variant: String, callback: ((CheckResult) -> Unit)?) {
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            callback?.invoke(CheckResult.Error("Not logged in"))
            return
        }
        val now = System.currentTimeMillis()
        if (inflight && now - inflightSince < INFLIGHT_TIMEOUT_MS) {
            callback?.invoke(CheckResult.InFlight)
            return
        }
        inflight = true
        inflightSince = now
        searchReleases(account, 10) { messages, error ->
            if (messages == null) {
                finish(callback, CheckResult.Error(error.orEmpty()))
                return@searchReleases
            }
            val latest = messages.firstOrNull()
            fun onLoaded(msg: TLRPC.Message?) {
                val info = msg?.let { extractApkInfo(it, variant) }
                val current = currentBuild()
                if (info == null || !isNewer(info, current)) {
                    clearPending()
                    finish(callback, CheckResult.UpToDate)
                    return
                }
                stashChangelogs(msg, info, current, variant)
                finish(callback, CheckResult.Updated(applyUpdate(msg, info, current, variant)))
            }
            if (latest?.rich_message?.part == true) loadRichMessage(account, latest.id, ::onLoaded)
            else onLoaded(latest)
        }
    }

    private fun searchReleases(
        account: Int,
        limit: Int,
        callback: (messages: List<TLRPC.Message>?, error: String?) -> Unit,
    ) {
        val mc = MessagesController.getInstance(account)
        mc.userNameResolver.resolve(USERNAME) { id ->
            if (id == null || id == 0L || id == Long.MAX_VALUE) {
                AndroidUtilities.runOnUIThread { callback(null, "resolve failed") }
                return@resolve
            }
            val req = TLRPC.TL_messages_search().apply {
                peer = mc.getInputPeer(id)
                q = "#release"
                filter = TLRPC.TL_inputMessagesFilterEmpty()
                this.limit = limit
            }
            ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
                AndroidUtilities.runOnUIThread {
                    if (err != null || resp !is TLRPC.messages_Messages) {
                        callback(null, err?.text ?: "no response")
                    } else {
                        callback(resp.messages.filter { it.rich_message != null }.sortedByDescending { it.id }, null)
                    }
                }
            }
        }
    }

    private fun searchChannelReleases(
        account: Int,
        username: String,
        channelId: Long,
        limit: Int,
        callback: (List<Release>?) -> Unit,
    ) {
        val mc = MessagesController.getInstance(account)
        mc.userNameResolver.resolve(username) { id ->
            if (id == null || id == 0L || id == Long.MAX_VALUE) {
                AndroidUtilities.runOnUIThread { callback(null) }
                return@resolve
            }
            val resolvedChannelId = if (id < 0) -id else id
            val req = TLRPC.TL_messages_search().apply {
                peer = mc.getInputPeer(id)
                q = "#release"
                filter = TLRPC.TL_inputMessagesFilterEmpty()
                this.limit = limit
            }
            ConnectionsManager.getInstance(account).sendRequest(req) { resp, err ->
                AndroidUtilities.runOnUIThread {
                    if (err != null || resp !is TLRPC.messages_Messages) {
                        callback(null)
                        return@runOnUIThread
                    }
                    val messages = resp.messages.filter { it.rich_message != null }.sortedByDescending { it.id }
                    if (messages.isEmpty()) {
                        callback(emptyList())
                        return@runOnUIThread
                    }
                    val variant = currentVariant()
                    val loaded = arrayOfNulls<TLRPC.Message>(messages.size)
                    var pending = messages.size
                    fun onLoaded(index: Int, msg: TLRPC.Message?) {
                        loaded[index] = msg
                        if (--pending > 0) return
                        val releases = loaded.mapNotNull { full ->
                            full?.let { createRelease(it, variant) }
                        }
                        callback(releases)
                    }
                    messages.forEachIndexed { index, msg ->
                        if (msg.rich_message?.part == true) {
                            loadRichMessage(account, msg.id, resolvedChannelId) { onLoaded(index, it) }
                        } else {
                            onLoaded(index, msg)
                        }
                    }
                }
            }
        }
    }

    // ponytail: no pagination, the newest `limit` releases are all anyone gets to see
    fun loadReleases(
        limit: Int,
        versions: IntRange = 1..currentBuild().versionCode,
        callback: (List<Release>?) -> Unit,
    ) {
        val account = UserConfig.selectedAccount
        if (!UserConfig.getInstance(account).isClientActivated) {
            loadReleasesFromGitHub(limit, versions, callback)
            return
        }

        var forkReleases: List<Release>? = null
        var upstreamReleases: List<Release>? = null
        var pending = 2

        fun onDone() {
            if (--pending > 0) return
            val fork = forkReleases.orEmpty()
            val upstream = upstreamReleases.orEmpty()
            if (fork.isEmpty() && upstream.isEmpty()) {
                loadReleasesFromGitHub(limit, versions, callback)
                return
            }
            val combined = combineReleases(fork, upstream).filter { it.verCode in versions }
            callback(combined)
        }

        searchChannelReleases(account, USERNAME, CHANNEL_ID, limit) { res ->
            forkReleases = res
            onDone()
        }

        searchChannelReleases(account, UPSTREAM_USERNAME, UPSTREAM_CHANNEL_ID, limit) { res ->
            upstreamReleases = res
            onDone()
        }
    }

    private fun createRelease(msg: TLRPC.Message, variant: String): Release? {
        val info = extractApkInfo(msg, variant)
        val verCode = info?.verCode ?: extractVersionFromMessage(msg) ?: return null
        val changelog = extractChangelog(msg, variant)
        return Release(verCode, changelog)
    }

    private fun extractVersionFromMessage(msg: TLRPC.Message): Int? {
        val text = msg.message.orEmpty()
        val reTag = Regex("""#release\s+(?:v)?(\d+)""", RegexOption.IGNORE_CASE)
        reTag.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val reVer = Regex("""v(?:13\.\d+\.\d+(?:\.\d+)?-)?(\d+)""", RegexOption.IGNORE_CASE)
        reVer.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        val richHeading = msg.rich_message?.blocks.orEmpty().filterIsInstance<TL_iv.pageBlockHeading1>().firstOrNull()
        if (richHeading != null) {
            val hText = RichMessageLayout.getString(richHeading.text)
            Regex("""\b(\d+)\b""").find(hText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    internal class Deduplicator {
        private val hashes = mutableSetOf<String>()
        private val messages = mutableSetOf<String>()
        private val rawTexts = mutableSetOf<String>()

        fun record(text: String) {
            if (text.isBlank()) return
            rawTexts.add(text.trim().lowercase())
            val hashRegex = Regex("""\b([0-9a-f]{7,40})\b""", RegexOption.IGNORE_CASE)
            for (m in hashRegex.findAll(text)) {
                hashes.add(m.value.lowercase())
            }
            val norm = normalize(text)
            if (norm.isNotBlank()) messages.add(norm)
        }

        fun isDuplicate(text: String): Boolean {
            if (text.isBlank()) return false
            val trimmed = text.trim().lowercase()
            if (rawTexts.contains(trimmed)) return true
            val hashRegex = Regex("""\b([0-9a-f]{7,40})\b""", RegexOption.IGNORE_CASE)
            val foundHashes = hashRegex.findAll(text).map { it.value.lowercase() }.toList()
            if (foundHashes.isNotEmpty() && foundHashes.any { hashes.contains(it) }) return true
            val norm = normalize(text)
            if (norm.isNotBlank() && messages.contains(norm)) return true
            return false
        }

        private fun normalize(text: String): String {
            return text
                .replace(Regex("""\b[0-9a-f]{7,40}\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\bhttps?://\S+"""), "")
                .replace(Regex("""^[\s\-*•—–:>\]\[()]+"""), "")
                .replace(Regex("""[•—–]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .lowercase()
        }
    }

    fun combineReleases(forkReleases: List<Release>, upstreamReleases: List<Release>): List<Release> {
        val allCodes = (forkReleases.map { it.verCode } + upstreamReleases.map { it.verCode }).distinct().sortedDescending()
        val forkByCode = forkReleases.groupBy { it.verCode }
        val upstreamByCode = upstreamReleases.groupBy { it.verCode }

        return allCodes.mapNotNull { code ->
            val forkRel = forkByCode[code]?.firstOrNull()
            val upstreamRel = upstreamByCode[code]?.firstOrNull()

            if (forkRel == null && upstreamRel == null) return@mapNotNull null

            if (upstreamRel == null) {
                val blocks = ArrayList<TL_iv.PageBlock>()
                blocks.add(TL_iv.pageBlockHeading1().apply {
                    text = TL_iv.textPlain().apply { text = "v$code" }
                })
                blocks.addAll(forkRel!!.changelog.blocks.orEmpty().filterNot { isVersionHeader(it, code) })
                val rich = TL_iv.RichMessage().apply {
                    this.blocks = blocks
                    photos = forkRel.changelog.photos
                    documents = forkRel.changelog.documents
                }
                return@mapNotNull Release(code, rich)
            }

            if (forkRel == null) {
                val blocks = ArrayList<TL_iv.PageBlock>()
                blocks.add(TL_iv.pageBlockHeading1().apply {
                    text = TL_iv.textPlain().apply { text = "v$code" }
                })
                blocks.addAll(upstreamRel.changelog.blocks.orEmpty().filterNot { isVersionHeader(it, code) })
                val rich = TL_iv.RichMessage().apply {
                    this.blocks = blocks
                    photos = upstreamRel.changelog.photos
                    documents = upstreamRel.changelog.documents
                }
                return@mapNotNull Release(code, rich)
            }

            val dedup = Deduplicator()
            val forkBlocks = forkRel.changelog.blocks.orEmpty().filterNot { isVersionHeader(it, code) }
            for (b in forkBlocks) {
                recordBlockTexts(b, dedup)
            }

            val upstreamBlocks = upstreamRel.changelog.blocks.orEmpty().filterNot { isVersionHeader(it, code) }
            val filteredUpstreamBlocks = filterDuplicateBlocks(upstreamBlocks, dedup)

            val combinedBlocks = ArrayList<TL_iv.PageBlock>()
            combinedBlocks.add(TL_iv.pageBlockHeading1().apply {
                text = TL_iv.textPlain().apply { text = "v$code" }
            })

            // Top section: Fork changes
            if (filteredUpstreamBlocks.isNotEmpty()) {
                combinedBlocks.add(TL_iv.pageBlockHeading2().apply {
                    text = TL_iv.textPlain().apply {
                        text = LocaleController.getString(R.string.InuChangelogFork)
                    }
                })
            }
            combinedBlocks.addAll(forkBlocks)

            // Divider and Upstream section
            if (filteredUpstreamBlocks.isNotEmpty()) {
                combinedBlocks.add(TL_iv.pageBlockDivider())
                combinedBlocks.add(TL_iv.pageBlockHeading2().apply {
                    text = TL_iv.textPlain().apply {
                        text = LocaleController.getString(R.string.InuChangelogUpstream)
                    }
                })
                combinedBlocks.addAll(filteredUpstreamBlocks)
            }

            val combinedRich = TL_iv.RichMessage().apply {
                this.blocks = combinedBlocks
                photos = ArrayList((forkRel.changelog.photos.orEmpty() + upstreamRel.changelog.photos.orEmpty()).distinct())
                documents = ArrayList((forkRel.changelog.documents.orEmpty() + upstreamRel.changelog.documents.orEmpty()).distinct())
            }

            Release(code, combinedRich)
        }
    }

    private fun isVersionHeader(block: TL_iv.PageBlock, code: Int): Boolean {
        if (block is TL_iv.pageBlockHeading1) {
            val text = RichMessageLayout.getString(block.text).trim()
            return text.equals("v$code", ignoreCase = true) || text.equals("$code", ignoreCase = true)
        }
        return false
    }

    private fun recordBlockTexts(block: TL_iv.PageBlock, dedup: Deduplicator) {
        when (block) {
            is TL_iv.pageBlockParagraph -> dedup.record(RichMessageLayout.getString(block.text))
            is TL_iv.pageBlockBlockquote -> dedup.record(RichMessageLayout.getString(block.text))
            is TL_iv.pageBlockHeading2 -> dedup.record(RichMessageLayout.getString(block.text))
            is TL_iv.pageBlockHeading3 -> dedup.record(RichMessageLayout.getString(block.text))
            is TL_iv.pageBlockList -> {
                for (item in block.items.orEmpty()) {
                    dedup.record(RichMessageLayout.getString(item))
                }
            }
            is TL_iv.pageBlockOrderedList -> {
                for (item in block.items.orEmpty()) {
                    dedup.record(RichMessageLayout.getString(item))
                }
            }
        }
    }

    private fun filterDuplicateBlocks(blocks: List<TL_iv.PageBlock>, dedup: Deduplicator): List<TL_iv.PageBlock> {
        val result = ArrayList<TL_iv.PageBlock>()
        for (block in blocks) {
            when (block) {
                is TL_iv.pageBlockParagraph -> {
                    val str = RichMessageLayout.getString(block.text)
                    if (!dedup.isDuplicate(str)) {
                        result.add(block)
                        dedup.record(str)
                    }
                }
                is TL_iv.pageBlockBlockquote -> {
                    val str = RichMessageLayout.getString(block.text)
                    if (!dedup.isDuplicate(str)) {
                        result.add(block)
                        dedup.record(str)
                    }
                }
                is TL_iv.pageBlockList -> {
                    val remainingItems = block.items.orEmpty().filterNot { item ->
                        val str = RichMessageLayout.getString(item)
                        val isDup = dedup.isDuplicate(str)
                        if (!isDup) dedup.record(str)
                        isDup
                    }
                    if (remainingItems.isNotEmpty()) {
                        val newList = TL_iv.pageBlockList().apply {
                            items = ArrayList(remainingItems)
                        }
                        result.add(newList)
                    }
                }
                is TL_iv.pageBlockOrderedList -> {
                    val remainingItems = block.items.orEmpty().filterNot { item ->
                        val str = RichMessageLayout.getString(item)
                        val isDup = dedup.isDuplicate(str)
                        if (!isDup) dedup.record(str)
                        isDup
                    }
                    if (remainingItems.isNotEmpty()) {
                        val newList = TL_iv.pageBlockOrderedList().apply {
                            items = ArrayList(remainingItems)
                        }
                        result.add(newList)
                    }
                }
                is TL_iv.pageBlockHeading2, is TL_iv.pageBlockHeading3 -> {
                    result.add(block)
                }
                is TL_iv.pageBlockDivider -> {}
                else -> {
                    result.add(block)
                }
            }
        }
        val cleaned = ArrayList<TL_iv.PageBlock>()
        for (i in result.indices) {
            val b = result[i]
            if (b is TL_iv.pageBlockHeading2 || b is TL_iv.pageBlockHeading3) {
                val hasContentAfter = result.drop(i + 1).any { it !is TL_iv.pageBlockHeading2 && it !is TL_iv.pageBlockHeading3 && it !is TL_iv.pageBlockDivider }
                if (hasContentAfter) cleaned.add(b)
            } else {
                cleaned.add(b)
            }
        }
        return cleaned
    }

    private fun loadReleasesFromGitHub(
        limit: Int,
        versions: IntRange,
        callback: (List<Release>?) -> Unit,
    ) {
        Utilities.globalQueue.postRunnable {
            val fork = fetchGitHubReleases(GITHUB_REPO_FORK, limit)
            val upstream = fetchGitHubReleases(GITHUB_REPO_UPSTREAM, limit)
            val combined = combineReleases(fork, upstream).filter { it.verCode in versions }
            AndroidUtilities.runOnUIThread {
                callback(if (combined.isEmpty()) null else combined)
            }
        }
    }

    private fun fetchGitHubReleases(repo: String, limit: Int): List<Release> {
        return runCatching {
            val url = URI("https://api.github.com/repos/$repo/releases?per_page=$limit").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Inugram-Android")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            if (conn.responseCode != 200) return emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            val list = mutableListOf<Release>()
            val verRe = Regex("""(?:\.|\b|-)(\d+)(?:-legacy)?$""")
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val tag = obj.optString("tag_name", "")
                val body = obj.optString("body", "")
                val verCode = verRe.find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""\b(\d+)\b""").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                val rich = markdownToRichMessage(body)
                list.add(Release(verCode, rich))
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun markdownToRichMessage(body: String): TL_iv.RichMessage {
        val blocks = ArrayList<TL_iv.PageBlock>()
        val currentListItems = ArrayList<TL_iv.RichText>()

        fun flushList() {
            if (currentListItems.isNotEmpty()) {
                blocks.add(TL_iv.pageBlockList().apply {
                    items = ArrayList(currentListItems)
                })
                currentListItems.clear()
            }
        }

        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flushList()
                continue
            }
            if (trimmed.startsWith("### ")) {
                flushList()
                blocks.add(TL_iv.pageBlockHeading3().apply {
                    text = TL_iv.textPlain().apply { text = trimmed.removePrefix("### ").trim() }
                })
            } else if (trimmed.startsWith("## ")) {
                flushList()
                blocks.add(TL_iv.pageBlockHeading2().apply {
                    text = TL_iv.textPlain().apply { text = trimmed.removePrefix("## ").trim() }
                })
            } else if (trimmed.startsWith("# ")) {
                flushList()
                blocks.add(TL_iv.pageBlockHeading1().apply {
                    text = TL_iv.textPlain().apply { text = trimmed.removePrefix("# ").trim() }
                })
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                val itemText = trimmed.substring(2).trim()
                currentListItems.add(TL_iv.textPlain().apply { text = itemText })
            } else {
                flushList()
                blocks.add(TL_iv.pageBlockParagraph().apply {
                    text = TL_iv.textPlain().apply { text = trimmed }
                })
            }
        }
        flushList()

        return TL_iv.RichMessage().apply {
            this.blocks = blocks
        }
    }

    private fun isUpdatedInstall() = packageInfo.firstInstallTime != packageInfo.lastUpdateTime

    fun hasUnseenWhatsNew(): Boolean {
        val seen = InuConfig.WHATS_NEW_SEEN_BUILD.value
        return if (seen == 0) isUpdatedInstall() else seen < currentBuild().versionCode
    }

    // the update dialog marks the build it is about to download, ahead of the installed one
    fun markWhatsNewSeen(build: Int = currentBuild().versionCode) {
        InuConfig.WHATS_NEW_SEEN_BUILD.value = maxOf(InuConfig.WHATS_NEW_SEEN_BUILD.value, build)
    }

    // in-process only: whether the stash was already found to hold nothing for this build
    @Volatile
    private var whatsNewChecked = false

    private var whatsNewSheetActivity: WeakReference<LaunchActivity>? = null

    private val passcodeObserver = object : NotificationCenter.NotificationCenterDelegate {
        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.passcodeDismissed)
            AndroidUtilities.runOnUIThread { maybeShowWhatsNew(LaunchActivity.instance ?: return@runOnUIThread) }
        }
    }

    private fun writeStashedReleases(releases: List<Release>) {
        val data = SerializedData()
        data.writeInt32(releases.size)
        for (release in releases) {
            data.writeInt32(release.verCode)
            release.changelog.serializeToStream(data)
        }
        InuConfig.WHATS_NEW_STASH.value = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
        data.cleanup()
        whatsNewChecked = false
    }

    private fun readStashedReleases(): List<Release> {
        val stash = InuConfig.WHATS_NEW_STASH.value
        if (stash.isEmpty()) return emptyList()
        var data: SerializedData? = null
        return try {
            data = SerializedData(Base64.decode(stash, Base64.NO_WRAP))
            List(data.readInt32(true)) {
                val verCode = data.readInt32(true)
                Release(verCode, TL_iv.RichMessage.TLdeserialize(data, data.readInt32(true), true))
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            data?.cleanup()
        }
    }

    // the stash survives until the user closes the sheet: an activity recreated during startup
    // takes a sheet shown on it down without dismissing it, so the next resume shows it again
    fun maybeShowWhatsNew(activity: LaunchActivity) {
        if (whatsNewChecked || whatsNewSheetActivity?.get() === activity) return
        val current = currentBuild().versionCode
        val seen = InuConfig.WHATS_NEW_SEEN_BUILD.value
        val releases = readStashedReleases().filter { it.verCode in seen + 1..current }
        if (releases.isEmpty()) {
            whatsNewChecked = true
            return
        }
        if (!InuConfig.WHATS_NEW_ON_UPDATE.value || ParanoiaHelper.isDisguised()) {
            InuConfig.WHATS_NEW_STASH.value = ""
            whatsNewChecked = true
            return
        }
        if (AndroidUtilities.needShowPasscode(false) || SharedConfig.isWaitingForPasscodeEnter) {
            NotificationCenter.getGlobalInstance().addObserver(passcodeObserver, NotificationCenter.passcodeDismissed)
            return
        }
        whatsNewSheetActivity = WeakReference(activity)
        // stock sheets size their header and insets from the global system bar sizes, which a cold
        // start only fills in during the activity's first layout. Waiting for the main thread to go
        // idle after that keeps the open animation out of the startup work
        activity.window.decorView.doOnLayout {
            Looper.myQueue().addIdleHandler {
                var disabled = false
                ChangelogSheet(
                    activity,
                    LocaleController.getString(R.string.InuWhatsNew),
                    releases.map {
                        RichMessageLayout.PreviewView(activity, UserConfig.selectedAccount, null).apply {
                            set(it.changelog)
                            setPadding(AndroidUtilities.dp(23f), AndroidUtilities.dp(15f), AndroidUtilities.dp(23f), AndroidUtilities.dp(8f))
                        }
                    },
                    LocaleController.getString(R.string.InuWhatsNewDontShowAgain),
                    {
                        InuConfig.WHATS_NEW_ON_UPDATE.value = false
                        disabled = true
                    },
                    LocaleController.getString(R.string.OK),
                    {},
                ).apply {
                    setOnDismissListener(DialogInterface.OnDismissListener {
                        InuConfig.WHATS_NEW_STASH.value = ""
                        markWhatsNewSeen()
                        whatsNewChecked = true
                        whatsNewSheetActivity = null
                        if (disabled) {
                            BulletinFactory.global().createSimpleBulletin(
                                R.raw.info,
                                LocaleController.getString(R.string.InuWhatsNewDisabled),
                                AndroidUtilities.replaceSingleTag(LocaleController.getString(R.string.InuWhatsNewDisabledInfo)) {
                                    LaunchActivity.getSafeLastFragment()?.presentFragment(WhatsNewActivity())
                                },
                            ).show()
                        }
                    })
                }.show()
                false
            }
        }
    }

    private fun stashChangelogs(msg: TLRPC.Message, info: ApkInfo, current: CurrentBuild, variant: String) {
        writeStashedReleases(listOfNotNull(createRelease(msg, variant)))
        loadReleases(10, current.versionCode + 1..info.verCode) { releases ->
            if (!releases.isNullOrEmpty()) {
                writeStashedReleases(releases)
                val matching = releases.firstOrNull { it.verCode == info.verCode }
                if (matching != null && SharedConfig.pendingAppUpdate?.version == info.verCode.toString()) {
                    val data = SerializedData(matching.changelog.objectSize)
                    matching.changelog.serializeToStream(data)
                    SharedConfig.pendingAppUpdate.text = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
                    data.cleanup()
                    SharedConfig.saveConfig()
                }
            }
        }
    }

    // search results may hold a truncated rich message without all of its documents
    private fun loadRichMessage(account: Int, messageId: Int, channelId: Long = CHANNEL_ID, callback: (TLRPC.Message?) -> Unit) {
        val req = TL_iv.getRichMessage().apply {
            peer = MessagesController.getInstance(account).getInputPeer(-channelId)
            id = messageId
        }
        ConnectionsManager.getInstance(account).sendRequest(req) { resp, _ ->
            AndroidUtilities.runOnUIThread {
                callback((resp as? TLRPC.messages_Messages)?.messages?.firstOrNull { it.id == messageId })
            }
        }
    }

    fun onNewMessage(msg: TLRPC.Message, account: Int) {
        if (!InuConfig.UPDATES_ENABLED.value) return
        if (BuildConfig.INU_BUILD_TYPE == "debug") return
        if (msg.peer_id?.channel_id != CHANNEL_ID) return
        val rich = msg.rich_message ?: return
        fun onLoaded(full: TLRPC.Message?) {
            val variant = currentVariant()
            val info = full?.let { extractApkInfo(it, variant) } ?: return
            val current = currentBuild()
            if (!isNewer(info, current)) return
            stashChangelogs(full, info, current, variant)
            applyUpdate(full, info, current, variant)
            revealPendingUpdate()
            InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        }
        AndroidUtilities.runOnUIThread {
            if (rich.part) loadRichMessage(account, msg.id, ::onLoaded)
            else onLoaded(msg)
        }
    }

    private fun applyUpdate(
        msg: TLRPC.Message,
        info: ApkInfo,
        current: CurrentBuild,
        variant: String,
    ): TLRPC.TL_help_appUpdate {
        val changelog = extractChangelog(msg, variant)
        val data = SerializedData(changelog.objectSize)
        changelog.serializeToStream(data)

        val updateObj = TLRPC.TL_help_appUpdate().apply {
            flags = flags or 2
            // stash the source channel message id and the serialized changelog in otherwise-unused fields
            id = msg.id
            version = info.verCode.toString()
            text = Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
            document = info.document
        }
        data.cleanup()

        SharedConfig.pendingAppUpdate = updateObj
        SharedConfig.pendingAppUpdateBuildVersion = current.versionCode
        SharedConfig.saveConfig()
        pendingBetaUpdate = BetaUpdate(info.appVerName, info.verCode, null)
        pendingSourceMessage = msg
        return updateObj
    }

    private fun extractChangelog(msg: TLRPC.Message, variant: String): TL_iv.RichMessage {
        var skipping = false
        val blocks = msg.rich_message.blocks.filterIsInstance<TL_iv.pageBlockDetails>().firstOrNull()?.blocks.orEmpty().filterTo(ArrayList()) {
            if (it is TL_iv.pageBlockHeading2) {
                skipping = variant == "pluginless" && RichMessageLayout.getString(it.text).trim() == "Plugins"
            }
            !skipping
        }
        return TL_iv.RichMessage().apply {
            this.blocks = blocks
            photos = msg.rich_message.photos
            documents = msg.rich_message.documents
        }
    }

    private fun finish(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        inflight = false
        InuConfig.UPDATE_LAST_CHECK_MS.value = System.currentTimeMillis()
        callback?.invoke(result)
    }

    private fun finishVariantSwitch(
        callback: (VariantSwitchResult) -> Unit,
        result: VariantSwitchResult,
    ) {
        inflight = false
        callback(result)
    }

    @Suppress("DEPRECATION")
    private fun currentBuild(): CurrentBuild = CurrentBuild(packageInfo.versionCode)

    private fun extractApkInfo(msg: TLRPC.Message, variant: String): ApkInfo? {
        val (match, doc) = msg.rich_message?.documents.orEmpty().firstNotNullOfOrNull { doc ->
            APK_RE.matchEntire(FileLoader.getDocumentFileName(doc))
                ?.takeIf {
                    val apkVariant = if (it.groupValues[3] == "pluginless") "pluginless" else "full"
                    apkVariant == variant
                }
                ?.let { it to doc }
        } ?: return null
        val verName = match.groupValues[1]
        val verCode = match.groupValues[2].toIntOrNull() ?: return null
        val appVerName = verName.replace(SHORT_SHA_RE, "")
        return ApkInfo(verCode, appVerName, doc)
    }

    private fun currentVariant(): String = if (BuildConfig.INU_PLUGINLESS) "pluginless" else "full"

    private fun isNewer(remote: ApkInfo, current: CurrentBuild): Boolean {
        return remote.verCode > current.versionCode
    }

    sealed class CheckResult {
        object InFlight : CheckResult()
        object UpToDate : CheckResult()
        data class Updated(val update: TLRPC.TL_help_appUpdate) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    sealed class VariantSwitchResult {
        object InFlight : VariantSwitchResult()
        object Unavailable : VariantSwitchResult()
        data class Ready(val variantSwitch: VariantSwitch) : VariantSwitchResult()
        data class Error(val message: String) : VariantSwitchResult()
    }

    class VariantSwitch internal constructor(
        val document: TLRPC.Document,
        internal val sourceMessage: TLRPC.Message,
    )

    class Release(val verCode: Int, val changelog: TL_iv.RichMessage)

    private data class ApkInfo(
        val verCode: Int,
        val appVerName: String,
        val document: TLRPC.Document,
    )

    private data class CurrentBuild(
        val versionCode: Int,
    )
}
