package com.g150446.voiceharness

import android.content.Context
import android.content.Intent
import android.icu.text.Transliterator
import android.util.Log
import java.text.Normalizer
import java.util.Locale

internal data class LaunchableApp(val label: String, val packageName: String)

private val SPOKEN_APP_LAUNCH = Regex(
    "^(.{1,20}?)(?:アプリ)?(?:を)?" +
        "(?:開いて|ひらいて|開けて|開く|起動して|起動|立ち上げて|立ち上げ)(?:ください|下さい)?" +
        "$ASR_TAIL_NOISE$",
)

/** Words that describe the app rather than name it ("クロムブラウザ" is still Chrome). */
private val APP_NAME_SUFFIXES = listOf("アプリ", "ブラウザー", "ブラウザ", "browser", "app")

/**
 * The app named by a whole Pilot utterance that only asks to open it ("Chromeブラウザを開いて").
 * Longer sentences ("Chromeの使い方を教えて") are left to the LLM, like spoken mode switches.
 */
internal fun spokenAppLaunchTarget(text: String): String? {
    val compact = collapseRepeatedUtterance(text.replace(Regex("[\\s　、。,.!！?？・:：「」]+"), ""))
    val name = SPOKEN_APP_LAUNCH.matchEntire(compact)?.groupValues?.get(1) ?: return null
    return name.takeIf { normalizeAppName(it).isNotEmpty() }
}

/**
 * ASR sometimes returns a short command twice in a row
 * ("クロムブラウザアプリを開いてクロムブラウザアプリを開いて"); keep one copy.
 */
internal fun collapseRepeatedUtterance(compact: String): String {
    for (times in 3 downTo 2) {
        if (compact.length % times != 0) continue
        val unit = compact.substring(0, compact.length / times)
        if (unit.isNotEmpty() && unit.repeat(times) == compact) return unit
    }
    return compact
}

/**
 * Katakana readings and ASR spellings that never match a Latin label, mapped to the packages
 * they mean. Entries whose package is not installed are ignored.
 */
private val APP_ALIASES: Map<String, List<String>> = mapOf(
    // This app itself: "Voice Harness" shares no consonant skeleton with "boisuhānesu".
    "com.g150446.voiceharness" to listOf("ボイスハーネス", "ヴォイスハーネス", "ハーネス", "ハーネスボイス"),
    "com.android.chrome" to listOf("クロム", "クローム", "グーグルクローム"),
    "ai.x.grok" to listOf("グロック", "グロク", "グロッグ", "glock", "grock"),
    "com.google.android.youtube" to listOf("ユーチューブ", "ようつべ"),
    "com.google.android.gm" to listOf("ジーメール", "gメール", "グーグルメール"),
    "com.google.android.apps.maps" to listOf("マップ", "グーグルマップ", "地図"),
    "jp.naver.line.android" to listOf("ライン"),
    "com.twitter.android" to listOf("エックス", "ツイッター", "twitter"),
    "com.anthropic.claude" to listOf("クロード"),
    "com.openai.chatgpt" to listOf("チャットgpt", "チャットジーピーティー", "チャッピー"),
    "com.google.android.apps.bard" to listOf("ジェミニ", "ジェミナイ"),
    "com.spotify.music" to listOf("スポティファイ"),
    "com.instagram.android" to listOf("インスタ", "インスタグラム"),
    "com.google.android.calendar" to listOf("カレンダー", "グーグルカレンダー"),
).flatMap { (pkg, names) -> names.map { normalizeAppName(it) to pkg } }
    .groupBy({ it.first }, { it.second })

/**
 * NFKC, lower case, hiragana folded to katakana, and only letters and digits kept. The long
 * vowel mark is dropped too: ASR writes 「ボイスハーネース」 as often as 「ボイスハーネス」.
 */
internal fun normalizeAppName(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }
        .filter { it.isLetterOrDigit() && it != 'ー' }
        .joinToString("")

/**
 * App names worth giving the STT model, most useful first: this app, the apps the alias
 * table knows, then the rest of the launcher.
 */
internal fun appSpeechHints(apps: List<LaunchableApp>, selfPackage: String): List<String> {
    val aliasPackages = APP_ALIASES.values.flatten().distinct()
    val rank = { app: LaunchableApp ->
        when {
            app.packageName == selfPackage -> 0
            app.packageName in aliasPackages -> 1
            else -> 2
        }
    }
    return apps.sortedBy(rank).map { it.label.trim() }.filter { it.isNotEmpty() }.distinct()
}

private fun spokenNameVariants(spoken: String): List<String> {
    val variants = mutableListOf(normalizeAppName(spoken))
    var changed = true
    while (changed) {
        changed = false
        val last = variants.last()
        APP_NAME_SUFFIXES.map(::normalizeAppName).firstOrNull { last.endsWith(it) && last != it }
            ?.let {
                variants += last.removeSuffix(it)
                changed = true
            }
    }
    return variants.filter { it.isNotEmpty() }.distinct()
}

/**
 * Finds the installed app the user named, tolerating ASR spellings: exact label, known
 * aliases, romanized katakana sharing the label's [consonantSkeleton] ([toLatin] turns
 * katakana into romaji), then containment.
 */
internal fun matchLaunchableApp(
    spoken: String,
    apps: List<LaunchableApp>,
    toLatin: (String) -> String = { it },
): LaunchableApp? {
    if (apps.isEmpty()) return null
    val variants = spokenNameVariants(spoken)
    if (variants.isEmpty()) return null
    val labeled = apps.map { it to normalizeAppName(it.label) }.filter { it.second.isNotEmpty() }
    val byPackage = apps.associateBy { it.packageName }

    for (name in variants) {
        labeled.firstOrNull { it.second == name }?.let { return it.first }
    }
    for (name in variants) {
        APP_ALIASES[name]?.firstNotNullOfOrNull { byPackage[it] }?.let { return it }
    }
    for (name in variants) {
        val key = consonantSkeleton(toLatin(name))
        if (key.length < 3) continue
        labeled
            .map { (app, _) -> app to editDistance(key, consonantSkeleton(app.label)) }
            .filter { (app, distance) ->
                val labelKey = consonantSkeleton(app.label)
                labelKey.length >= 3 && distance <= (if (labelKey.length >= 5) 1 else 0)
            }
            .minByOrNull { it.second }
            ?.let { return it.first }
    }
    for (name in variants) {
        if (name.length < 3) continue
        labeled
            .filter { (_, label) ->
                label.length >= 3 && (label.contains(name) || name.contains(label))
            }
            .maxByOrNull { it.second.length }
            ?.let { return it.first }
    }
    return null
}

/**
 * Romaji of katakana ("kuromu") and the English spelling ("Chrome") share their consonants
 * once vowels, h/y/w and doubled letters go and l/r, c/k sound alike: both become "krm".
 */
internal fun consonantSkeleton(text: String): String {
    val ascii = Normalizer.normalize(text, Normalizer.Form.NFD)
        .lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' }
    val mapped = ascii.map {
        when (it) {
            'l' -> 'r'
            'c', 'q' -> 'k'
            'v' -> 'b'
            else -> it
        }
    }.filter { it !in "aeiouhyw" }
    return mapped.filterIndexed { i, ch -> i == 0 || mapped[i - 1] != ch }.joinToString("")
}

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        }
        prev = cur
    }
    return prev[b.length]
}

internal object AppLauncher {
    private const val TAG = "AppLauncher"

    private val romanizer: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Katakana-Latin") }.getOrNull()
    }

    fun launchableApps(context: Context): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = context.packageManager
        return pm.queryIntentActivities(intent, 0)
            .map {
                LaunchableApp(
                    label = it.loadLabel(pm).toString(),
                    packageName = it.activityInfo.packageName,
                )
            }
            .distinctBy { it.packageName }
    }

    fun speechHints(context: Context): List<String> =
        runCatching { appSpeechHints(launchableApps(context), context.packageName) }
            .getOrDefault(emptyList())

    fun find(context: Context, spoken: String): LaunchableApp? =
        matchLaunchableApp(spoken, launchableApps(context)) { text ->
            romanizer?.transliterate(text) ?: text
        }

    /** Relies on the overlay permission to start an activity while the app is in background. */
    fun launch(context: Context, app: LaunchableApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "launch ${app.packageName} failed", it) }.isSuccess
    }

    /** The reply shown and spoken for a spoken launch request. */
    fun openSpoken(context: Context, spoken: String): String {
        val app = find(context, spoken) ?: return "「$spoken」というアプリが見つかりませんでした"
        return if (launch(context, app)) "${app.label}を開きました" else "${app.label}を開けませんでした"
    }
}
