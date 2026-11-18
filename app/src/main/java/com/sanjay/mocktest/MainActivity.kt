package com.sanjay.mocktest

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.TextUtils
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

// ==================== 1. GITHUB & ENCRYPTION CONFIGURATION ====================
const val GITHUB_OWNER = "sanjusuthar"
const val GITHUB_REPO = "mock-test-app"
const val GITHUB_BRANCH = "main"

// Note: Client-side obfuscation prevents casual reading on GitHub; true zero-trust secrecy requires a backend server.
private const val QUIZ_SECRET_PASSPHRASE = "SanjayMockTest#2026!SecureKey"

// ==================== 2. CLEAN COACHING UI THEME COLORS ====================
val BgDark = Color(0xFF09090B)
val CardDark = Color(0xFF18181B)
val HomeBgTop = Color(0xFF0F172A)
val HomeBgBot = Color(0xFF020617)
val HomeCard = Color(0xFF1E293B)
val HomeBox = Color(0xFF334155)
val PrimaryRose = Color(0xFFE11D48)
val SecondarySky = Color(0xFF0EA5E9)
val TextMain = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val BorderColor = Color(0xFF27272A)
val OptionBg = Color(0xFF1F2937)
val CorrectGreen = Color(0xFF10B981)
val WrongRed = Color(0xFFEF4444)
val SkippedPurple = Color(0xFF8B5CF6)
val AmberGold = Color(0xFFF59E0B)

// ==================== 3. DATA MODELS ====================
data class Question(
    val topic: String,
    val text: String,
    val options: List<String>,
    val correctAnswer: Int,
    val solution: String
)

data class ParseResult(
    val questions: List<Question>,
    val warnings: List<String>
)

data class TestConfig(
    val id: String = "custom",
    val title: String = "Online Mock Test",
    val subtitle: String = "Target Preparation & Revision Test",
    val examName: String = "RPSC",
    val subject: String = "General",
    val timeMinutes: Int = 60,
    val marksPerCorrect: Double = 2.0,
    val negativeMark: Double = 0.0
)

data class OnlineQuizMeta(
    val id: String,
    val title: String,
    val subtitle: String,
    val examName: String,
    val subject: String,
    val timeMinutes: Int,
    val marksPerCorrect: Double,
    val negativeMark: Double,
    val questionCount: Int,
    val fileUrl: String,
    val repoPath: String,
    val publishedDate: String
)

data class SavedResumeState(
    val config: TestConfig,
    val rawTxt: String,
    val currentQIndex: Int,
    val remainingSeconds: Int,
    val userAnswers: List<Int?>,
    val markedForReview: List<Boolean>
)

data class UserProfile(
    val name: String,
    val targetExam: String,
    val districtOrCity: String,
    val avatarEmoji: String
)

data class CompletedTestResult(
    val attemptNumber: Int,
    val config: TestConfig,
    val userAnswers: List<Int?>,
    val timeSpentSeconds: Int,
    val obtainedMarks: Double,
    val maxMarks: Double,
    val percentage: Double,
    val completedDate: String,
    val completedAtMillis: Long = 0L,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val skippedCount: Int = 0
)

enum class AppScreen { PROFILE_SETUP, HOME, MY_TESTS, SOLUTIONS, SETTINGS, SUBJECT_TESTS, CUSTOM_BUILDER, ADMIN_PORTAL, TEST, RESULT }

// ==================== 4. ANDROID KEYSTORE SECURE STORAGE FOR PAT & ADMIN PIN ====================
object SecureAdminStore {
    private const val KEY_ALIAS = "MockTestAdminKeystoreAlias"
    private const val PREF_NAME = "secure_admin_prefs"
    private const val KEY_ENC_PAT = "enc_pat_v1"
    private const val KEY_PIN_HASH = "admin_pin_hash_v1"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun hasPin(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()
    }

    fun setPin(context: Context, pin: String) {
        val hash = sha256(pin)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PIN_HASH, hash).apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val saved = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PIN_HASH, null) ?: return false
        return saved == sha256(pin)
    }

    fun savePat(context: Context, pat: String) {
        if (pat.isBlank()) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(pat.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENC_PAT, payload).apply()
    }

    fun loadPat(context: Context): String {
        val payload = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENC_PAT, null) ?: return ""
        return try {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            val iv = bytes.copyOfRange(0, 12)
            val ciphertext = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// ==================== 5. CONTENT ENCRYPTION HELPER ====================
object QuizCrypto {
    private fun getAesKey(): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(QUIZ_SECRET_PASSPHRASE.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, getAesKey(), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted
        return "ENCv1:" + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptIfNeeded(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("ENCv1:")) return content
        val combined = Base64.decode(trimmed.removePrefix("ENCv1:"), Base64.DEFAULT)
        val iv = combined.copyOfRange(0, 16)
        val encrypted = combined.copyOfRange(16, combined.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, getAesKey(), IvParameterSpec(iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}

// ==================== 6. STRICT MULTI-LINE TXT QUESTION PARSER ====================
object TxtQuestionParser {
    fun parseWithValidation(rawText: String): ParseResult {
        val parsed = mutableListOf<Question>()
        val warnings = mutableListOf<String>()

        // Normalize all line breaks and split any inline TOPIC: glued onto the end of a SOL: line
        val normalized = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("(?i)(?<!^)(?<!\\n)(?=TOPIC\\s*:)"), "\n")

        var pendingTopic = "General"
        var qTopic = "General"
        var qText = ""
        val options = mutableListOf<String>()
        var answerIndex: Int? = null
        var solution = "व्याख्या उपलब्ध नहीं है।"
        var currentField = "NONE" // TOPIC, Q, OPT, ANS, SOL

        fun flushCurrentQuestion() {
            if (qText.isNotBlank() && options.size >= 2) {
                val cleanSol = solution
                    .replace(Regex("(?i)\\n?TOPIC\\s*:.*$"), "")
                    .replace("<br>", "\n")
                    .trim()
                val idx = answerIndex
                if (idx == null || idx !in options.indices) {
                    warnings.add("Q${parsed.size + 1}: ANS: गलत या गायब है")
                } else {
                    parsed.add(
                        Question(
                            topic = qTopic.trim().ifEmpty { "General" },
                            text = qText.replace("<br>", "\n").trim(),
                            options = options.map { it.trim() },
                            correctAnswer = idx,
                            solution = cleanSol.ifEmpty { "व्याख्या उपलब्ध नहीं है।" }
                        )
                    )
                }
            }
            qText = ""
            options.clear()
            answerIndex = null
            solution = "व्याख्या उपलब्ध नहीं है।"
            currentField = "NONE"
        }

        for (rawLine in normalized.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val topicMatch = Regex("^(?i)TOPIC\\s*:\\s*(.*)").find(line)
            val qMatch = Regex("^(?i)(?:Q|Question|प्र|प्रश्न)\\s*[:.]\\s*(.*)").find(line)
            val optMatch = Regex("^(?i)([A-E])[).:-]\\s*(.*)").find(line)
            val ansMatch = Regex("^(?i)(?:ANS|Answer|उत्तर)\\s*[:.-]\\s*(.*)").find(line)
            val solMatch = Regex("^(?i)(?:SOL|SOLUTION|व्याख्या)\\s*[:.-]\\s*(.*)").find(line)

            when {
                topicMatch != null -> {
                    // Save previous question BEFORE updating pendingTopic for the next question!
                    if (qText.isNotBlank()) {
                        flushCurrentQuestion()
                    }
                    pendingTopic = topicMatch.groupValues[1].trim().ifEmpty { "General" }
                    currentField = "TOPIC"
                }
                qMatch != null -> {
                    // Save previous question if it wasn't flushed yet
                    if (qText.isNotBlank()) {
                        flushCurrentQuestion()
                    }
                    qTopic = pendingTopic
                    qText = qMatch.groupValues[1].trim()
                    currentField = "Q"
                }
                optMatch != null && (currentField == "Q" || currentField == "OPT") -> {
                    options.add(optMatch.groupValues[2].trim())
                    currentField = "OPT"
                }
                ansMatch != null && options.isNotEmpty() -> {
                    val ansToken = ansMatch.groupValues[1].trim().uppercase()
                    val firstChar = ansToken.firstOrNull()?.toString() ?: ""
                    val map = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3, "E" to 4)
                    answerIndex = map[firstChar] ?: ansToken.toIntOrNull()?.minus(1)
                    currentField = "ANS"
                }
                solMatch != null && options.isNotEmpty() -> {
                    solution = solMatch.groupValues[1].trim()
                    currentField = "SOL"
                }
                else -> {
                    when (currentField) {
                        "TOPIC" -> {
                            pendingTopic = "$pendingTopic $line".trim()
                        }
                        "Q" -> {
                            qText = if (qText.isEmpty()) line else "$qText\n$line"
                        }
                        "OPT" -> {
                            if (options.isNotEmpty()) {
                                val lastIdx = options.lastIndex
                                options[lastIdx] = options[lastIdx] + " " + line
                            }
                        }
                        "SOL" -> {
                            solution = if (solution.isEmpty() || solution == "व्याख्या उपलब्ध नहीं है।") line else "$solution\n$line"
                        }
                    }
                }
            }
        }
        if (qText.isNotBlank()) {
            flushCurrentQuestion()
        }

        return ParseResult(parsed, warnings)
    }

    fun parse(rawText: String): List<Question> = parseWithValidation(rawText).questions
}

// ==================== 7. WALL-CLOCK RESUME STORAGE HELPER ====================
object ResumeStorage {
    private const val KEY_RESUME_JSON = "active_resume_state_v2"

    fun save(context: Context, state: SavedResumeState) {
        // Ensure question text is cached once in filesDir/quiz_cache/<id>.enc instead of writing rawTxt to SharedPreferences on every click
        ProfileAndHistoryStorage.saveQuizTextToCache(context, state.config.id, state.rawTxt)

        val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
        val ansArr = JSONArray()
        state.userAnswers.forEach { ans ->
            if (ans == null) ansArr.put(JSONObject.NULL) else ansArr.put(ans)
        }
        val revArr = JSONArray()
        state.markedForReview.forEach { revArr.put(it) }

        val obj = JSONObject().apply {
            put("id", state.config.id)
            put("title", state.config.title)
            put("subtitle", state.config.subtitle)
            put("examName", state.config.examName)
            put("subject", state.config.subject)
            put("timeMinutes", state.config.timeMinutes)
            put("marksPerCorrect", state.config.marksPerCorrect)
            put("negativeMark", state.config.negativeMark)
            put("currentQIndex", state.currentQIndex)
            put("remainingSeconds", state.remainingSeconds)
            put("userAnswers", ansArr)
            put("markedForReview", revArr)
        }
        prefs.edit().putString(KEY_RESUME_JSON, obj.toString()).apply()
    }

    fun load(context: Context): SavedResumeState? {
        val prefs = context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_RESUME_JSON, null) ?: return null
        return try {
            val obj = JSONObject(str)
            val quizId = obj.optString("id", "custom")
            val resolvedRawTxt = obj.optString("rawTxt", "").ifBlank {
                ProfileAndHistoryStorage.loadCachedQuizText(context, quizId) ?: ""
            }
            if (resolvedRawTxt.isBlank()) return null

            val ansArr = obj.getJSONArray("userAnswers")
            val ansList = mutableListOf<Int?>()
            for (i in 0 until ansArr.length()) {
                ansList.add(if (ansArr.isNull(i)) null else ansArr.getInt(i))
            }
            val revArr = obj.optJSONArray("markedForReview")
            val revList = mutableListOf<Boolean>()
            if (revArr != null) {
                for (i in 0 until revArr.length()) revList.add(revArr.getBoolean(i))
            } else {
                repeat(ansList.size) { revList.add(false) }
            }
            SavedResumeState(
                config = TestConfig(
                    id = quizId,
                    title = obj.optString("title", "Mock Test"),
                    subtitle = obj.optString("subtitle", ""),
                    examName = obj.optString("examName", "RPSC"),
                    subject = obj.optString("subject", "General"),
                    timeMinutes = obj.optInt("timeMinutes", 60),
                    marksPerCorrect = obj.optDouble("marksPerCorrect", 2.0),
                    negativeMark = obj.optDouble("negativeMark", 0.0)
                ),
                rawTxt = resolvedRawTxt,
                currentQIndex = obj.optInt("currentQIndex", 0),
                remainingSeconds = obj.optInt("remainingSeconds", 60),
                userAnswers = ansList,
                markedForReview = revList
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE)
            .edit().remove(KEY_RESUME_JSON).apply()
    }
}

// ==================== 7B. STUDENT PROFILE & MULTI-ATTEMPT LIGHTWEIGHT RESULT STORAGE ====================
object ProfileAndHistoryStorage {
    private const val PREF_NAME = "mock_test_prefs"
    private const val KEY_PROFILE = "user_profile_v1"
    private const val KEY_ATTEMPTS_MAP = "completed_attempts_map_v2"
    private const val LEGACY_KEY_RESULTS_MAP = "completed_results_map_v1"

    fun loadProfile(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILE, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) return null
            UserProfile(
                name = name,
                targetExam = obj.optString("targetExam", "RPSC / UGC NET"),
                districtOrCity = obj.optString("districtOrCity", "Rajasthan"),
                avatarEmoji = obj.optString("avatarEmoji", "🎓")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveProfile(context: Context, profile: UserProfile) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val obj = JSONObject().apply {
            put("name", profile.name.trim())
            put("targetExam", profile.targetExam.trim())
            put("districtOrCity", profile.districtOrCity.trim())
            put("avatarEmoji", profile.avatarEmoji)
        }
        prefs.edit()
            .putString(KEY_PROFILE, obj.toString())
            .putString("student_name", profile.name.trim())
            .apply()
    }

    fun customQuizIdForText(rawTxt: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawTxt.trim().toByteArray(Charsets.UTF_8))
        val hex = digest.take(6).joinToString("") { "%02x".format(it) }
        return "custom_$hex"
    }

    // Cache question text in filesDir/quiz_cache/<id>.enc so SharedPreferences stays tiny and fast!
    fun saveQuizTextToCache(context: Context, quizId: String, plainTxt: String) {
        if (plainTxt.isBlank()) return
        try {
            val cacheDir = File(context.filesDir, "quiz_cache").apply { mkdirs() }
            val cacheFile = File(cacheDir, "$quizId.enc")
            if (!cacheFile.exists() || quizId == "custom") {
                cacheFile.writeText(QuizCrypto.encrypt(plainTxt), Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }
    }

    fun loadCachedQuizText(context: Context, quizId: String): String? {
        return try {
            val cacheFile = File(File(context.filesDir, "quiz_cache"), "$quizId.enc")
            if (cacheFile.exists()) {
                QuizCrypto.decryptIfNeeded(cacheFile.readText(Charsets.UTF_8))
            } else if (quizId.startsWith("custom")) {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                prefs.getString("saved_custom_txt", null)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveCompletedAttempt(
        context: Context,
        config: TestConfig,
        rawTxt: String,
        userAnswers: List<Int?>,
        timeSpentSeconds: Int,
        obtainedMarks: Double,
        maxMarks: Double,
        percentage: Double,
        completedDate: String,
        correctCount: Int,
        wrongCount: Int,
        skippedCount: Int
    ) {
        saveQuizTextToCache(context, config.id, rawTxt)

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existingRaw = prefs.getString(KEY_ATTEMPTS_MAP, "{}") ?: "{}"
        val rootObj = try { JSONObject(existingRaw) } catch (_: Exception) { JSONObject() }

        val historyArr = rootObj.optJSONArray(config.id) ?: JSONArray()
        val nextAttemptNumber = historyArr.length() + 1

        val ansArr = JSONArray()
        userAnswers.forEach { a ->
            if (a == null) ansArr.put(JSONObject.NULL) else ansArr.put(a)
        }

        // Lightweight JSON entry (NO rawTxt stored in SharedPreferences!)
        val entry = JSONObject().apply {
            put("attemptNumber", nextAttemptNumber)
            put("id", config.id)
            put("title", config.title)
            put("subtitle", config.subtitle)
            put("examName", config.examName)
            put("subject", config.subject)
            put("timeMinutes", config.timeMinutes)
            put("marksPerCorrect", config.marksPerCorrect)
            put("negativeMark", config.negativeMark)
            put("userAnswers", ansArr)
            put("timeSpentSeconds", timeSpentSeconds)
            put("obtainedMarks", obtainedMarks)
            put("maxMarks", maxMarks)
            put("percentage", percentage)
            put("completedDate", completedDate)
            put("completedAtMillis", System.currentTimeMillis())
            put("correctCount", correctCount)
            put("wrongCount", wrongCount)
            put("skippedCount", skippedCount)
        }

        historyArr.put(entry)
        rootObj.put(config.id, historyArr)
        prefs.edit()
            .putString(KEY_ATTEMPTS_MAP, rootObj.toString())
            .remove(LEGACY_KEY_RESULTS_MAP)
            .apply()
    }

    fun loadAllAttemptsHistory(context: Context): Map<String, List<CompletedTestResult>> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val map = mutableMapOf<String, List<CompletedTestResult>>()

        // Migrate any v1 single-result entries seamlessly if present
        val legacyRaw = prefs.getString(LEGACY_KEY_RESULTS_MAP, null)
        if (!legacyRaw.isNullOrBlank()) {
            try {
                val legacyRoot = JSONObject(legacyRaw)
                val v2Root = JSONObject(prefs.getString(KEY_ATTEMPTS_MAP, "{}") ?: "{}")
                val keys = legacyRoot.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val obj = legacyRoot.optJSONObject(k) ?: continue
                    val oldTxt = obj.optString("rawTxt", "")
                    if (oldTxt.isNotBlank()) saveQuizTextToCache(context, k, oldTxt)
                    obj.remove("rawTxt")
                    obj.put("attemptNumber", 1)
                    if (!v2Root.has(k)) {
                        v2Root.put(k, JSONArray().put(obj))
                    }
                }
                prefs.edit()
                    .putString(KEY_ATTEMPTS_MAP, v2Root.toString())
                    .remove(LEGACY_KEY_RESULTS_MAP)
                    .apply()
            } catch (_: Exception) {
            }
        }

        val existingRaw = prefs.getString(KEY_ATTEMPTS_MAP, "{}") ?: "{}"
        try {
            val rootObj = JSONObject(existingRaw)
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = rootObj.optJSONArray(key) ?: continue
                val attemptList = mutableListOf<CompletedTestResult>()
                for (j in 0 until arr.length()) {
                    val obj = arr.optJSONObject(j) ?: continue
                    val ansArr = obj.optJSONArray("userAnswers") ?: JSONArray()
                    val ansList = mutableListOf<Int?>()
                    for (i in 0 until ansArr.length()) {
                        ansList.add(if (ansArr.isNull(i)) null else ansArr.getInt(i))
                    }
                    val cfg = TestConfig(
                        id = obj.optString("id", key),
                        title = obj.optString("title", "Mock Test"),
                        subtitle = obj.optString("subtitle", ""),
                        examName = obj.optString("examName", "RPSC"),
                        subject = obj.optString("subject", "General"),
                        timeMinutes = obj.optInt("timeMinutes", 60),
                        marksPerCorrect = obj.optDouble("marksPerCorrect", 2.0),
                        negativeMark = obj.optDouble("negativeMark", 0.0)
                    )
                    attemptList.add(
                        CompletedTestResult(
                            attemptNumber = obj.optInt("attemptNumber", j + 1),
                            config = cfg,
                            userAnswers = ansList,
                            timeSpentSeconds = obj.optInt("timeSpentSeconds", 0),
                            obtainedMarks = obj.optDouble("obtainedMarks", 0.0),
                            maxMarks = obj.optDouble("maxMarks", 0.0),
                            percentage = obj.optDouble("percentage", 0.0),
                            completedDate = obj.optString("completedDate", ""),
                            completedAtMillis = obj.optLong("completedAtMillis", 0L),
                            correctCount = obj.optInt("correctCount", ansList.count { it != null }),
                            wrongCount = obj.optInt("wrongCount", 0),
                            skippedCount = obj.optInt("skippedCount", ansList.count { it == null })
                        )
                    )
                }
                if (attemptList.isNotEmpty()) {
                    map[key] = attemptList
                }
            }
        } catch (_: Exception) {
        }
        return map
    }

    fun clearAllStudentData(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_ATTEMPTS_MAP)
            .remove(LEGACY_KEY_RESULTS_MAP)
            .remove("saved_custom_txt")
            .apply()
        ResumeStorage.clear(context)
    }

    fun logoutProfile(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PROFILE)
            .remove("student_name")
            .apply()
    }
}

// ==================== 8. GITHUB CLOUD SYNC (WITH OFFLINE CACHE & HTTP CHECKS) ====================
object GitHubCloudService {
    suspend fun fetchOnlineQuizzes(context: Context): List<OnlineQuizMeta> = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "quiz_cache").apply { mkdirs() }
        val indexCacheFile = File(cacheDir, "cached_index.json")

        val jsonText: String = try {
            val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/quizzes/index.json?ref=$GITHUB_BRANCH&t=${System.currentTimeMillis()}"
            val apiConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                useCaches = false
                setRequestProperty("Accept", "application/vnd.github.raw+json")
                setRequestProperty("Cache-Control", "no-cache")
            }

            val fetched = if (apiConn.responseCode == 200) {
                apiConn.inputStream.bufferedReader().use { it.readText() }
            } else {
                val rawUrl = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/quizzes/index.json?t=${System.currentTimeMillis()}"
                val rawConn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (rawConn.responseCode != 200) throw Exception("HTTP ${rawConn.responseCode}")
                rawConn.inputStream.bufferedReader().use { it.readText() }
            }
            indexCacheFile.writeText(fetched, Charsets.UTF_8)
            fetched
        } catch (_: Exception) {
            if (indexCacheFile.exists()) {
                indexCacheFile.readText(Charsets.UTF_8)
            } else {
                return@withContext emptyList()
            }
        }

        val arr = JSONArray(jsonText)
        val list = mutableListOf<OnlineQuizMeta>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val rawSubject = obj.optString("subject", "").trim()
            val rawTitle = obj.optString("title", "Mock Test")
            val rawExam = obj.optString("examName", "").trim()
            // Legacy tests without examName: if subject or title mentions CTET -> "CTET", otherwise default to "RPSC"!
            val resolvedExam = when {
                rawExam.isNotEmpty() -> rawExam
                rawSubject.contains("CTET", ignoreCase = true) || rawTitle.contains("CTET", ignoreCase = true) -> "CTET"
                else -> "RPSC"
            }
            val fileUrl = obj.optString("fileUrl", "")
            val derivedPath = obj.optString("repoPath", "").ifBlank {
                val marker = "/$GITHUB_BRANCH/"
                if (fileUrl.contains(marker)) fileUrl.substringAfter(marker) else ""
            }
            list.add(
                OnlineQuizMeta(
                    id = obj.optString("id", "quiz_$i"),
                    title = rawTitle,
                    subtitle = obj.optString("subtitle", "Online Practice Test"),
                    examName = resolvedExam,
                    subject = if (rawSubject.isNotEmpty()) rawSubject else "General Tests",
                    timeMinutes = obj.optInt("timeMinutes", 60),
                    marksPerCorrect = obj.optDouble("marksPerCorrect", 2.0),
                    negativeMark = obj.optDouble("negativeMark", 0.0),
                    questionCount = obj.optInt("questionCount", 0),
                    fileUrl = fileUrl,
                    repoPath = derivedPath,
                    publishedDate = obj.optString("publishedDate", "")
                )
            )
        }
        // Sort chronologically by creation timestamp in id (quiz_<timestamp>) so Unit 1 stays at the TOP and Unit 2, 3 follow below!
        list.sortedBy { meta ->
            meta.id.substringAfter("quiz_", "").toLongOrNull() ?: 0L
        }
    }

    suspend fun downloadAndDecryptQuizText(context: Context, meta: OnlineQuizMeta): String = withContext(Dispatchers.IO) {
        val cacheDir = File(context.filesDir, "quiz_cache").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${meta.id}.enc")

        try {
            val sep = if (meta.fileUrl.contains("?")) "&" else "?"
            val conn = (URL("${meta.fileUrl}${sep}t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                useCaches = false
            }
            if (conn.responseCode != 200) {
                throw Exception("Server HTTP ${conn.responseCode}")
            }
            val rawDownloaded = conn.inputStream.bufferedReader().use { it.readText() }
            cacheFile.writeText(rawDownloaded, Charsets.UTF_8)
            QuizCrypto.decryptIfNeeded(rawDownloaded)
        } catch (e: Exception) {
            if (cacheFile.exists()) {
                QuizCrypto.decryptIfNeeded(cacheFile.readText(Charsets.UTF_8))
            } else {
                throw e
            }
        }
    }

    suspend fun publishEncryptedQuizToGitHub(
        token: String,
        title: String,
        subtitle: String,
        examName: String,
        subject: String,
        timeMinutes: Int,
        marks: Double,
        neg: Double,
        rawTxtContent: String,
        questionCount: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safeExam = examName.trim()
                .lowercase(Locale.ENGLISH)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifEmpty { "rpsc" }
            val safeFolder = subject.trim()
                .lowercase(Locale.ENGLISH)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifEmpty { "general" }

            val fileId = "quiz_${System.currentTimeMillis()}"
            val encPath = "quizzes/$safeExam/$safeFolder/$fileId.enc"
            val encryptedPayload = QuizCrypto.encrypt(rawTxtContent)

            putFileToGitHub(
                token = token,
                path = encPath,
                content = encryptedPayload,
                commitMsg = "Publish encrypted [$subject] quiz: $title"
            )

            val indexPath = "quizzes/index.json"
            val existingFile = getFileFromGitHub(token, indexPath)
            val currentArray = if (existingFile != null) JSONArray(existingFile.first) else JSONArray()

            val rawFileUrl = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/$encPath"
            val today = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())

            val newEntry = JSONObject().apply {
                put("id", fileId)
                put("title", title)
                put("subtitle", subtitle)
                put("examName", examName.trim().ifEmpty { "RPSC" })
                put("subject", subject.trim().ifEmpty { "General Tests" })
                put("timeMinutes", timeMinutes)
                put("marksPerCorrect", marks)
                put("negativeMark", neg)
                put("questionCount", questionCount)
                put("fileUrl", rawFileUrl)
                put("repoPath", encPath)
                put("publishedDate", today)
            }

            // Append new test after existing tests so Unit 1 remains at the top and new units follow in order
            val updatedArray = JSONArray()
            for (i in 0 until currentArray.length()) {
                updatedArray.put(currentArray.getJSONObject(i))
            }
            updatedArray.put(newEntry)

            putFileToGitHub(
                token = token,
                path = indexPath,
                content = updatedArray.toString(2),
                commitMsg = "Update index.json: $subject - $title",
                sha = existingFile?.second
            )

            Result.success("टेस्ट [$examName ➔ $subject] फोल्डर में पब्लिश हो गया!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteQuizFromGitHub(token: String, quiz: OnlineQuizMeta): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Delete encrypted quiz file first so no orphan file remains if index update succeeds
            if (quiz.repoPath.isNotBlank()) {
                deleteFileFromGitHub(token, quiz.repoPath, "Remove file for ${quiz.title}")
            }

            val indexPath = "quizzes/index.json"
            val existingIndex = getFileFromGitHub(token, indexPath)
                ?: return@withContext Result.failure(Exception("index.json नहीं मिला"))

            val currentArray = JSONArray(existingIndex.first)
            val updatedArray = JSONArray()
            for (i in 0 until currentArray.length()) {
                val obj = currentArray.getJSONObject(i)
                if (obj.optString("id") != quiz.id) {
                    updatedArray.put(obj)
                }
            }

            putFileToGitHub(
                token = token,
                path = indexPath,
                content = updatedArray.toString(2),
                commitMsg = "Delete quiz: ${quiz.title}",
                sha = existingIndex.second
            )

            Result.success("'${quiz.title}' डिलीट हो गया!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSubjectFolderFromGitHub(
        token: String,
        subjectName: String,
        quizzesInFolder: List<OnlineQuizMeta>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Delete individual quiz files first before removing entries from index.json
            quizzesInFolder.forEach { q ->
                if (q.repoPath.isNotBlank()) {
                    try {
                        deleteFileFromGitHub(token, q.repoPath, "Cleanup $subjectName")
                    } catch (_: Exception) {
                    }
                }
            }

            val indexPath = "quizzes/index.json"
            val existingIndex = getFileFromGitHub(token, indexPath)
                ?: return@withContext Result.failure(Exception("index.json नहीं मिला"))

            val idsToRemove = quizzesInFolder.map { it.id }.toSet()
            val currentArray = JSONArray(existingIndex.first)
            val updatedArray = JSONArray()
            for (i in 0 until currentArray.length()) {
                val obj = currentArray.getJSONObject(i)
                if (obj.optString("id") !in idsToRemove) {
                    updatedArray.put(obj)
                }
            }

            putFileToGitHub(
                token = token,
                path = indexPath,
                content = updatedArray.toString(2),
                commitMsg = "Delete subject folder: $subjectName",
                sha = existingIndex.second
            )

            Result.success("फोल्डर '$subjectName' और इसके सभी टेस्ट डिलीट हो गए!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getFileFromGitHub(token: String, path: String): Pair<String, String>? {
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$path?ref=$GITHUB_BRANCH"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode != 200) return null
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val sha = json.getString("sha")
            val encoded = json.getString("content").replace("\n", "")
            val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            Pair(decoded, sha)
        } finally {
            conn.disconnect()
        }
    }

    private fun putFileToGitHub(
        token: String,
        path: String,
        content: String,
        commitMsg: String,
        sha: String? = null
    ) {
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$path"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val base64Content = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("message", commitMsg)
                put("content", base64Content)
                put("branch", GITHUB_BRANCH)
                if (sha != null) put("sha", sha)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..201) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw Exception("GitHub API Error ($code): $err")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun deleteFileFromGitHub(token: String, path: String, commitMsg: String) {
        val fileInfo = getFileFromGitHub(token, path) ?: return
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$path"
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().apply {
                put("message", commitMsg)
                put("sha", fileInfo.second)
                put("branch", GITHUB_BRANCH)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..204 && code != 404) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw Exception("GitHub Delete Error ($code): $err")
            }
        } finally {
            conn.disconnect()
        }
    }
}

// ==================== 9. MAIN ACTIVITY ====================
fun normalizeExamCode(raw: String): String {
    val upper = raw.uppercase(Locale.ENGLISH)
    return when {
        upper.contains("RPSC") -> "RPSC"
        upper.contains("RSSB") -> "RSSB"
        upper.contains("CTET") -> "CTET"
        upper.contains("NET") -> "UGC NET"
        else -> raw.substringBefore(" (").trim().ifEmpty { "RPSC" }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LaunchedEffect(Unit) {
                window.setBackgroundDrawable(ColorDrawable(0xFF09090B.toInt()))
            }
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PrimaryRose,
                    secondary = SecondarySky,
                    background = BgDark,
                    surface = CardDark
                )
            ) {
                MockTestAppRoot()
            }
        }
    }
}

@Composable
fun MockTestAppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE) }

    val initialProfile = remember { ProfileAndHistoryStorage.loadProfile(context) }
    var userProfile by remember { mutableStateOf(initialProfile) }
    var currentScreen by remember {
        mutableStateOf(if (initialProfile == null) AppScreen.PROFILE_SETUP else AppScreen.HOME)
    }
    var previousScreenBeforeTest by remember { mutableStateOf(AppScreen.HOME) }
    var previousScreenBeforeSubject by remember { mutableStateOf(AppScreen.HOME) }
    var selectedExamFilter by remember {
        mutableStateOf(normalizeExamCode(initialProfile?.targetExam ?: "RPSC"))
    }
    var selectedSubjectFolder by remember { mutableStateOf("") }

    var testConfig by remember { mutableStateOf(TestConfig()) }
    var activeRawTxt by remember { mutableStateOf("") }
    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var candidateName by remember {
        mutableStateOf(initialProfile?.name ?: prefs.getString("student_name", "") ?: "")
    }
    var hasCustomSavedQuiz by remember { mutableStateOf(false) }

    var savedResumeState by remember { mutableStateOf<SavedResumeState?>(null) }
    var completedResultsMap by remember {
        mutableStateOf(ProfileAndHistoryStorage.loadAllAttemptsHistory(context))
    }

    // Test Active State
    var currentQIndex by remember { mutableStateOf(0) }
    var userAnswers by remember { mutableStateOf<List<Int?>>(emptyList()) }
    var markedForReview by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var remainingSeconds by remember { mutableStateOf(0) }
    var resultTimeSpentSeconds by remember { mutableStateOf(0) }

    var showPaletteSheet by remember { mutableStateOf(false) }
    var showSubmitConfirm by remember { mutableStateOf(false) }
    var showExitTestConfirm by remember { mutableStateOf(false) }

    // Modals
    var showPinGateModal by remember { mutableStateOf(false) }
    var showNamePromptDialog by remember { mutableStateOf(false) }
    var pendingQuizToStart by remember { mutableStateOf<OnlineQuizMeta?>(null) }

    // Online Quizzes State
    var onlineQuizzes by remember { mutableStateOf<List<OnlineQuizMeta>>(emptyList()) }
    var isLoadingOnline by remember { mutableStateOf(false) }
    var isDownloadingQuiz by remember { mutableStateOf(false) }

    var lastBackPressTime by remember { mutableStateOf(0L) }

    fun persistActiveProgress() {
        if (currentScreen == AppScreen.TEST && activeRawTxt.isNotBlank() && questions.isNotEmpty()) {
            val state = SavedResumeState(
                config = testConfig,
                rawTxt = activeRawTxt,
                currentQIndex = currentQIndex,
                remainingSeconds = remainingSeconds,
                userAnswers = userAnswers,
                markedForReview = markedForReview
            )
            ResumeStorage.save(context, state)
            savedResumeState = state
        }
    }

    fun finishAndSubmitExam() {
        if (currentScreen == AppScreen.RESULT) return
        val spent = ((testConfig.timeMinutes * 60) - remainingSeconds).coerceAtLeast(0)
        resultTimeSpentSeconds = spent

        var correct = 0
        var wrong = 0
        var skipped = 0
        questions.forEachIndexed { idx, q ->
            val ans = userAnswers.getOrNull(idx)
            if (ans == null) skipped++
            else if (ans == q.correctAnswer) correct++
            else wrong++
        }
        val obtained = (correct * testConfig.marksPerCorrect) - (wrong * testConfig.negativeMark)
        val maxM = questions.size * testConfig.marksPerCorrect
        val pct = if (maxM > 0) ((obtained / maxM) * 100.0).coerceIn(0.0, 100.0) else 0.0
        val today = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())

        if (activeRawTxt.isNotBlank() && questions.isNotEmpty()) {
            ProfileAndHistoryStorage.saveCompletedAttempt(
                context = context,
                config = testConfig,
                rawTxt = activeRawTxt,
                userAnswers = userAnswers,
                timeSpentSeconds = spent,
                obtainedMarks = obtained,
                maxMarks = maxM,
                percentage = pct,
                completedDate = today,
                correctCount = correct,
                wrongCount = wrong,
                skippedCount = skipped
            )
            completedResultsMap = ProfileAndHistoryStorage.loadAllAttemptsHistory(context)
        }

        ResumeStorage.clear(context)
        savedResumeState = null
        currentScreen = AppScreen.RESULT
    }

    BackHandler(enabled = true) {
        when (currentScreen) {
            AppScreen.TEST -> {
                if (showPaletteSheet) showPaletteSheet = false
                else showExitTestConfirm = true
            }
            AppScreen.RESULT -> currentScreen = previousScreenBeforeTest
            AppScreen.SUBJECT_TESTS -> currentScreen = previousScreenBeforeSubject
            AppScreen.MY_TESTS -> currentScreen = AppScreen.HOME
            AppScreen.SOLUTIONS -> currentScreen = AppScreen.HOME
            AppScreen.SETTINGS -> currentScreen = AppScreen.HOME
            AppScreen.CUSTOM_BUILDER -> currentScreen = AppScreen.HOME
            AppScreen.ADMIN_PORTAL -> currentScreen = AppScreen.HOME
            AppScreen.PROFILE_SETUP -> {
                if (userProfile != null) {
                    currentScreen = AppScreen.HOME
                } else {
                    (context as? ComponentActivity)?.finish()
                }
            }
            AppScreen.HOME -> {
                when {
                    showPinGateModal -> showPinGateModal = false
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < 2000L) {
                            (context as? ComponentActivity)?.finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(context, "ऐप बंद करने के लिए दोबारा Back दबाएं", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    fun launchOnlineQuiz(meta: OnlineQuizMeta) {
        if (candidateName.isBlank()) {
            pendingQuizToStart = meta
            showNamePromptDialog = true
            return
        }
        prefs.edit().putString("student_name", candidateName.trim()).apply()
        scope.launch {
            isDownloadingQuiz = true
            try {
                val decryptedTxt = GitHubCloudService.downloadAndDecryptQuizText(context, meta)
                val parsed = TxtQuestionParser.parse(decryptedTxt)
                if (parsed.isNotEmpty()) {
                    activeRawTxt = decryptedTxt
                    questions = parsed
                    testConfig = TestConfig(
                        id = meta.id,
                        title = meta.title,
                        subtitle = meta.subtitle,
                        examName = meta.examName,
                        subject = meta.subject,
                        timeMinutes = meta.timeMinutes,
                        marksPerCorrect = meta.marksPerCorrect,
                        negativeMark = meta.negativeMark
                    )
                    userAnswers = List(parsed.size) { null }
                    markedForReview = List(parsed.size) { false }
                    currentQIndex = 0
                    remainingSeconds = meta.timeMinutes * 60
                    previousScreenBeforeTest = currentScreen
                    currentScreen = AppScreen.TEST
                    persistActiveProgress()
                } else {
                    Toast.makeText(context, "टेस्ट फाइल में कोई मान्य प्रश्न नहीं मिला।", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "टेस्ट लोड नहीं हो सका: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isDownloadingQuiz = false
            }
        }
    }

    fun openSavedResult(saved: CompletedTestResult, fromScreen: AppScreen) {
        val cachedTxt = ProfileAndHistoryStorage.loadCachedQuizText(context, saved.config.id)
        val parsed = if (!cachedTxt.isNullOrBlank()) TxtQuestionParser.parse(cachedTxt) else emptyList()
        if (parsed.isNotEmpty() && cachedTxt != null) {
            testConfig = saved.config
            activeRawTxt = cachedTxt
            questions = parsed
            userAnswers = if (saved.userAnswers.size == parsed.size) saved.userAnswers else List(parsed.size) { null }
            resultTimeSpentSeconds = saved.timeSpentSeconds
            previousScreenBeforeTest = fromScreen
            currentScreen = AppScreen.RESULT
        } else {
            Toast.makeText(context, "रिजल्ट डेटा कैश में उपलब्ध नहीं है, कृपया एक बार Re-Test दें।", Toast.LENGTH_SHORT).show()
        }
    }

    fun printSavedResultDirectly(saved: CompletedTestResult) {
        val cachedTxt = ProfileAndHistoryStorage.loadCachedQuizText(context, saved.config.id)
        val parsed = if (!cachedTxt.isNullOrBlank()) TxtQuestionParser.parse(cachedTxt) else emptyList()
        if (parsed.isNotEmpty()) {
            val answers = if (saved.userAnswers.size == parsed.size) saved.userAnswers else List(parsed.size) { null }
            printTwoColumnA4ResultPdf(
                context = context,
                config = saved.config,
                studentName = candidateName.ifBlank { "Student" },
                questions = parsed,
                userAnswers = answers
            )
        } else {
            Toast.makeText(context, "PDF के लिए प्रश्न कैश में उपलब्ध नहीं हैं, कृपया एक बार टेस्ट खोलें।", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        // Cleanup legacy unencrypted token if present from earlier versions
        prefs.edit().remove("gh_pat_token").apply()

        savedResumeState = ResumeStorage.load(context)
        completedResultsMap = ProfileAndHistoryStorage.loadAllAttemptsHistory(context)

        val savedTxt = prefs.getString("saved_custom_txt", null)
        if (!savedTxt.isNullOrBlank()) {
            val parsed = TxtQuestionParser.parse(savedTxt)
            if (parsed.isNotEmpty()) {
                hasCustomSavedQuiz = true
                if (savedResumeState == null) {
                    questions = parsed
                    activeRawTxt = savedTxt
                    testConfig = TestConfig(
                        id = ProfileAndHistoryStorage.customQuizIdForText(savedTxt),
                        title = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                        examName = prefs.getString("saved_exam", "RPSC") ?: "RPSC",
                        subject = prefs.getString("saved_subject", "General") ?: "General",
                        timeMinutes = prefs.getInt("saved_time", 60),
                        marksPerCorrect = prefs.getFloat("saved_marks", 2.0f).toDouble(),
                        negativeMark = prefs.getFloat("saved_neg", 0.0f).toDouble()
                    )
                }
            }
        }

        isLoadingOnline = true
        try {
            onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes(context)
        } catch (_: Exception) {
        } finally {
            isLoadingOnline = false
        }
    }

    // Countdown Timer (Pauses when app is closed or exited)
    LaunchedEffect(currentScreen, remainingSeconds) {
        if (currentScreen == AppScreen.TEST && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
            if (remainingSeconds % 5 == 0) {
                persistActiveProgress()
            }
            if (remainingSeconds <= 0) {
                finishAndSubmitExam()
            }
        }
    }

    val groupedBySubject = remember(onlineQuizzes) {
        onlineQuizzes.groupBy { it.subject }
    }
    val targetExamCode = remember(userProfile) {
        normalizeExamCode(userProfile?.targetExam ?: "RPSC")
    }
    val activeExamSubjectsMap = remember(onlineQuizzes, selectedExamFilter) {
        onlineQuizzes
            .filter { normalizeExamCode(it.examName).equals(normalizeExamCode(selectedExamFilter), ignoreCase = true) }
            .groupBy { it.subject }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        when (currentScreen) {
            AppScreen.PROFILE_SETUP -> {
                ProfileSetupScreenUI(
                    existingProfile = userProfile,
                    onSaveProfile = { newProfile ->
                        ProfileAndHistoryStorage.saveProfile(context, newProfile)
                        userProfile = newProfile
                        candidateName = newProfile.name
                        selectedExamFilter = normalizeExamCode(newProfile.targetExam)
                        currentScreen = AppScreen.HOME
                    }
                )
            }

            AppScreen.HOME -> {
                HomeScreenUI(
                    userProfile = userProfile ?: UserProfile(
                        name = candidateName.ifBlank { "Student" },
                        targetExam = "RPSC • RSSB • CTET • UGC NET",
                        districtOrCity = "Rajasthan",
                        avatarEmoji = "🎓"
                    ),
                    onEditProfile = { currentScreen = AppScreen.PROFILE_SETUP },
                    savedResumeState = savedResumeState,
                    onResumeTest = { state ->
                        val parsed = TxtQuestionParser.parse(state.rawTxt)
                        if (parsed.isNotEmpty()) {
                            testConfig = state.config
                            activeRawTxt = state.rawTxt
                            questions = parsed
                            currentQIndex = state.currentQIndex.coerceIn(0, parsed.size - 1)
                            remainingSeconds = state.remainingSeconds.coerceAtLeast(1)
                            userAnswers = if (state.userAnswers.size == parsed.size) state.userAnswers else List(parsed.size) { null }
                            markedForReview = if (state.markedForReview.size == parsed.size) state.markedForReview else List(parsed.size) { false }
                            previousScreenBeforeTest = AppScreen.HOME
                            currentScreen = AppScreen.TEST
                        }
                    },
                    onDiscardResume = {
                        ResumeStorage.clear(context)
                        savedResumeState = null
                    },
                    selectedExamFilter = selectedExamFilter,
                    onSelectExamCategory = { examCode ->
                        selectedExamFilter = examCode
                        currentScreen = AppScreen.MY_TESTS
                    },
                    groupedSubjects = activeExamSubjectsMap,
                    allOnlineQuizzes = onlineQuizzes,
                    completedResultsMap = completedResultsMap,
                    totalOnlineTests = onlineQuizzes.size,
                    isLoadingOnline = isLoadingOnline,
                    hasCustomSavedQuiz = hasCustomSavedQuiz,
                    customQuizTitle = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                    customQuizCount = TxtQuestionParser.parse(prefs.getString("saved_custom_txt", "") ?: "").size,
                    onRefreshOnline = {
                        scope.launch {
                            isLoadingOnline = true
                            try {
                                onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes(context)
                            } catch (_: Exception) {
                            }
                            isLoadingOnline = false
                        }
                    },
                    onOpenSubjectFolder = { subjectName ->
                        selectedSubjectFolder = subjectName
                        previousScreenBeforeSubject = AppScreen.HOME
                        currentScreen = AppScreen.SUBJECT_TESTS
                    },
                    onOpenMyTestsScreen = {
                        selectedExamFilter = targetExamCode
                        currentScreen = AppScreen.MY_TESTS
                    },
                    onOpenLatestResult = { saved ->
                        openSavedResult(saved, AppScreen.HOME)
                    },
                    onStartCustomQuiz = {
                        val savedTxt = prefs.getString("saved_custom_txt", "") ?: ""
                        val parsed = TxtQuestionParser.parse(savedTxt)
                        if (parsed.isNotEmpty()) {
                            activeRawTxt = savedTxt
                            questions = parsed
                            val tMins = prefs.getInt("saved_time", 60)
                            testConfig = TestConfig(
                                id = ProfileAndHistoryStorage.customQuizIdForText(savedTxt),
                                title = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                                examName = prefs.getString("saved_exam", "RPSC") ?: "RPSC",
                                subject = prefs.getString("saved_subject", "General") ?: "General",
                                timeMinutes = tMins,
                                marksPerCorrect = prefs.getFloat("saved_marks", 2.0f).toDouble(),
                                negativeMark = prefs.getFloat("saved_neg", 0.0f).toDouble()
                            )
                            userAnswers = List(parsed.size) { null }
                            markedForReview = List(parsed.size) { false }
                            currentQIndex = 0
                            remainingSeconds = tMins * 60
                            previousScreenBeforeTest = AppScreen.HOME
                            currentScreen = AppScreen.TEST
                            persistActiveProgress()
                        }
                    },
                    onOpenCustomModal = { currentScreen = AppScreen.CUSTOM_BUILDER },
                    onOpenSolutionsScreen = { currentScreen = AppScreen.SOLUTIONS },
                    onOpenSettingsScreen = { currentScreen = AppScreen.SETTINGS },
                    onOpenAdminModal = { showPinGateModal = true }
                )
            }

            AppScreen.SOLUTIONS -> {
                SolutionsAndPdfScreenUI(
                    userProfile = userProfile ?: UserProfile(
                        name = candidateName.ifBlank { "Student" },
                        targetExam = "RPSC",
                        districtOrCity = "Rajasthan",
                        avatarEmoji = "🎓"
                    ),
                    allOnlineQuizzes = onlineQuizzes,
                    completedResultsMap = completedResultsMap,
                    savedResumeState = savedResumeState,
                    isLoadingOnline = isLoadingOnline,
                    onRefreshOnline = {
                        scope.launch {
                            isLoadingOnline = true
                            try {
                                onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes(context)
                            } catch (_: Exception) {
                            }
                            isLoadingOnline = false
                        }
                    },
                    onOpenSavedResult = { saved ->
                        openSavedResult(saved, AppScreen.SOLUTIONS)
                    },
                    onPrintSavedResultPdf = { saved ->
                        printSavedResultDirectly(saved)
                    },
                    onResumeInterruptedTest = { state ->
                        val parsed = TxtQuestionParser.parse(state.rawTxt)
                        if (parsed.isNotEmpty()) {
                            testConfig = state.config
                            activeRawTxt = state.rawTxt
                            questions = parsed
                            currentQIndex = state.currentQIndex.coerceIn(0, parsed.size - 1)
                            remainingSeconds = state.remainingSeconds.coerceAtLeast(1)
                            userAnswers = if (state.userAnswers.size == parsed.size) state.userAnswers else List(parsed.size) { null }
                            markedForReview = if (state.markedForReview.size == parsed.size) state.markedForReview else List(parsed.size) { false }
                            previousScreenBeforeTest = AppScreen.SOLUTIONS
                            currentScreen = AppScreen.TEST
                        }
                    },
                    onStartOnlineQuiz = { meta ->
                        launchOnlineQuiz(meta)
                    },
                    onNavigateHome = { currentScreen = AppScreen.HOME },
                    onNavigateMyTests = {
                        selectedExamFilter = targetExamCode
                        currentScreen = AppScreen.MY_TESTS
                    },
                    onNavigateSettings = { currentScreen = AppScreen.SETTINGS },
                    onEditProfile = { currentScreen = AppScreen.PROFILE_SETUP },
                    onOpenAdminModal = { showPinGateModal = true }
                )
            }

            AppScreen.SETTINGS -> {
                SettingsScreenUI(
                    userProfile = userProfile ?: UserProfile(
                        name = candidateName.ifBlank { "Sanjay Suthar" },
                        targetExam = "RPSC • RSSB • CTET • UGC NET",
                        districtOrCity = "Rajasthan",
                        avatarEmoji = "🎓"
                    ),
                    totalOnlineTests = onlineQuizzes.size,
                    completedResultsMap = completedResultsMap,
                    onNavigateHome = { currentScreen = AppScreen.HOME },
                    onNavigateMyTests = {
                        selectedExamFilter = targetExamCode
                        currentScreen = AppScreen.MY_TESTS
                    },
                    onNavigateSolutions = { currentScreen = AppScreen.SOLUTIONS },
                    onEditProfile = { currentScreen = AppScreen.PROFILE_SETUP },
                    onOpenLatestResult = { saved ->
                        openSavedResult(saved, AppScreen.SETTINGS)
                    },
                    onOpenTeacherAdmin = { showPinGateModal = true },
                    onRefreshOnline = {
                        scope.launch {
                            isLoadingOnline = true
                            try {
                                onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes(context)
                            } catch (_: Exception) {
                            }
                            isLoadingOnline = false
                        }
                    },
                    onClearAllData = {
                        ProfileAndHistoryStorage.clearAllStudentData(context)
                        completedResultsMap = emptyMap()
                        savedResumeState = null
                        hasCustomSavedQuiz = false
                        Toast.makeText(context, "सभी टेस्ट हिस्ट्री और कैश डेटा साफ कर दिया गया!", Toast.LENGTH_SHORT).show()
                    },
                    onLogout = {
                        ProfileAndHistoryStorage.logoutProfile(context)
                        userProfile = null
                        currentScreen = AppScreen.PROFILE_SETUP
                    }
                )
            }

            AppScreen.MY_TESTS -> {
                MyTestsScreenUI(
                    userProfile = userProfile ?: UserProfile(
                        name = candidateName.ifBlank { "Student" },
                        targetExam = "RPSC",
                        districtOrCity = "Rajasthan",
                        avatarEmoji = "🎓"
                    ),
                    selectedExamFilter = selectedExamFilter,
                    onSelectExamFilter = { selectedExamFilter = it },
                    examSubjectsMap = activeExamSubjectsMap,
                    allOnlineQuizzes = onlineQuizzes,
                    completedResultsMap = completedResultsMap,
                    isLoadingOnline = isLoadingOnline,
                    hasCustomSavedQuiz = hasCustomSavedQuiz,
                    customQuizExam = prefs.getString("saved_exam", "RPSC") ?: "RPSC",
                    customQuizSubject = prefs.getString("saved_subject", "General") ?: "General",
                    customQuizTitle = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                    customQuizCount = TxtQuestionParser.parse(prefs.getString("saved_custom_txt", "") ?: "").size,
                    onOpenSubjectFolder = { subjectName ->
                        selectedSubjectFolder = subjectName
                        previousScreenBeforeSubject = AppScreen.MY_TESTS
                        currentScreen = AppScreen.SUBJECT_TESTS
                    },
                    onQuickStartSubjectTest = { quizMeta ->
                        launchOnlineQuiz(quizMeta)
                    },
                    onRefreshOnline = {
                        scope.launch {
                            isLoadingOnline = true
                            try {
                                onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes(context)
                            } catch (_: Exception) {
                            }
                            isLoadingOnline = false
                        }
                    },
                    onOpenAdminModal = { showPinGateModal = true },
                    onStartCustomQuiz = {
                        val savedTxt = prefs.getString("saved_custom_txt", "") ?: ""
                        val parsed = TxtQuestionParser.parse(savedTxt)
                        if (parsed.isNotEmpty()) {
                            activeRawTxt = savedTxt
                            questions = parsed
                            val tMins = prefs.getInt("saved_time", 60)
                            testConfig = TestConfig(
                                id = ProfileAndHistoryStorage.customQuizIdForText(savedTxt),
                                title = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                                examName = prefs.getString("saved_exam", "RPSC") ?: "RPSC",
                                subject = prefs.getString("saved_subject", "General") ?: "General",
                                timeMinutes = tMins,
                                marksPerCorrect = prefs.getFloat("saved_marks", 2.0f).toDouble(),
                                negativeMark = prefs.getFloat("saved_neg", 0.0f).toDouble()
                            )
                            userAnswers = List(parsed.size) { null }
                            markedForReview = List(parsed.size) { false }
                            currentQIndex = 0
                            remainingSeconds = tMins * 60
                            previousScreenBeforeTest = AppScreen.MY_TESTS
                            currentScreen = AppScreen.TEST
                            persistActiveProgress()
                        }
                    },
                    onOpenCustomBuilder = { currentScreen = AppScreen.CUSTOM_BUILDER },
                    onNavigateHome = { currentScreen = AppScreen.HOME },
                    onNavigateSolutions = { currentScreen = AppScreen.SOLUTIONS },
                    onNavigateSettings = { currentScreen = AppScreen.SETTINGS },
                    onChangeTargetExamProfile = { currentScreen = AppScreen.PROFILE_SETUP }
                )
            }

            AppScreen.SUBJECT_TESTS -> {
                val folderTests = (activeExamSubjectsMap[selectedSubjectFolder] ?: groupedBySubject[selectedSubjectFolder]) ?: emptyList()
                SubjectTestsScreenUI(
                    examName = selectedExamFilter,
                    subjectName = selectedSubjectFolder,
                    tests = folderTests,
                    completedResultsMap = completedResultsMap,
                    isDownloading = isDownloadingQuiz,
                    onBack = { currentScreen = previousScreenBeforeSubject },
                    onStartTest = { meta -> launchOnlineQuiz(meta) },
                    onViewSavedResult = { saved ->
                        openSavedResult(saved, AppScreen.SUBJECT_TESTS)
                    }
                )
            }

            AppScreen.CUSTOM_BUILDER -> {
                CustomQuizScreenUI(
                    initialConfig = testConfig,
                    onBack = { currentScreen = AppScreen.HOME },
                    onSaveCustomQuiz = { newCfg, rawTxt, parsedList ->
                        testConfig = newCfg
                        activeRawTxt = rawTxt
                        questions = parsedList
                        hasCustomSavedQuiz = true
                        prefs.edit()
                            .putString("saved_custom_txt", rawTxt)
                            .putString("saved_exam", newCfg.examName)
                            .putString("saved_subject", newCfg.subject)
                            .putString("saved_title", newCfg.title)
                            .putInt("saved_time", newCfg.timeMinutes)
                            .putFloat("saved_marks", newCfg.marksPerCorrect.toFloat())
                            .putFloat("saved_neg", newCfg.negativeMark.toFloat())
                            .apply()
                        currentScreen = AppScreen.HOME
                        Toast.makeText(context, "Custom Quiz सेव हो गया (${parsedList.size} प्रश्न)!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            AppScreen.ADMIN_PORTAL -> {
                AdminPortalScreenUI(
                    savedToken = SecureAdminStore.loadPat(context),
                    groupedSubjects = groupedBySubject,
                    latestQuiz = onlineQuizzes.lastOrNull(),
                    onBack = { currentScreen = AppScreen.HOME },
                    onActionSuccess = { tokenUsed ->
                        SecureAdminStore.savePat(context, tokenUsed)
                        scope.launch {
                            isLoadingOnline = true
                            try {
                                onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes(context)
                            } catch (_: Exception) {
                            }
                            isLoadingOnline = false
                        }
                    }
                )
            }

            AppScreen.TEST -> {
                TestScreenUI(
                    testConfig = testConfig,
                    candidateName = candidateName,
                    questions = questions,
                    currentQIndex = currentQIndex,
                    userAnswers = userAnswers,
                    isMarkedForReview = markedForReview.getOrElse(currentQIndex) { false },
                    remainingSeconds = remainingSeconds,
                    onBackClick = { showExitTestConfirm = true },
                    onToggleReview = {
                        val mutable = markedForReview.toMutableList()
                        mutable[currentQIndex] = !mutable[currentQIndex]
                        markedForReview = mutable
                        persistActiveProgress()
                    },
                    onSelectOption = { optIdx ->
                        val mutable = userAnswers.toMutableList()
                        mutable[currentQIndex] = optIdx
                        userAnswers = mutable
                        persistActiveProgress()
                    },
                    onPrev = {
                        if (currentQIndex > 0) {
                            currentQIndex--
                            persistActiveProgress()
                        }
                    },
                    onNext = {
                        if (currentQIndex < questions.size - 1) {
                            currentQIndex++
                            persistActiveProgress()
                        } else {
                            showSubmitConfirm = true
                        }
                    },
                    onSkip = {
                        if (currentQIndex < questions.size - 1) {
                            currentQIndex++
                            persistActiveProgress()
                        } else {
                            showSubmitConfirm = true
                        }
                    },
                    onOpenPalette = { showPaletteSheet = true },
                    onSubmitClick = { showSubmitConfirm = true }
                )
            }

            AppScreen.RESULT -> {
                ResultScreenUI(
                    testConfig = testConfig,
                    candidateName = candidateName,
                    questions = questions,
                    userAnswers = userAnswers,
                    timeSpentSeconds = resultTimeSpentSeconds,
                    onBackHome = { currentScreen = previousScreenBeforeTest },
                    onPrintPdf = {
                        printTwoColumnA4ResultPdf(
                            context = context,
                            config = testConfig,
                            studentName = candidateName,
                            questions = questions,
                            userAnswers = userAnswers
                        )
                    },
                    onRetake = {
                        userAnswers = List(questions.size) { null }
                        markedForReview = List(questions.size) { false }
                        currentQIndex = 0
                        remainingSeconds = testConfig.timeMinutes * 60
                        currentScreen = AppScreen.TEST
                        persistActiveProgress()
                    }
                )
            }
        }

        if (isDownloadingQuiz) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(CardDark)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = PrimaryRose)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("सुरक्षित टेस्ट लोड हो रहा है...", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showPaletteSheet && currentScreen == AppScreen.TEST) {
            AlertDialog(
                onDismissRequest = { showPaletteSheet = false },
                containerColor = CardDark,
                title = {
                    Column {
                        Text("Question Palette", color = TextMain, fontWeight = FontWeight.Bold)
                        Text("हरा: उत्तर दिया | पीला: Review | लाल: वर्तमान", color = TextMuted, fontSize = 11.sp)
                    }
                },
                text = {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 320.dp)
                    ) {
                        items(questions.size) { idx ->
                            val bg = when {
                                idx == currentQIndex -> PrimaryRose
                                markedForReview.getOrElse(idx) { false } -> AmberGold
                                userAnswers[idx] != null -> CorrectGreen
                                else -> BgDark
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bg)
                                    .clickable {
                                        currentQIndex = idx
                                        persistActiveProgress()
                                        showPaletteSheet = false
                                    }
                            ) {
                                Text("${idx + 1}", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPaletteSheet = false
                            showSubmitConfirm = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("✅ Submit Exam", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        if (showExitTestConfirm) {
            AlertDialog(
                onDismissRequest = { showExitTestConfirm = false },
                containerColor = CardDark,
                title = { Text("परीक्षा रोकें और बाहर जाएं?", color = TextMain, fontWeight = FontWeight.Bold) },
                text = { Text("आपका बचा हुआ समय और उत्तर सेव कर लिए गए हैं। आप होम स्क्रीन से कभी भी Resume कर सकते हैं।", color = TextMuted) },
                confirmButton = {
                    Button(
                        onClick = {
                            persistActiveProgress()
                            showExitTestConfirm = false
                            currentScreen = previousScreenBeforeTest
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold)
                    ) {
                        Text("सेव करें व बाहर जाएं", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showExitTestConfirm = false }) {
                        Text("परीक्षा जारी रखें", color = TextMain)
                    }
                }
            )
        }

        if (showSubmitConfirm) {
            AlertDialog(
                onDismissRequest = { showSubmitConfirm = false },
                containerColor = CardDark,
                title = { Text("परीक्षा सबमिट करें?", color = TextMain, fontWeight = FontWeight.Bold) },
                text = { Text("क्या आप वाकई परीक्षा सबमिट करना चाहते हैं?", color = TextMuted) },
                confirmButton = {
                    Button(
                        onClick = {
                            showSubmitConfirm = false
                            finishAndSubmitExam()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen)
                    ) {
                        Text("हाँ, सबमिट करें", color = Color.White)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showSubmitConfirm = false }) {
                        Text("वापस जाएं", color = TextMain)
                    }
                }
            )
        }

        if (showNamePromptDialog && pendingQuizToStart != null) {
            var tempName by remember { mutableStateOf(candidateName) }
            AlertDialog(
                onDismissRequest = { showNamePromptDialog = false },
                containerColor = CardDark,
                title = { Text("अपना नाम लिखें", color = TextMain, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = tempName,
                        onValueChange = { tempName = it },
                        placeholder = { Text("उदा. राहुल शर्मा", color = TextMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (tempName.isNotBlank()) {
                                candidateName = tempName.trim()
                                prefs.edit().putString("student_name", candidateName).apply()
                                val meta = pendingQuizToStart!!
                                showNamePromptDialog = false
                                pendingQuizToStart = null
                                launchOnlineQuiz(meta)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose)
                    ) {
                        Text("शुरू करें", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNamePromptDialog = false }) {
                        Text("रद्द करें", color = TextMuted)
                    }
                }
            )
        }

        if (showPinGateModal) {
            AdminPinGateDialog(
                isSettingNewPin = !SecureAdminStore.hasPin(context),
                onDismiss = { showPinGateModal = false },
                onPinVerified = { pin ->
                    if (!SecureAdminStore.hasPin(context)) {
                        SecureAdminStore.setPin(context, pin)
                    }
                    showPinGateModal = false
                    currentScreen = AppScreen.ADMIN_PORTAL
                }
            )
        }
    }
}

// ==================== 10. STUDENT DEMO PROFILE SETUP SCREEN ====================
@Composable
fun ProfileSetupScreenUI(
    existingProfile: UserProfile?,
    onSaveProfile: (UserProfile) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existingProfile?.name ?: "") }
    var selectedExam by remember { mutableStateOf(existingProfile?.targetExam ?: "RPSC (Rajasthan Public Service Commission)") }
    var district by remember { mutableStateOf(existingProfile?.districtOrCity ?: "Rajasthan") }
    var selectedAvatar by remember { mutableStateOf(existingProfile?.avatarEmoji ?: "🎓") }

    val avatars = listOf("🎓", "👨‍🎓", "👩‍🎓", "📚", "🏆", "🎯")
    val examOptions = listOf(
        "RPSC (Rajasthan Public Service Commission)",
        "RSSB (Rajasthan Staff Selection Board)",
        "CTET (Central Teacher Eligibility Test)",
        "UGC NET (National Eligibility Test)"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF06102B), Color(0xFF020617))))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Study Smart... Score High...",
            color = AmberGold,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "ONLINE MOCK TEST",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Text(
            text = "Create Your Student Profile",
            color = SecondarySky,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp, bottom = 20.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(HomeCard)
                .border(1.dp, PrimaryRose.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("अपना प्रोफाइल आइकन चुनें", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                avatars.forEach { emoji ->
                    val isSel = selectedAvatar == emoji
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (isSel) PrimaryRose else HomeBox)
                            .border(2.dp, if (isSel) AmberGold else Color.Transparent, CircleShape)
                            .clickable { selectedAvatar = emoji }
                    ) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("आपका पूरा नाम (Student Name)") },
                placeholder = { Text("उदा. संजय सुथार", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = district,
                onValueChange = { district = it },
                label = { Text("शहर / जिला (City / District)") },
                placeholder = { Text("उदा. हनुमानगढ़, राजस्थान", color = TextMuted) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "अपनी मुख्य परीक्षा चुनें (Target Exam):",
                color = TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))

            examOptions.forEach { exam ->
                val isSelected = selectedExam == exam
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) PrimaryRose.copy(alpha = 0.16f) else HomeBgTop)
                        .border(1.dp, if (isSelected) PrimaryRose else BorderColor, RoundedCornerShape(10.dp))
                        .clickable { selectedExam = exam }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSelected) "🔘" else "⚪",
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = exam,
                        color = if (isSelected) Color.White else TextMuted,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    if (name.trim().isEmpty()) {
                        Toast.makeText(context, "कृपया आगे बढ़ने के लिए अपना नाम लिखें!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    onSaveProfile(
                        UserProfile(
                            name = name.trim(),
                            targetExam = selectedExam,
                            districtOrCity = district.trim().ifEmpty { "Rajasthan" },
                            avatarEmoji = selectedAvatar
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("🚀 प्रोफाइल सेव करें व ऐप खोलें", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==================== 10B. ADMIN PIN LOCK DIALOG ====================
@Composable
fun AdminPinGateDialog(
    isSettingNewPin: Boolean,
    onDismiss: () -> Unit,
    onPinVerified: (String) -> Unit
) {
    val context = LocalContext.current
    var pinInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = {
            Text(
                text = if (isSettingNewPin) "🔐 नया Admin PIN सेट करें" else "🔐 Teacher Admin PIN दर्ज करें",
                color = PrimaryRose,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                Text(
                    text = if (isSettingNewPin)
                        "ताकि कोई भी छात्र आपके टेस्ट या फोल्डर डिलीट न कर सके, 4 से 6 अंकों का PIN सेट करें।"
                    else
                        "Admin पैनल खोलने के लिए अपना गुप्त PIN डालें।",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { if (it.length <= 6) pinInput = it },
                    label = { Text("4-6 Digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pinInput.length < 4) {
                        Toast.makeText(context, "कम से कम 4 अंकों का PIN डालें", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isSettingNewPin || SecureAdminStore.verifyPin(context, pinInput)) {
                        onPinVerified(pinInput)
                    } else {
                        Toast.makeText(context, "गलत PIN! कृपया सही PIN डालें।", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose)
            ) {
                Text(if (isSettingNewPin) "Set PIN & Open" else "Unlock", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        }
    )
}

// ==================== 11. EXACT MOCKUP HOME SCREEN UI (100% PURE COMPOSE CODE) ====================
@Composable
fun HomeScreenUI(
    userProfile: UserProfile,
    onEditProfile: () -> Unit,
    savedResumeState: SavedResumeState?,
    onResumeTest: (SavedResumeState) -> Unit,
    onDiscardResume: () -> Unit,
    selectedExamFilter: String,
    onSelectExamCategory: (String) -> Unit,
    groupedSubjects: Map<String, List<OnlineQuizMeta>>,
    allOnlineQuizzes: List<OnlineQuizMeta>,
    completedResultsMap: Map<String, List<CompletedTestResult>>,
    totalOnlineTests: Int,
    isLoadingOnline: Boolean,
    hasCustomSavedQuiz: Boolean,
    customQuizTitle: String,
    customQuizCount: Int,
    onRefreshOnline: () -> Unit,
    onOpenSubjectFolder: (String) -> Unit,
    onOpenMyTestsScreen: () -> Unit,
    onOpenLatestResult: (CompletedTestResult) -> Unit,
    onStartCustomQuiz: () -> Unit,
    onOpenCustomModal: () -> Unit,
    onOpenSolutionsScreen: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    onOpenAdminModal: () -> Unit
) {
    val context = LocalContext.current
    var selectedBottomNav by remember { mutableStateOf(0) } // 0=Home, 1=My Tests, 2=Results, 3=Settings
    var showResultsHistorySheet by remember { mutableStateOf(false) }
    var showPerformanceDialog by remember { mutableStateOf(false) }

    val latestAttemptsPerQuiz = remember(completedResultsMap) {
        completedResultsMap.values.mapNotNull { it.lastOrNull() }
    }
    val allAttemptsSorted = remember(completedResultsMap) {
        completedResultsMap.values.flatten().sortedByDescending { it.completedAtMillis }
    }
    val completedCount = latestAttemptsPerQuiz.size
    val avgPercentage = remember(latestAttemptsPerQuiz) {
        if (latestAttemptsPerQuiz.isNotEmpty()) latestAttemptsPerQuiz.map { it.percentage }.average() else 0.0
    }
    val completionRatio = remember(completedCount, totalOnlineTests) {
        if (totalOnlineTests > 0) (completedCount.toFloat() / totalOnlineTests.toFloat()).coerceIn(0f, 1f)
        else if (completedCount > 0) 1f else 0f
    }
    val displayPercentInt = remember(avgPercentage, completedCount, completionRatio) {
        if (completedCount > 0) avgPercentage.roundToInt().coerceIn(0, 100)
        else (completionRatio * 100).roundToInt()
    }

    val firstSubjectName = groupedSubjects.keys.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050C24), Color(0xFF09153E), Color(0xFF04091A))
                )
            )
    ) {
        // Main Scrollable Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 92.dp)
        ) {
            // ==================== 1. TOP HEADER BAR ====================
            AppTopHeaderBar(
                avatarEmoji = userProfile.avatarEmoji,
                totalOnlineTests = totalOnlineTests,
                isLoadingOnline = isLoadingOnline,
                onMenuClick = onOpenAdminModal,
                onBellClick = onRefreshOnline,
                onProfileClick = onEditProfile
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 2. HERO BANNER (Prepare Smart... Score High... + Rajasthan Fort Graphic) ====================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(182.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF07163B), Color(0xFF0D235A), Color(0xFF1E3A8A))
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(listOf(SecondarySky, PrimaryRose, SecondarySky)),
                        shape = RoundedCornerShape(20.dp)
                    )
            ) {
                // Right Side Pure-Code Rajasthan Golden Fort & Sky Illustration
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height

                    // Warm Golden-Sky Glow on Right Side
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF38BDF8).copy(alpha = 0.45f), Color.Transparent),
                            center = Offset(w * 0.80f, h * 0.35f),
                            radius = w * 0.45f
                        ),
                        center = Offset(w * 0.80f, h * 0.35f),
                        radius = w * 0.45f
                    )

                    // Neon Pink Sweeping Arc Divider (like mockup banner)
                    val arcPath = Path().apply {
                        moveTo(w * 0.56f, 0f)
                        quadraticBezierTo(w * 0.44f, h * 0.55f, w * 0.65f, h)
                        lineTo(w, h)
                        lineTo(w, 0f)
                        close()
                    }
                    drawPath(
                        path = arcPath,
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFF7DD3FC).copy(alpha = 0.25f), Color(0xFFF59E0B).copy(alpha = 0.22f))
                        )
                    )

                    val neonCurve = Path().apply {
                        moveTo(w * 0.57f, 0f)
                        quadraticBezierTo(w * 0.44f, h * 0.55f, w * 0.64f, h)
                    }
                    drawPath(
                        path = neonCurve,
                        brush = Brush.verticalGradient(listOf(PrimaryRose, Color(0xFFFF6B8B), SecondarySky)),
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )

                    // Stylized Golden Rajasthan Fort Silhouette on the Right Half
                    val fortBaseY = h * 0.82f
                    val fortLeft = w * 0.58f
                    val fortColor = Color(0xFFD97706)
                    val fortLight = Color(0xFFFBBF24)

                    // Fort Hill
                    val hillPath = Path().apply {
                        moveTo(w * 0.50f, h)
                        quadraticBezierTo(w * 0.72f, h * 0.68f, w, h * 0.74f)
                        lineTo(w, h)
                        close()
                    }
                    drawPath(hillPath, color = Color(0xFF78350F))

                    // Fort Battlements & Towers
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(fortLeft, h * 0.44f),
                        size = Size(w * 0.38f, h * 0.34f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    // Left & Right Royal Turrets (Chhatris)
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(w * 0.62f, h * 0.30f),
                        size = Size(w * 0.09f, h * 0.45f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(w * 0.76f, h * 0.24f),
                        size = Size(w * 0.11f, h * 0.52f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(w * 0.90f, h * 0.32f),
                        size = Size(w * 0.08f, h * 0.44f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    // Green Trees at Fort Base
                    drawCircle(color = Color(0xFF15803D), radius = 26f, center = Offset(w * 0.66f, fortBaseY))
                    drawCircle(color = Color(0xFF166534), radius = 32f, center = Offset(w * 0.78f, fortBaseY + 6f))
                    drawCircle(color = Color(0xFF15803D), radius = 24f, center = Offset(w * 0.90f, fortBaseY + 4f))
                }

                // Left Content Inside Hero Banner
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.68f)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Prepare Smart...",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 21.sp
                        )
                        Text(
                            text = "Score High...",
                            color = AmberGold,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black,
                            lineHeight = 23.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        // Pink "Rajasthan Exams" Pill
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C))))
                                .padding(horizontal = 12.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Rajasthan Exams",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "RPSC  |  RSSB  |  CTET  |  UGC NET",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }

                    // Golden Yellow "Start Mock Test ->" Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFFACC15), Color(0xFFF59E0B))))
                            .clickable { onOpenMyTestsScreen() }
                            .padding(horizontal = 16.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "Start Mock Test  ➔",
                            color = Color(0xFF09090B),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 3. 4 GLOWING QUICK ACTION TILES (Mock Tests | Custom Test | Performance | Results) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickFeatureSquareCard(
                    modifier = Modifier.weight(1f),
                    icon = "📋",
                    label = "Mock Tests",
                    glowColor = PrimaryRose,
                    onClick = { onOpenMyTestsScreen() }
                )
                QuickFeatureSquareCard(
                    modifier = Modifier.weight(1f),
                    icon = "📝",
                    label = "Custom Test",
                    glowColor = SecondarySky,
                    onClick = { onOpenCustomModal() }
                )
                QuickFeatureSquareCard(
                    modifier = Modifier.weight(1f),
                    icon = "📈",
                    label = "Performance",
                    glowColor = SkippedPurple,
                    onClick = { showPerformanceDialog = true }
                )
                QuickFeatureSquareCard(
                    modifier = Modifier.weight(1f),
                    icon = "🏆",
                    label = "Results",
                    glowColor = AmberGold,
                    onClick = { onOpenSolutionsScreen() }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==================== 4. POPULAR EXAMS (4 WHITE CARDS: RPSC | RSSB | CTET | UGC NET) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PrimaryRose)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Popular Exams",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = if (isLoadingOnline) "Syncing..." else "Refresh ➔",
                    color = SecondarySky,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onRefreshOnline() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PopularExamWhiteCard(
                    modifier = Modifier.weight(1f),
                    emblem = "🏛️",
                    title = "RPSC",
                    subtitle = "Rajasthan Public\nService Commission",
                    bottomAccent = PrimaryRose,
                    onClick = { onSelectExamCategory("RPSC") }
                )
                PopularExamWhiteCard(
                    modifier = Modifier.weight(1f),
                    emblem = "🏢",
                    title = "RSSB",
                    subtitle = "Rajasthan Staff\nSelection Board",
                    bottomAccent = SecondarySky,
                    onClick = { onSelectExamCategory("RSSB") }
                )
                PopularExamWhiteCard(
                    modifier = Modifier.weight(1f),
                    emblem = "📘",
                    title = "CTET",
                    subtitle = "Central Teacher\nEligibility Test",
                    bottomAccent = SkippedPurple,
                    onClick = { onSelectExamCategory("CTET") }
                )
                PopularExamWhiteCard(
                    modifier = Modifier.weight(1f),
                    emblem = "☸️",
                    title = "UGC NET",
                    subtitle = "National Eligibility\nTest",
                    bottomAccent = CorrectGreen,
                    onClick = { onSelectExamCategory("UGC NET") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==================== 5. TWO SPLIT BOTTOM CARDS (Time Matters! + Circular Progress 75% Ring) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Card: Time Matters! (Starts Resume Test or Custom/Online Test)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(118.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0F1E46), Color(0xFF1E1B4B))
                            )
                        )
                        .border(1.dp, PrimaryRose.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .clickable {
                            when {
                                savedResumeState != null -> onResumeTest(savedResumeState)
                                hasCustomSavedQuiz && customQuizCount > 0 -> onStartCustomQuiz()
                                firstSubjectName != null -> onOpenSubjectFolder(firstSubjectName)
                                else -> onOpenCustomModal()
                            }
                        }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pure-Code 3D Stopwatch Graphic
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf( Color(0xFF38BDF8).copy(alpha = 0.35f), Color(0xFF1E3A8A))
                                )
                            )
                            .border(2.dp, SecondarySky, CircleShape)
                    ) {
                        Text("⏱️", fontSize = 26.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (savedResumeState != null) "Resume Test!" else "Time Matters!",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(
                            text = if (savedResumeState != null)
                                "बचा समय: %02d:%02d".format(savedResumeState.remainingSeconds / 60, savedResumeState.remainingSeconds % 60)
                            else
                                "Practice Regularly\n& Boost Your Score",
                            color = Color(0xFFCBD5E1),
                            fontSize = 9.5.sp,
                            lineHeight = 12.sp,
                            maxLines = 2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(PrimaryRose)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (savedResumeState != null) "Resume ➔" else "Start Now ➔",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Right Card: Your Progress with Neon Cyan Circular Ring
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(118.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0F1E46), Color(0xFF091534))
                            )
                        )
                        .border(1.dp, SecondarySky.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                        .clickable { onOpenSolutionsScreen() }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(60.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { 1f },
                            color = Color(0xFF1E293B),
                            strokeWidth = 6.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        CircularProgressIndicator(
                            progress = { (displayPercentInt / 100f).coerceIn(0.05f, 1f) },
                            color = Color(0xFF00F0FF),
                            strokeWidth = 6.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "$displayPercentInt%",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Your Progress",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(
                            text = "$completedCount / $totalOnlineTests Tests",
                            color = Color(0xFFCBD5E1),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(SecondarySky)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "View Details ➔",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Optional Resume Discard Strip if a test is paused
            if (savedResumeState != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AmberGold.copy(alpha = 0.14f))
                        .border(1.dp, AmberGold.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⏳ अधूरा टेस्ट: ${savedResumeState.config.title}",
                        color = AmberGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "✕ हटाएं",
                        color = WrongRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .clickable { onDiscardResume() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ==================== 6. ALL SUBJECT FOLDERS LIST (Direct Access on Home) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SecondarySky)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$selectedExamFilter Subject Folders",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = "${groupedSubjects.size} Folders",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isLoadingOnline && groupedSubjects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HomeCard)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PrimaryRose, modifier = Modifier.size(28.dp))
                }
            } else if (groupedSubjects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HomeCard)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "अभी कोई ऑनलाइन फोल्डर नहीं मिला। ऊपर 🔔 या Refresh पर टैप करें।",
                        color = TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val folderColors = listOf(PrimaryRose, SecondarySky, CorrectGreen, AmberGold, SkippedPurple)
                groupedSubjects.entries.forEachIndexed { index, (subjectName, testList) ->
                    val accent = folderColors[index % folderColors.size]
                    val completedInFolder = testList.count { completedResultsMap.containsKey(it.id) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF111C38))
                            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable { onOpenSubjectFolder(subjectName) }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(accent.copy(alpha = 0.18f))
                        ) {
                            Text("📁", fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = subjectName,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${testList.size} मॉक टेस्ट • $completedInFolder पूर्ण (Completed)",
                                color = TextMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(accent)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "खोलें ➔",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // ==================== 7. FIXED BOTTOM NAVIGATION BAR ====================
        AppBottomNavigationBar(
            selectedTab = selectedBottomNav,
            onNavigateHome = { selectedBottomNav = 0 },
            onNavigateMyTests = onOpenMyTestsScreen,
            onNavigateSolutions = onOpenSolutionsScreen,
            onNavigateSettings = onOpenSettingsScreen
        )

        // Performance Overview Popup
        if (showPerformanceDialog) {
            AlertDialog(
                onDismissRequest = { showPerformanceDialog = false },
                containerColor = CardDark,
                title = { Text("📈 Your Performance Report", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("👤 छात्र: ${userProfile.name}", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("🎯 लक्ष्य: ${userProfile.targetExam}", color = SecondarySky, fontSize = 12.sp)
                        HorizontalDivider(color = BorderColor)
                        Text("✅ कुल पूर्ण किए गए टेस्ट: $completedCount / $totalOnlineTests", color = CorrectGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("🔄 कुल दिए गए Attempts: ${allAttemptsSorted.size}", color = TextMain, fontSize = 13.sp)
                        Text("📊 औसत प्रतिशत (Average Score): %.2f%%".format(avgPercentage), color = AmberGold, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPerformanceDialog = false
                            showResultsHistorySheet = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose)
                    ) {
                        Text("सभी रिजल्ट देखें", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPerformanceDialog = false }) {
                        Text("बंद करें", color = TextMuted)
                    }
                }
            )
        }

        // All Completed Results & Attempts Dialog
        if (showResultsHistorySheet) {
            AlertDialog(
                onDismissRequest = {
                    showResultsHistorySheet = false
                    selectedBottomNav = 0
                },
                containerColor = CardDark,
                title = { Text("🏆 Saved Results & Attempts", color = AmberGold, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                text = {
                    if (allAttemptsSorted.isEmpty()) {
                        Text("आपने अभी तक कोई टेस्ट सबमिट नहीं किया है। किसी भी टेस्ट को पूरा करने के बाद उसका रिजल्ट और 2-Column PDF यहाँ दिखेगा।", color = TextMuted, fontSize = 13.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(allAttemptsSorted) { att ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(HomeCard)
                                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                        .clickable {
                                            showResultsHistorySheet = false
                                            selectedBottomNav = 0
                                            onOpenLatestResult(att)
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(att.config.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Attempt ${att.attemptNumber} • ${att.completedDate}", color = TextMuted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("%.2f / %.2f".format(att.obtainedMarks, att.maxMarks), color = AmberGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("%.1f%% ➔".format(att.percentage), color = CorrectGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResultsHistorySheet = false
                            selectedBottomNav = 0
                        }
                    ) {
                        Text("Close", color = PrimaryRose, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
fun QuickFeatureSquareCard(
    modifier: Modifier,
    icon: String,
    label: String,
    glowColor: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF132046), Color(0xFF0B132B))
                )
            )
            .border(1.dp, glowColor.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(glowColor, glowColor.copy(alpha = 0.25f))
                    )
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
        ) {
            Text(icon, fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PopularExamWhiteCard(
    modifier: Modifier,
    emblem: String,
    title: String,
    subtitle: String,
    bottomAccent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .height(108.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.5.dp, bottomAccent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(emblem, fontSize = 24.sp)
        Text(
            text = title,
            color = Color(0xFF0F172A),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
        Text(
            text = subtitle,
            color = Color(0xFF334155),
            fontSize = 8.5.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AppTopHeaderBar(
    avatarEmoji: String,
    totalOnlineTests: Int,
    isLoadingOnline: Boolean,
    onMenuClick: () -> Unit,
    onBellClick: () -> Unit,
    onProfileClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable { onMenuClick() }
        ) {
            Text("☰", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎓", fontSize = 28.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "Online",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 19.sp
                    )
                    Text(
                        text = "Mock Test",
                        color = PrimaryRose,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 20.sp
                    )
                }
            }
            Text(
                text = "Target Preparation & Revision Test",
                color = Color(0xFFCBD5E1),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickable { onBellClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (isLoadingOnline) "⏳" else "🔔", fontSize = 17.sp)
                if (totalOnlineTests > 0) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(PrimaryRose)
                    ) {
                        Text(
                            text = "$totalOnlineTests",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (onProfileClick != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .border(1.5.dp, SecondarySky.copy(alpha = 0.7f), CircleShape)
                        .clickable { onProfileClick() }
                ) {
                    Text(avatarEmoji, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
fun BoxScope.AppBottomNavigationBar(
    selectedTab: Int,
    onNavigateHome: () -> Unit,
    onNavigateMyTests: () -> Unit,
    onNavigateSolutions: () -> Unit,
    onNavigateSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF081229))
            .border(1.dp, SecondarySky.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomNavTabItem(
            icon = "🏠",
            label = "Home",
            isSelected = selectedTab == 0,
            onClick = onNavigateHome
        )
        BottomNavTabItem(
            icon = "📊",
            label = "My Tests",
            isSelected = selectedTab == 1,
            onClick = onNavigateMyTests
        )
        BottomNavTabItem(
            icon = "📄",
            label = "Solutions",
            isSelected = selectedTab == 2,
            onClick = onNavigateSolutions
        )
        BottomNavTabItem(
            icon = "⚙️",
            label = "Settings",
            isSelected = selectedTab == 3,
            onClick = onNavigateSettings
        )
    }
}

@Composable
fun BottomNavTabItem(
    icon: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 18.sp
        )
        Text(
            text = label,
            color = if (isSelected) PrimaryRose else Color(0xFFCBD5E1),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
        )
    }
}

// ==================== 11B. EXACT MOCKUP SETTINGS & PROFILE ANALYTICS SCREEN ====================
@Composable
fun SettingsScreenUI(
    userProfile: UserProfile,
    totalOnlineTests: Int,
    completedResultsMap: Map<String, List<CompletedTestResult>>,
    onNavigateHome: () -> Unit,
    onNavigateMyTests: () -> Unit,
    onNavigateSolutions: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenLatestResult: (CompletedTestResult) -> Unit,
    onOpenTeacherAdmin: () -> Unit,
    onRefreshOnline: () -> Unit,
    onClearAllData: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE) }

    var isDarkThemeEnabled by remember { mutableStateOf(prefs.getBoolean("pref_dark_theme", true)) }
    var isNotificationsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_notifications", true)) }
    var showDownloadCenterDialog by remember { mutableStateOf(false) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogBody by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val allAttempts = remember(completedResultsMap) {
        completedResultsMap.values.flatten().sortedByDescending { it.completedAtMillis }
    }
    val latestPerQuiz = remember(completedResultsMap) {
        completedResultsMap.values.mapNotNull { it.lastOrNull() }
    }
    val totalTestsAttempted = allAttempts.size
    val overallScorePct = remember(latestPerQuiz) {
        if (latestPerQuiz.isNotEmpty()) latestPerQuiz.map { it.percentage }.average() else 0.0
    }
    val totalCorrect = remember(allAttempts) { allAttempts.sumOf { it.correctCount } }
    val totalWrong = remember(allAttempts) { allAttempts.sumOf { it.wrongCount } }
    val totalSkipped = remember(allAttempts) { allAttempts.sumOf { it.skippedCount } }
    val progressRingPct = remember(overallScorePct, totalTestsAttempted) {
        if (totalTestsAttempted > 0) overallScorePct.roundToInt().coerceIn(0, 100) else 0
    }

    val emailSlug = remember(userProfile.name) {
        userProfile.name.lowercase(Locale.ENGLISH).replace(Regex("[^a-z0-9]"), "").ifEmpty { "student" } + "@mocktest.in"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050C24), Color(0xFF09153E), Color(0xFF04091A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 92.dp)
        ) {
            // 1. TOP HEADER BAR
            AppTopHeaderBar(
                avatarEmoji = userProfile.avatarEmoji,
                totalOnlineTests = totalOnlineTests,
                isLoadingOnline = false,
                onMenuClick = onOpenTeacherAdmin,
                onBellClick = onRefreshOnline,
                onProfileClick = null
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 2. USER PROFILE CARD (Dual-Neon Avatar Ring + Premium User Pill + Total Tests | Overall Score)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0A1636), Color(0xFF0E1E48), Color(0xFF0A1636))
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose.copy(alpha = 0.7f), SecondarySky.copy(alpha = 0.7f))),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with Pink-Cyan Ring & Camera Badge
                Box(
                    modifier = Modifier
                        .size(74.dp)
                        .clickable { onEditProfile() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF09132C))
                            .border(
                                width = 3.dp,
                                brush = Brush.sweepGradient(listOf(PrimaryRose, SecondarySky, PrimaryRose)),
                                shape = CircleShape
                            )
                    ) {
                        Text(userProfile.avatarEmoji, fontSize = 32.sp)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(SecondarySky)
                            .border(1.5.dp, Color(0xFF09132C), CircleShape)
                    ) {
                        Text("📷", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Name, Email & Premium User Pill
                Column(modifier = Modifier.weight(1.3f)) {
                    Text(
                        text = userProfile.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "✉️ $emailSlug",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF1E1B18))
                            .border(1.dp, AmberGold, RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "👑 Premium User",
                            color = AmberGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(54.dp)
                        .background(Color.White.copy(alpha = 0.14f))
                )

                // Total Tests Column
                Column(
                    modifier = Modifier.weight(0.75f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📋", fontSize = 16.sp)
                    Text("Total Tests", color = Color(0xFFCBD5E1), fontSize = 9.5.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        text = "$totalTestsAttempted",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Vertical Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(54.dp)
                        .background(Color.White.copy(alpha = 0.14f))
                )

                // Overall Score Column
                Column(
                    modifier = Modifier.weight(0.85f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⭐", fontSize = 16.sp)
                    Text("Overall Score", color = Color(0xFFCBD5E1), fontSize = 9.5.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        text = "%.1f%%".format(overallScorePct),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. YOUR PROGRESS ANALYTICS CARD (Dual Ring + Correct/Wrong/Skipped + Golden Trophy)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF141233), Color(0xFF0D1B3E), Color(0xFF0A1636))
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose.copy(alpha = 0.6f), SecondarySky.copy(alpha = 0.6f))),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📊", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Your Progress",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = "View Analytics ➔",
                        color = Color(0xFF00F0FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onNavigateSolutions() }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dual-Color Circular Progress Ring
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(72.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = { 1f },
                            color = PrimaryRose.copy(alpha = 0.45f),
                            strokeWidth = 7.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        CircularProgressIndicator(
                            progress = { (progressRingPct / 100f).coerceIn(0.05f, 1f) },
                            color = Color(0xFF00F0FF),
                            strokeWidth = 7.dp,
                            modifier = Modifier.fillMaxSize()
                        )
                        Text(
                            text = "$progressRingPct%",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Correct / Wrong / Skipped Breakdown
                    Column(
                        modifier = Modifier.weight(1.25f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ProgressStatLine("✔", CorrectGreen, "Correct Answers", "$totalCorrect")
                        ProgressStatLine("✖", PrimaryRose, "Wrong Answers", "$totalWrong")
                        ProgressStatLine("−", Color(0xFF64748B), "Skipped", "$totalSkipped")
                    }

                    // Vertical Divider
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .width(1.dp)
                            .height(64.dp)
                            .background(Color.White.copy(alpha = 0.14f))
                    )

                    // Motivational Quote + Golden Trophy
                    Column(
                        modifier = Modifier.weight(0.95f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "“Keep Going...\nYou're Doing Great!”",
                            color = Color.White,
                            fontSize = 10.sp,
                            lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("🏆", fontSize = 30.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 4. SETTINGS MENU LIST CONTAINER (8 ROWS EXACTLY MATCHING MOCKUP)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF0A1430))
                    .border(1.dp, SecondarySky.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .padding(vertical = 4.dp)
            ) {
                SettingsMenuRowItem(
                    icon = "👤",
                    iconBg = Color(0xFF1D4ED8),
                    title = "Edit Profile",
                    subtitle = "Update your personal information",
                    onClick = onEditProfile
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuToggleRow(
                    icon = "🎨",
                    iconBg = PrimaryRose,
                    title = "App Theme",
                    subtitle = "Change app theme (Dark / Light)",
                    checked = isDarkThemeEnabled,
                    activeTrackColor = SecondarySky,
                    onCheckedChange = {
                        isDarkThemeEnabled = it
                        prefs.edit().putBoolean("pref_dark_theme", it).apply()
                        Toast.makeText(context, "Theme preference saved!", Toast.LENGTH_SHORT).show()
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuToggleRow(
                    icon = "🔔",
                    iconBg = Color(0xFF2563EB),
                    title = "Notifications",
                    subtitle = "Get alerts for new tests & updates",
                    checked = isNotificationsEnabled,
                    activeTrackColor = PrimaryRose,
                    onCheckedChange = {
                        isNotificationsEnabled = it
                        prefs.edit().putBoolean("pref_notifications", it).apply()
                        Toast.makeText(
                            context,
                            if (it) "नए टेस्ट अलर्ट चालू किए गए!" else "नोटिफिकेशन बंद किए गए",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuRowItem(
                    icon = "⬇️",
                    iconBg = Color(0xFF0284C7),
                    title = "Download Center",
                    subtitle = "Download PDFs & Study Material",
                    onClick = onNavigateSolutions
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuRowItem(
                    icon = "🛡️",
                    iconBg = Color(0xFF0EA5E9),
                    title = "Privacy Policy",
                    subtitle = "Read our privacy policy",
                    onClick = {
                        infoDialogTitle = "🛡️ Privacy Policy"
                        infoDialogBody = "आपका नाम, प्रोफाइल और परीक्षा परिणाम पूरी तरह आपके फोन की लोकल मेमोरी में सुरक्षित रहते हैं। सभी ऑनलाइन प्रश्न पत्र AES-256 एन्क्रिप्शन के साथ सुरक्षित रूप से लोड किए जाते हैं।"
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuRowItem(
                    icon = "ℹ️",
                    iconBg = Color(0xFF2563EB),
                    title = "About Us",
                    subtitle = "Know more about Online Mock Test",
                    onClick = {
                        infoDialogTitle = "ℹ️ About Online Mock Test"
                        infoDialogBody = "Online Mock Test Portal विशेष रूप से RPSC, RSSB, CTET और UGC NET परीक्षाओं की तैयारी करने वाले विद्यार्थियों के लिए बनाया गया है। यहाँ आप विषयवार मॉक टेस्ट दे सकते हैं और 2-कॉलम A4 PDF डाउनलोड कर सकते हैं।"
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuRowItem(
                    icon = "✉️",
                    iconBg = Color(0xFF0284C7),
                    title = "Contact Us",
                    subtitle = "Have any query? Contact us",
                    onClick = {
                        infoDialogTitle = "✉️ Contact & Teacher Admin"
                        infoDialogBody = "किसी भी सहायता या नए टेस्ट अपडेट के लिए अपने शिक्षक से संपर्क करें।\n\n(यदि आप शिक्षक हैं, तो ऊपर बाईं ओर ☰ बटन दबाकर Teacher Admin Panel खोल सकते हैं।)"
                    }
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.07f))

                SettingsMenuRowItem(
                    icon = "📄",
                    iconBg = Color(0xFFD97706),
                    title = "Rate App",
                    subtitle = "Give us your valuable rating",
                    onClick = {
                        Toast.makeText(context, "⭐⭐⭐⭐⭐ आपकी 5-स्टार रेटिंग और सहयोग के लिए धन्यवाद!", Toast.LENGTH_LONG).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 5. BOTTOM LOGOUT & CLEAR DATA BUTTONS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Outlined Logout Button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF0A1430))
                        .border(1.5.dp, SecondarySky, RoundedCornerShape(24.dp))
                        .clickable { onLogout() },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🚪  Logout", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }

                // Pink-Red Clear Data Button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C))))
                        .clickable { showClearConfirm = true },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🗑️  Clear Data", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 6. VERSION FOOTER
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Version 1.0.0", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "Made with ❤️ for Your Success",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 7. FIXED BOTTOM NAVIGATION BAR (Settings Highlighted)
        AppBottomNavigationBar(
            selectedTab = 3,
            onNavigateHome = onNavigateHome,
            onNavigateMyTests = onNavigateMyTests,
            onNavigateSolutions = onNavigateSolutions,
            onNavigateSettings = {}
        )

        // Download Center / Analytics History Dialog
        if (showDownloadCenterDialog) {
            AlertDialog(
                onDismissRequest = { showDownloadCenterDialog = false },
                containerColor = CardDark,
                title = { Text("⬇️ Download Center & Results", color = SecondarySky, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                text = {
                    if (allAttempts.isEmpty()) {
                        Text("अभी कोई टेस्ट पूरा नहीं किया गया है। किसी भी टेस्ट को सबमिट करने के बाद आप यहाँ से उसका 2-Column A4 PDF और विस्तृत हल डाउनलोड कर सकते हैं।", color = TextMuted, fontSize = 13.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(allAttempts) { att ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(HomeCard)
                                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                        .clickable {
                                            showDownloadCenterDialog = false
                                            onOpenLatestResult(att)
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(att.config.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Attempt ${att.attemptNumber} • ${att.completedDate}", color = TextMuted, fontSize = 11.sp)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("🖨️ PDF / View", color = SecondarySky, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("%.1f%%".format(att.percentage), color = CorrectGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDownloadCenterDialog = false }) {
                        Text("Close", color = PrimaryRose, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Info Dialog (Privacy / About / Contact)
        if (infoDialogTitle != null) {
            AlertDialog(
                onDismissRequest = { infoDialogTitle = null },
                containerColor = CardDark,
                title = { Text(infoDialogTitle!!, color = Color.White, fontWeight = FontWeight.Bold) },
                text = { Text(infoDialogBody, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp) },
                confirmButton = {
                    Button(
                        onClick = { infoDialogTitle = null },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose)
                    ) {
                        Text("ठीक है", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // Clear Data Confirmation Dialog
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                containerColor = CardDark,
                title = { Text("🗑️ Clear All Test Data?", color = WrongRed, fontWeight = FontWeight.Bold) },
                text = { Text("क्या आप वाकई अपने सभी दिए गए टेस्ट के स्कोर, Attempts हिस्ट्री और अधूरे टेस्ट का डेटा साफ करना चाहते हैं?", color = TextMuted, fontSize = 13.sp) },
                confirmButton = {
                    Button(
                        onClick = {
                            showClearConfirm = false
                            onClearAllData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WrongRed)
                    ) {
                        Text("हाँ, साफ करें", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showClearConfirm = false }) {
                        Text("रद्द करें", color = TextMain)
                    }
                }
            )
        }
    }
}

@Composable
fun ProgressStatLine(symbol: String, badgeColor: Color, label: String, countText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            ) {
                Text(symbol, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = Color(0xFFE2E8F0), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(countText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun SettingsMenuRowItem(
    icon: String,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconBg.copy(alpha = 0.25f))
                .border(1.dp, iconBg, CircleShape)
        ) {
            Text(icon, fontSize = 17.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Text("›", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsMenuToggleRow(
    icon: String,
    iconBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    activeTrackColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(iconBg.copy(alpha = 0.25f))
                .border(1.dp, iconBg, CircleShape)
        ) {
            Text(icon, fontSize = 17.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = activeTrackColor,
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedTrackColor = Color(0xFF1E293B)
            )
        )
    }
}

// ==================== 11C. EXACT MOCKUP MY TESTS SCREEN (EXAM SELECTOR + FORT BANNER + SUBJECT CARDS + PERFORMANCE BAR) ====================
@Composable
fun MyTestsScreenUI(
    userProfile: UserProfile,
    selectedExamFilter: String,
    onSelectExamFilter: (String) -> Unit,
    examSubjectsMap: Map<String, List<OnlineQuizMeta>>,
    allOnlineQuizzes: List<OnlineQuizMeta>,
    completedResultsMap: Map<String, List<CompletedTestResult>>,
    isLoadingOnline: Boolean,
    hasCustomSavedQuiz: Boolean,
    customQuizExam: String,
    customQuizSubject: String,
    customQuizTitle: String,
    customQuizCount: Int,
    onOpenSubjectFolder: (String) -> Unit,
    onQuickStartSubjectTest: (OnlineQuizMeta) -> Unit,
    onRefreshOnline: () -> Unit,
    onOpenAdminModal: () -> Unit,
    onStartCustomQuiz: () -> Unit,
    onOpenCustomBuilder: () -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateSolutions: () -> Unit,
    onNavigateSettings: () -> Unit,
    onChangeTargetExamProfile: () -> Unit
) {
    var selectedSubjectChip by remember(selectedExamFilter) { mutableStateOf("ALL") }
    var showExamSwitcherMenu by remember { mutableStateOf(false) }

    val examFullTitle = remember(selectedExamFilter) {
        when (selectedExamFilter.uppercase(Locale.ENGLISH)) {
            "RPSC" -> "Rajasthan Public Service Commission"
            "RSSB" -> "Rajasthan Staff Selection Board"
            "CTET" -> "Central Teacher Eligibility Test"
            "UGC NET" -> "National Eligibility Test"
            else -> "Competitive Exam Preparation"
        }
    }

    val totalTestsInExam = remember(examSubjectsMap) {
        examSubjectsMap.values.sumOf { it.size }
    }

    val displayedSubjectsMap = remember(examSubjectsMap, selectedSubjectChip) {
        if (selectedSubjectChip == "ALL" || !examSubjectsMap.containsKey(selectedSubjectChip)) {
            examSubjectsMap
        } else {
            mapOf(selectedSubjectChip to (examSubjectsMap[selectedSubjectChip] ?: emptyList()))
        }
    }

    // Calculate Overall Score & Rank for this Exam
    val examQuizIds = remember(examSubjectsMap) {
        examSubjectsMap.values.flatten().map { it.id }.toSet()
    }
    val examLatestAttempts = remember(completedResultsMap, examQuizIds) {
        completedResultsMap.filterKeys { it in examQuizIds }.values.mapNotNull { it.lastOrNull() }
    }
    val examOverallScore = remember(examLatestAttempts) {
        if (examLatestAttempts.isNotEmpty()) examLatestAttempts.map { it.percentage }.average() else 0.0
    }
    val rankLabel = remember(examOverallScore, examLatestAttempts.size) {
        when {
            examLatestAttempts.isEmpty() -> "Start Now"
            examOverallScore >= 80.0 -> "Top 5%"
            examOverallScore >= 65.0 -> "Top 15%"
            examOverallScore >= 50.0 -> "Top 30%"
            else -> "Active"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050C24), Color(0xFF09153E), Color(0xFF04091A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 92.dp)
        ) {
            // ==================== 1. TOP HEADER BAR ====================
            AppTopHeaderBar(
                avatarEmoji = userProfile.avatarEmoji,
                totalOnlineTests = allOnlineQuizzes.size,
                isLoadingOnline = isLoadingOnline,
                onMenuClick = onOpenAdminModal,
                onBellClick = onRefreshOnline,
                onProfileClick = onChangeTargetExamProfile
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 2. SELECTED EXAM CARD (White Emblem + RPSC Dropdown + Change Exam Button) ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF0A1636), Color(0xFF102252), Color(0xFF0A1636))
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose.copy(alpha = 0.65f), SecondarySky.copy(alpha = 0.65f))),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showExamSwitcherMenu = !showExamSwitcherMenu },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // White Circle Emblem
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .border(2.dp, SecondarySky, CircleShape)
                    ) {
                        val iconEmoji = when (selectedExamFilter.uppercase(Locale.ENGLISH)) {
                            "RPSC" -> "🏛️"
                            "RSSB" -> "🏢"
                            "CTET" -> "📘"
                            "UGC NET" -> "☸️"
                            else -> "🎓"
                        }
                        Text(iconEmoji, fontSize = 26.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Selected Exam",
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedExamFilter,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("⌄", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = examFullTitle,
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Neon Cyan "Change Exam" Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A1938))
                        .border(1.5.dp, Color(0xFF00F0FF), RoundedCornerShape(14.dp))
                        .clickable { showExamSwitcherMenu = !showExamSwitcherMenu }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⇄ ", color = Color(0xFF00F0FF), fontSize = 13.sp, fontWeight = FontWeight.Black)
                    Text("Change Exam", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Expandable Exam Switcher Row when "Change Exam" is tapped
            if (showExamSwitcherMenu) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("RPSC", "RSSB", "CTET", "UGC NET").forEach { exCode ->
                        val isSel = selectedExamFilter.equals(exCode, ignoreCase = true)
                        val count = allOnlineQuizzes.count { normalizeExamCode(it.examName).equals(exCode, ignoreCase = true) }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSel) PrimaryRose else Color(0xFF111C38))
                                .border(1.dp, if (isSel) Color.White else SecondarySky.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelectExamFilter(exCode)
                                    selectedSubjectChip = "ALL"
                                    showExamSwitcherMenu = false
                                }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(exCode, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            Text("$count Tests", color = Color(0xFFE2E8F0), fontSize = 9.5.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ==================== 3. "MY TESTS" RAJASTHAN FORT BANNER WITH TOTAL TESTS PILL ====================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1A103C), Color(0xFF0F2557), Color(0xFF1E3A8A))
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose, SecondarySky)),
                        shape = RoundedCornerShape(18.dp)
                    )
            ) {
                // Right-Side Rajasthan Fort Illustration in Pure Compose Canvas
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val fortLeft = w * 0.48f
                    val fortColor = Color(0xFFD97706)
                    val fortLight = Color(0xFFFBBF24)

                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(fortLeft, h * 0.35f),
                        size = Size(w * 0.50f, h * 0.65f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(w * 0.55f, h * 0.18f),
                        size = Size(w * 0.10f, h * 0.75f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(fortLight, fortColor)),
                        topLeft = Offset(w * 0.70f, h * 0.12f),
                        size = Size(w * 0.12f, h * 0.80f),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawCircle(color = Color(0xFF15803D), radius = 24f, center = Offset(w * 0.56f, h * 0.90f))
                    drawCircle(color = Color(0xFF166534), radius = 28f, center = Offset(w * 0.68f, h * 0.92f))
                }

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(PrimaryRose)
                                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        ) {
                            Text("📋", fontSize = 24.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "My Tests",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "Subject-wise Test Series for $selectedExamFilter",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Right Total Tests Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF08142C).copy(alpha = 0.92f))
                            .border(1.dp, SecondarySky, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📊", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total Tests", color = Color(0xFFCBD5E1), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "$totalTestsInExam",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ==================== 4. HORIZONTAL FILTER PILLS (All Subjects | Subject 1 | Subject 2 | + Custom) ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isAllSelected = selectedSubjectChip == "ALL"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isAllSelected) Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C)))
                            else Brush.horizontalGradient(listOf(Color(0xFF0E1B3D), Color(0xFF0E1B3D)))
                        )
                        .border(1.dp, if (isAllSelected) Color.White.copy(alpha = 0.6f) else SecondarySky.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .clickable { selectedSubjectChip = "ALL" }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "📖 All Subjects",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                examSubjectsMap.keys.forEach { subName ->
                    val isSel = selectedSubjectChip == subName
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSel) Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C)))
                                else Brush.horizontalGradient(listOf(Color(0xFF0E1B3D), Color(0xFF0E1B3D)))
                            )
                            .border(1.dp, if (isSel) Color.White.copy(alpha = 0.6f) else SecondarySky.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .clickable { selectedSubjectChip = subName }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "🌐 $subName",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // "+ Custom Test" Chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF0E1B3D))
                        .border(1.dp, AmberGold.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                        .clickable { onOpenCustomBuilder() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "➕ Custom Test",
                        color = AmberGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 5. SUBJECT SERIES CARDS (EXACT MOCKUP LAYOUT) ====================
            if (displayedSubjectsMap.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0B1636))
                        .border(1.dp, SecondarySky.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📂", fontSize = 38.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$selectedExamFilter में अभी कोई ऑनलाइन टेस्ट नहीं है",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "ऊपर 'Change Exam' से दूसरी परीक्षा चुनें या Teacher Admin से $selectedExamFilter में नया टेस्ट पब्लिश करें।",
                        color = TextMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                val ringColors = listOf(Color(0xFF00F0FF), CorrectGreen, AmberGold, PrimaryRose, SkippedPurple)
                val subjectIcons = listOf("🏛️", "🌍", "⚖️", "📈", "👥", "🎵", "📘")

                displayedSubjectsMap.entries.forEachIndexed { idx, (subjectName, testList) ->
                    val totalQsInSubject = testList.sumOf { it.questionCount }
                    val avgTimeMin = if (testList.isNotEmpty()) testList.map { it.timeMinutes }.average().roundToInt() else 60
                    val completedInSub = testList.count { completedResultsMap.containsKey(it.id) }
                    val progressPct = if (testList.isNotEmpty()) {
                        ((completedInSub.toFloat() / testList.size.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                    } else 0
                    val nextQuizToStart = testList.firstOrNull { !completedResultsMap.containsKey(it.id) } ?: testList.firstOrNull()
                    val ringColor = ringColors[idx % ringColors.size]
                    val subEmoji = when {
                        subjectName.contains("Music", true) || subjectName.contains("संगीत", true) -> "🎵"
                        subjectName.contains("Sociology", true) || subjectName.contains("समाज", true) -> "👥"
                        subjectName.contains("History", true) || subjectName.contains("इतिहास", true) -> "🏰"
                        subjectName.contains("Geo", true) || subjectName.contains("भूगोल", true) -> "🌍"
                        subjectName.contains("Polity", true) || subjectName.contains("संविधान", true) -> "🏛️"
                        else -> subjectIcons[idx % subjectIcons.size]
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF0B1636), Color(0xFF102048), Color(0xFF0B1636))
                                )
                            )
                            .border(
                                width = 1.2.dp,
                                brush = Brush.horizontalGradient(listOf(PrimaryRose.copy(alpha = 0.55f), SecondarySky.copy(alpha = 0.55f))),
                                shape = RoundedCornerShape(18.dp)
                            )
                            .clickable { onOpenSubjectFolder(subjectName) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Circular Subject Emblem
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(ringColor.copy(alpha = 0.35f), Color(0xFF09132C))
                                    )
                                )
                                .border(2.dp, ringColor, CircleShape)
                        ) {
                            Text(subEmoji, fontSize = 28.sp)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Middle Subject Title & Details
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = subjectName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "${testList.size} Tests  |  $totalQsInSubject Questions",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🕒 ${avgTimeMin} Min", color = Color(0xFFCBD5E1), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("|", color = BorderColor, fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("⭐ 4.${(8 - (idx % 3))}", color = AmberGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Progress Circular Ring
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(end = 10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(52.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { 1f },
                                    color = Color(0xFF1E293B),
                                    strokeWidth = 5.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                                CircularProgressIndicator(
                                    progress = { (progressPct / 100f).coerceIn(0.05f, 1f) },
                                    color = ringColor,
                                    strokeWidth = 5.dp,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text(
                                    text = "$progressPct%",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Progress", color = ringColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }

                        // Right Stacked Buttons: Pink "▶ Start Test" & Outlined "View Details"
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C))))
                                    .clickable {
                                        if (nextQuizToStart != null) onQuickStartSubjectTest(nextQuizToStart)
                                        else onOpenSubjectFolder(subjectName)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("▶ Start Test", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF0A1430))
                                    .border(1.dp, SecondarySky.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                                    .clickable { onOpenSubjectFolder(subjectName) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Text("View Details", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Custom Saved Test Card (If user created a local custom quiz)
            if (hasCustomSavedQuiz && customQuizCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0B1636))
                        .border(1.dp, AmberGold.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .clickable { onStartCustomQuiz() }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AmberGold.copy(alpha = 0.2f))
                                .border(1.5.dp, AmberGold, CircleShape)
                        ) {
                            Text("⚙️", fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Custom: $customQuizTitle", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("[$customQuizExam • $customQuizSubject] $customQuizCount Questions", color = AmberGold, fontSize = 11.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(PrimaryRose)
                            .clickable { onStartCustomQuiz() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("▶ Start", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 6. BOTTOM PERFORMANCE BANNER (Golden Trophy + Overall Score % + Rank) ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF1F123B), Color(0xFF0D1D45), Color(0xFF0A1636))
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose, SecondarySky)),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text("🏆", fontSize = 30.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Your $selectedExamFilter Performance",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                        Text(
                            text = "Keep Practicing, You're on the Right Track!",
                            color = Color(0xFFCBD5E1),
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(Color.White.copy(alpha = 0.16f))
                )

                Column(
                    modifier = Modifier.weight(0.75f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Overall Score", color = Color(0xFFCBD5E1), fontSize = 9.5.sp)
                    Text(
                        text = "%.1f%%".format(examOverallScore),
                        color = Color(0xFF00F0FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(38.dp)
                        .background(Color.White.copy(alpha = 0.16f))
                )

                Column(
                    modifier = Modifier.weight(0.75f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Rank", color = Color(0xFFCBD5E1), fontSize = 9.5.sp)
                    Text(
                        text = "🥇 $rankLabel",
                        color = AmberGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1
                    )
                }
            }
        }

        // ==================== 7. FIXED BOTTOM NAVIGATION BAR (My Tests Highlighted) ====================
        AppBottomNavigationBar(
            selectedTab = 1,
            onNavigateHome = onNavigateHome,
            onNavigateMyTests = {},
            onNavigateSolutions = onNavigateSolutions,
            onNavigateSettings = onNavigateSettings
        )
    }
}

// ==================== 11D. EXACT MOCKUP SOLUTIONS & PDF SCREEN (REPLACING FAVOURITES TAB) ====================
@Composable
fun SolutionsAndPdfScreenUI(
    userProfile: UserProfile,
    allOnlineQuizzes: List<OnlineQuizMeta>,
    completedResultsMap: Map<String, List<CompletedTestResult>>,
    savedResumeState: SavedResumeState?,
    isLoadingOnline: Boolean,
    onRefreshOnline: () -> Unit,
    onOpenSavedResult: (CompletedTestResult) -> Unit,
    onPrintSavedResultPdf: (CompletedTestResult) -> Unit,
    onResumeInterruptedTest: (SavedResumeState) -> Unit,
    onStartOnlineQuiz: (OnlineQuizMeta) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateMyTests: () -> Unit,
    onNavigateSettings: () -> Unit,
    onEditProfile: () -> Unit,
    onOpenAdminModal: () -> Unit
) {
    var selectedFilterTab by remember { mutableStateOf(0) } // 0 = My Submissions, 1 = All Tests, 2 = Stats

    val allAttemptsSorted = remember(completedResultsMap) {
        completedResultsMap.values.flatten().sortedByDescending { it.completedAtMillis }
    }
    val latestPerQuiz = remember(completedResultsMap) {
        completedResultsMap.values.mapNotNull { it.lastOrNull() }
    }
    val overallAvgPct = remember(latestPerQuiz) {
        if (latestPerQuiz.isNotEmpty()) latestPerQuiz.map { it.percentage }.average() else 0.0
    }
    val totalCorrect = remember(allAttemptsSorted) { allAttemptsSorted.sumOf { it.correctCount } }
    val totalWrong = remember(allAttemptsSorted) { allAttemptsSorted.sumOf { it.wrongCount } }
    val totalSkipped = remember(allAttemptsSorted) { allAttemptsSorted.sumOf { it.skippedCount } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF050C24), Color(0xFF09153E), Color(0xFF04091A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 92.dp)
        ) {
            // ==================== 1. TOP HEADER BAR ====================
            AppTopHeaderBar(
                avatarEmoji = userProfile.avatarEmoji,
                totalOnlineTests = allOnlineQuizzes.size,
                isLoadingOnline = isLoadingOnline,
                onMenuClick = onOpenAdminModal,
                onBellClick = onRefreshOnline,
                onProfileClick = onEditProfile
            )

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 2. HERO BANNER ("Solutions & PDF" + Green Ribbon Badge + PDF & Printer Graphic) ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF19103B), Color(0xFF0C1E4A), Color(0xFF0F2963))
                        )
                    )
                    .border(
                        width = 1.3.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose, SecondarySky)),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left Green Check Ribbon Badge
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(62.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(CorrectGreen, Color(0xFF065F46))
                            )
                        )
                        .border(2.5.dp, Color(0xFF00F0FF), CircleShape)
                ) {
                    Text("✔", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Center Title & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Solutions & ",
                            color = Color.White,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "PDF",
                            color = Color(0xFF00F0FF),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Get Complete Solutions\nand Download / Print PDF",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Right PDF Document + Printer Graphic
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(1.dp, PrimaryRose, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                    ) {
                        Text("📄", fontSize = 16.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(PrimaryRose)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text("PDF", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Text("🖨️", fontSize = 26.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 3. 3 FILTER TABS (✔ My Submissions | 🕒 All Tests | 📊 Stats) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SolutionsFilterPill(
                    modifier = Modifier.weight(1.15f),
                    icon = "✔",
                    label = "My Submissions",
                    isSelected = selectedFilterTab == 0,
                    onClick = { selectedFilterTab = 0 }
                )
                SolutionsFilterPill(
                    modifier = Modifier.weight(0.95f),
                    icon = "🕒",
                    label = "All Tests",
                    isSelected = selectedFilterTab == 1,
                    onClick = { selectedFilterTab = 1 }
                )
                SolutionsFilterPill(
                    modifier = Modifier.weight(0.85f),
                    icon = "📊",
                    label = "Stats",
                    isSelected = selectedFilterTab == 2,
                    onClick = { selectedFilterTab = 2 }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 4. TAB CONTENT ====================
            if (selectedFilterTab == 0) {
                // TAB 0: MY SUBMISSIONS (In-Progress Resume Card + All Completed Attempts Cards)
                if (savedResumeState != null) {
                    val answeredCount = savedResumeState.userAnswers.count { it != null }
                    val totalQ = savedResumeState.userAnswers.size
                    val progressPct = if (totalQ > 0) (answeredCount * 100.0 / totalQ) else 0.0
                    val maxM = totalQ * savedResumeState.config.marksPerCorrect
                    val estM = answeredCount * savedResumeState.config.marksPerCorrect

                    SolutionSubmissionCard(
                        examName = normalizeExamCode(savedResumeState.config.examName),
                        title = savedResumeState.config.title,
                        subtitle = "${savedResumeState.config.subject} • Attempt In Progress",
                        dateText = "Paused",
                        durationMin = savedResumeState.config.timeMinutes,
                        scorePercentText = "%.1f%%".format(progressPct),
                        marksText = "${estM.roundToInt()} / ${maxM.roundToInt()}",
                        isCompleted = false,
                        onOpenSolutions = { onResumeInterruptedTest(savedResumeState) },
                        onPrintPdf = { onResumeInterruptedTest(savedResumeState) }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (allAttemptsSorted.isEmpty() && savedResumeState == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0B1636))
                            .border(1.dp, SecondarySky.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📄", fontSize = 38.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "अभी तक कोई सबमिट किया गया टेस्ट नहीं है",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "जब आप कोई टेस्ट सबमिट करेंगे, तो उसका पूरा Solution, Score % और 2-Column A4 PDF डाउनलोड/प्रिंट कार्ड यहाँ दिखेगा।",
                            color = TextMuted,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    allAttemptsSorted.forEach { att ->
                        SolutionSubmissionCard(
                            examName = normalizeExamCode(att.config.examName),
                            title = att.config.title,
                            subtitle = "${att.config.subject} - Attempt 0${att.attemptNumber}",
                            dateText = att.completedDate.ifBlank { "Recent" },
                            durationMin = att.config.timeMinutes,
                            scorePercentText = "%.1f%%".format(att.percentage),
                            marksText = "${att.obtainedMarks.roundToInt()} / ${att.maxMarks.roundToInt()}",
                            isCompleted = true,
                            onOpenSolutions = { onOpenSavedResult(att) },
                            onPrintPdf = { onPrintSavedResultPdf(att) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            } else if (selectedFilterTab == 1) {
                // TAB 1: ALL TESTS (Shows every online test with Completed or Start/In-Progress status)
                if (allOnlineQuizzes.isEmpty()) {
                    Text("कोई ऑनलाइन टेस्ट उपलब्ध नहीं है।", color = TextMuted, fontSize = 13.sp)
                } else {
                    allOnlineQuizzes.forEach { meta ->
                        val latestAtt = completedResultsMap[meta.id]?.lastOrNull()
                        val maxM = (meta.questionCount * meta.marksPerCorrect).roundToInt()
                        SolutionSubmissionCard(
                            examName = normalizeExamCode(meta.examName),
                            title = meta.title,
                            subtitle = "${meta.subject} • ${meta.questionCount} Questions",
                            dateText = latestAtt?.completedDate ?: meta.publishedDate.ifBlank { "Live" },
                            durationMin = meta.timeMinutes,
                            scorePercentText = if (latestAtt != null) "%.1f%%".format(latestAtt.percentage) else "0.0%",
                            marksText = if (latestAtt != null) "${latestAtt.obtainedMarks.roundToInt()} / $maxM" else "0 / $maxM",
                            isCompleted = latestAtt != null,
                            onOpenSolutions = {
                                if (latestAtt != null) onOpenSavedResult(latestAtt)
                                else onStartOnlineQuiz(meta)
                            },
                            onPrintPdf = {
                                if (latestAtt != null) onPrintSavedResultPdf(latestAtt)
                                else onStartOnlineQuiz(meta)
                            }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            } else {
                // TAB 2: STATS SUMMARY
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0B1636))
                        .border(1.dp, SecondarySky.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("📊 Overall Submissions & Accuracy Stats", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatInfoBox(Modifier.weight(1f), "${allAttemptsSorted.size}", "SUBMISSIONS", SecondarySky)
                        StatInfoBox(Modifier.weight(1f), "${latestPerQuiz.size}", "UNIQUE TESTS", PrimaryRose)
                        StatInfoBox(Modifier.weight(1f), "%.1f%%".format(overallAvgPct), "AVG SCORE", AmberGold)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatInfoBox(Modifier.weight(1f), "$totalCorrect", "CORRECT", CorrectGreen)
                        StatInfoBox(Modifier.weight(1f), "$totalWrong", "WRONG", WrongRed)
                        StatInfoBox(Modifier.weight(1f), "$totalSkipped", "SKIPPED", SkippedPurple)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==================== 5. BOTTOM MOTIVATIONAL BANNER ("Keep Practicing!" + "Take New Test ->") ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF241038), Color(0xFF0F1E46), Color(0xFF0A1636))
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.horizontalGradient(listOf(PrimaryRose, SecondarySky)),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🏆", fontSize = 30.sp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Keep Practicing!",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "You're One Step Closer to Your Dream!",
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C))))
                        .clickable { onNavigateMyTests() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Take New Test ➔",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ==================== 6. FIXED BOTTOM NAVIGATION BAR (Solutions Highlighted) ====================
        AppBottomNavigationBar(
            selectedTab = 2,
            onNavigateHome = onNavigateHome,
            onNavigateMyTests = onNavigateMyTests,
            onNavigateSolutions = {},
            onNavigateSettings = onNavigateSettings
        )
    }
}

@Composable
fun SolutionsFilterPill(
    modifier: Modifier,
    icon: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (isSelected) Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C)))
                else Brush.horizontalGradient(listOf(Color(0xFF0D1838), Color(0xFF0A122C)))
            )
            .border(
                width = 1.dp,
                color = if (isSelected) Color.White.copy(alpha = 0.65f) else SecondarySky.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SolutionSubmissionCard(
    examName: String,
    title: String,
    subtitle: String,
    dateText: String,
    durationMin: Int,
    scorePercentText: String,
    marksText: String,
    isCompleted: Boolean,
    onOpenSolutions: () -> Unit,
    onPrintPdf: () -> Unit
) {
    val badgeColor = when (examName.uppercase(Locale.ENGLISH)) {
        "RPSC" -> PrimaryRose
        "RSSB" -> Color(0xFF1D4ED8)
        "CTET" -> Color(0xFF0F766E)
        "UGC NET" -> Color(0xFFB91C1C)
        else -> PrimaryRose
    }
    val emblemIcon = when (examName.uppercase(Locale.ENGLISH)) {
        "RPSC" -> "🏛️"
        "RSSB" -> "🏢"
        "CTET" -> "📘"
        "UGC NET" -> "☸️"
        else -> "🎓"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF0B1636), Color(0xFF10204A), Color(0xFF09132E))
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.horizontalGradient(listOf(PrimaryRose.copy(alpha = 0.6f), SecondarySky.copy(alpha = 0.6f))),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable { onOpenSolutions() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left White Rounded Emblem Box with Exam Pill inside
        Column(
            modifier = Modifier
                .width(74.dp)
                .height(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(1.5.dp, badgeColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(emblemIcon, fontSize = 30.sp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeColor)
                    .padding(vertical = 3.dp)
            ) {
                Text(
                    text = examName,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Middle Column: Title, Subtitle, Date/Time, Score % | Marks
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = Color(0xFFE2E8F0),
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📅 $dateText", color = Color(0xFFCBD5E1), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(8.dp))
                Text("|", color = BorderColor, fontSize = 10.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("🕒 $durationMin Min", color = Color(0xFFCBD5E1), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Score", color = Color(0xFFCBD5E1), fontSize = 9.5.sp)
                    Text(
                        text = scorePercentText,
                        color = Color(0xFF00F0FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(Color.White.copy(alpha = 0.15f))
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                ) {
                    Text("Marks", color = Color(0xFFCBD5E1), fontSize = 9.5.sp)
                    Text(
                        text = marksText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Right Column: Completed/In-Progress Badge + Blue "Download PDF" + Pink "Print PDF"
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCompleted) CorrectGreen else AmberGold)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isCompleted) "✔ Completed" else "🕒 In Progress",
                    color = if (isCompleted) Color.White else Color.Black,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF0284C7), SecondarySky)))
                    .clickable { onPrintPdf() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("⬇️ Download PDF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isCompleted) Brush.horizontalGradient(listOf(PrimaryRose, Color(0xFFBE123C)))
                        else Brush.horizontalGradient(listOf(Color(0xFF1E293B), Color(0xFF334155)))
                    )
                    .border(1.dp, if (isCompleted) PrimaryRose else SecondarySky.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .clickable {
                        if (isCompleted) onPrintPdf() else onOpenSolutions()
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isCompleted) "🖨️ Print PDF" else "▶ Resume",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ==================== 12. SUBJECT FOLDER TESTS SCREEN (ORDERED + MULTI-ATTEMPT HISTORY + RESULT & RE-TEST BUTTONS) ====================
@Composable
fun SubjectTestsScreenUI(
    examName: String,
    subjectName: String,
    tests: List<OnlineQuizMeta>,
    completedResultsMap: Map<String, List<CompletedTestResult>>,
    isDownloading: Boolean,
    onBack: () -> Unit,
    onStartTest: (OnlineQuizMeta) -> Unit,
    onViewSavedResult: (CompletedTestResult) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HomeBgTop, HomeBgBot)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("← Back", color = PrimaryRose, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "📁 [$examName] $subjectName",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tests.size} उपलब्ध टेस्ट (क्रमानुसार: Unit 1 से आगे)",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(tests) { idx, quiz ->
                val attemptsList = completedResultsMap[quiz.id] ?: emptyList()
                val latestAttempt = attemptsList.lastOrNull()
                var showAllAttempts by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HomeCard)
                        .border(
                            width = 1.dp,
                            color = if (latestAttempt != null) CorrectGreen.copy(alpha = 0.5f) else BorderColor,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${idx + 1}. ${quiz.title}",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        if (latestAttempt != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CorrectGreen.copy(alpha = 0.16f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "✅ ${attemptsList.size}x Attempted",
                                    color = CorrectGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (quiz.subtitle.isNotBlank()) {
                        Text(quiz.subtitle, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val negStr = if (quiz.negativeMark > 0) "-${quiz.negativeMark}" else "No Neg"
                    Text(
                        text = "${quiz.questionCount} प्रश्न • ${quiz.timeMinutes} Min • +${quiz.marksPerCorrect} / $negStr",
                        color = SecondarySky,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Show Latest Attempt Score + Expandable Attempts History (Attempt 1, Attempt 2, Attempt 3...)
                    if (latestAttempt != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(BgDark)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🏆 नवीनतम (Attempt ${latestAttempt.attemptNumber}): %.2f / %.2f".format(
                                        latestAttempt.obtainedMarks,
                                        latestAttempt.maxMarks
                                    ),
                                    color = AmberGold,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "📊 %.1f%%".format(latestAttempt.percentage),
                                    color = CorrectGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (attemptsList.size > 1) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (showAllAttempts) "▲ सभी ${attemptsList.size} Attempts छुपाएं" else "▼ सभी ${attemptsList.size} Attempts का स्कोर देखें",
                                    color = SecondarySky,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showAllAttempts = !showAllAttempts }
                                )

                                if (showAllAttempts) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    attemptsList.forEach { att ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(HomeCard)
                                                .clickable { onViewSavedResult(att) }
                                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Attempt ${att.attemptNumber} (${att.completedDate})",
                                                color = TextMain,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "%.2f / %.2f (%.1f%%) ›".format(
                                                    att.obtainedMarks,
                                                    att.maxMarks,
                                                    att.percentage
                                                ),
                                                color = AmberGold,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (latestAttempt != null) {
                        // 2 Buttons after completion: Result & Re-Test
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                enabled = !isDownloading,
                                onClick = { onViewSavedResult(latestAttempt) },
                                colors = ButtonDefaults.buttonColors(containerColor = SecondarySky),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("📊 Result", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                enabled = !isDownloading,
                                onClick = { onStartTest(quiz) },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(42.dp)
                            ) {
                                Text("🔄 Re-Test", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        Button(
                            enabled = !isDownloading,
                            onClick = { onStartTest(quiz) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Text("▶ Start Now", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatInfoBox(modifier: Modifier, value: String, label: String, valueColor: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HomeBox)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = valueColor, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ==================== 13. TEST SCREEN UI (WITH REVIEW BOOKMARK) ====================
@Composable
fun TestScreenUI(
    testConfig: TestConfig,
    candidateName: String,
    questions: List<Question>,
    currentQIndex: Int,
    userAnswers: List<Int?>,
    isMarkedForReview: Boolean,
    remainingSeconds: Int,
    onBackClick: () -> Unit,
    onToggleReview: () -> Unit,
    onSelectOption: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onOpenPalette: () -> Unit,
    onSubmitClick: () -> Unit
) {
    val q = questions[currentQIndex]
    val answeredCount = userAnswers.count { it != null }
    val letters = listOf("A", "B", "C", "D", "E")

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBackClick,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("←", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    text = testConfig.title.uppercase(),
                    color = PrimaryRose,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val negText = if (testConfig.negativeMark > 0) "-%.2f Wrong".format(testConfig.negativeMark) else "No Negative"
                Text(
                    text = "+%.2f/Qn | $negText".format(testConfig.marksPerCorrect),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(candidateName.uppercase(), color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                val mins = remainingSeconds / 60
                val secs = remainingSeconds % 60
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(PrimaryRose)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "%02d:%02d".format(mins, secs),
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        LinearProgressIndicator(
            progress = { (currentQIndex + 1).toFloat() / questions.size.toFloat() },
            color = PrimaryRose,
            trackColor = BorderColor,
            modifier = Modifier.fillMaxWidth().height(3.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDark)
                .padding(horizontal = 15.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("प्रश्न ${currentQIndex + 1} / ${questions.size}", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (isMarkedForReview) "🔖 Marked for Review" else "🔖 Mark Review",
                color = if (isMarkedForReview) AmberGold else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onToggleReview() }
            )
            Text("उत्तर दिए: $answeredCount", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(15.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardDark)
                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                    .padding(15.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryRose.copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Q. ${currentQIndex + 1}", color = PrimaryRose, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(PrimaryRose)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(q.topic, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Text(
                    text = q.text,
                    color = TextMain,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 15.dp)
                )

                q.options.forEachIndexed { idx, optText ->
                    val isSelected = userAnswers[currentQIndex] == idx
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) PrimaryRose.copy(alpha = 0.12f) else OptionBg)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) PrimaryRose else BorderColor,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelectOption(idx) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) PrimaryRose else BorderColor)
                        ) {
                            Text(
                                text = letters.getOrElse(idx) { "${idx + 1}" },
                                color = if (isSelected) Color.White else TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(optText, color = TextMain, fontSize = 14.sp)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onPrev,
                    colors = ButtonDefaults.buttonColors(containerColor = BgDark),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text("◀ Prev", fontSize = 12.sp, color = TextMain)
                }
                Button(
                    onClick = onSkip,
                    colors = ButtonDefaults.buttonColors(containerColor = BgDark),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text("Skip", fontSize = 12.sp, color = TextMain)
                }
                Button(
                    onClick = onOpenPalette,
                    colors = ButtonDefaults.buttonColors(containerColor = BgDark),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(0.6f).height(38.dp)
                ) {
                    Text("▦", fontSize = 15.sp, color = TextMain)
                }
                Button(
                    onClick = onNext,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text(
                        if (currentQIndex == questions.size - 1) "Submit" else "Next ▶",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onSubmitClick,
                colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp)
            ) {
                Text("✅ SUBMIT EXAM", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

// ==================== 14. RESULT SCREEN UI (WITH PERCENTAGE % & TRUE NEGATIVE SCORE) ====================
@Composable
fun ResultScreenUI(
    testConfig: TestConfig,
    candidateName: String,
    questions: List<Question>,
    userAnswers: List<Int?>,
    timeSpentSeconds: Int,
    onBackHome: () -> Unit,
    onPrintPdf: () -> Unit,
    onRetake: () -> Unit
) {
    var correct = 0
    var wrong = 0
    var skipped = 0
    questions.forEachIndexed { idx, q ->
        val ans = userAnswers.getOrNull(idx)
        if (ans == null) skipped++
        else if (ans == q.correctAnswer) correct++
        else wrong++
    }

    val totalMarks = (correct * testConfig.marksPerCorrect) - (wrong * testConfig.negativeMark)
    val maxMarks = questions.size * testConfig.marksPerCorrect
    val percentage = if (maxMarks > 0) ((totalMarks / maxMarks) * 100.0).coerceIn(0.0, 100.0) else 0.0
    val attempted = correct + wrong
    val accuracy = if (attempted > 0) ((correct.toDouble() / attempted) * 100).roundToInt() else 0
    val letters = listOf("A", "B", "C", "D", "E")

    Column(modifier = Modifier.fillMaxSize().background(BgDark)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBackHome) {
                Text("← Back", color = PrimaryRose, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "EXAM RESULT",
                color = TextMain,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(15.dp)
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("$candidateName | ${testConfig.title}", color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardDark)
                        .border(1.dp, PrimaryRose, RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "%.2f / %.2f".format(totalMarks, maxMarks),
                        color = if (totalMarks < 0) WrongRed else Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(CorrectGreen.copy(alpha = 0.16f))
                            .border(1.dp, CorrectGreen, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "📊 कुल प्रतिशत (Percentage): %.2f%%".format(percentage),
                            color = CorrectGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val negInfo = if (testConfig.negativeMark > 0) "-%.2f Negative".format(testConfig.negativeMark) else "No Negative"
                    Text("Total Marks ($negInfo)", color = TextMuted, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatInfoBox(Modifier.weight(1f), "$correct", "सही", CorrectGreen)
                    StatInfoBox(Modifier.weight(1f), "$wrong", "गलत", WrongRed)
                    StatInfoBox(Modifier.weight(1f), "$skipped", "छूटे", SkippedPurple)
                }
                Spacer(modifier = Modifier.height(8.dp))
                val mins = timeSpentSeconds / 60
                val secs = timeSpentSeconds % 60
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatInfoBox(Modifier.weight(1f), "%.1f%%".format(percentage), "प्रतिशत (%)", AmberGold)
                    StatInfoBox(Modifier.weight(1f), "$accuracy%", "सटीकता", PrimaryRose)
                    StatInfoBox(Modifier.weight(1f), "${mins}m ${secs}s", "समय", SecondarySky)
                }

                Spacer(modifier = Modifier.height(15.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPrintPdf,
                        colors = ButtonDefaults.buttonColors(containerColor = SecondarySky),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("🖨️ Print PDF", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onRetake,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("🔄 Re-Test", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "📝 विस्तृत हल (Solutions & Explanations)",
                    color = PrimaryRose,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            itemsIndexed(questions) { idx, q ->
                val uAns = userAnswers.getOrNull(idx)
                val statusColor = when (uAns) {
                    null -> WrongRed
                    q.correctAnswer -> CorrectGreen
                    else -> WrongRed
                }
                val statusText = when (uAns) {
                    null -> "अनुत्तरित (SKIPPED)"
                    q.correctAnswer -> "सही (CORRECT)"
                    else -> "गलत (INCORRECT)"
                }
                val uAnsText = if (uAns != null) "(${letters.getOrElse(uAns) { "" }}) ${q.options.getOrElse(uAns) { "" }}" else "कोई नहीं"
                val cAnsText = "(${letters.getOrElse(q.correctAnswer) { "" }}) ${q.options.getOrElse(q.correctAnswer) { "" }}"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardDark)
                        .padding(15.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Q${idx + 1}.", color = PrimaryRose, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(PrimaryRose)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(q.topic, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(q.text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
                    Text("आपका उत्तर: $uAnsText", color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("सही उत्तर: $cAnsText", color = CorrectGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(PrimaryRose.copy(alpha = 0.08f))
                            .padding(10.dp)
                    ) {
                        Text("💡 व्याख्या (Solution):", color = PrimaryRose, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(q.solution, color = Color(0xFFE2E8F0), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

// ==================== 15. FULL-SCREEN CUSTOM LOCAL QUIZ BUILDER (ZERO KEYBOARD JITTER) ====================
@Composable
fun CustomQuizScreenUI(
    initialConfig: TestConfig,
    onBack: () -> Unit,
    onSaveCustomQuiz: (TestConfig, String, List<Question>) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE) }
    val examChoices = listOf("RPSC", "RSSB", "CTET", "UGC NET")
    var examName by remember { mutableStateOf(prefs.getString("saved_exam", initialConfig.examName) ?: "RPSC") }
    var subject by remember { mutableStateOf(prefs.getString("saved_subject", initialConfig.subject) ?: "Sociology") }
    var title by remember { mutableStateOf(prefs.getString("saved_title", initialConfig.title) ?: initialConfig.title) }
    var timeStr by remember { mutableStateOf(prefs.getInt("saved_time", initialConfig.timeMinutes).toString()) }
    var marksStr by remember { mutableStateOf(prefs.getFloat("saved_marks", initialConfig.marksPerCorrect.toFloat()).toString()) }
    var negStr by remember { mutableStateOf(prefs.getFloat("saved_neg", initialConfig.negativeMark.toFloat()).toString()) }
    var selectedFileName by remember { mutableStateOf("📂 .TXT फाइल चुनें") }
    var rawTextContent by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedFileName = "📄 ${uri.lastPathSegment ?: "questions.txt"}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                rawTextContent = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding()
    ) {
        // Clean Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("← Back", color = PrimaryRose, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("⚙️ Custom Quiz Builder", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }

        // Stable LazyColumn Form with centered bottom button
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Select Exam Category (परीक्षा चुनें):", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    examChoices.forEach { ex ->
                        val isSel = examName.equals(ex, ignoreCase = true)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSel) PrimaryRose else HomeBox)
                                .border(1.dp, if (isSel) Color.White else BorderColor, RoundedCornerShape(10.dp))
                                .clickable { examName = ex }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(ex, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Subject / Folder Name (विषय)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Test Name / Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = { timeStr = it },
                        label = { Text("Time (Min)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = marksStr,
                        onValueChange = { marksStr = it },
                        label = { Text("Marks") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = negStr,
                        onValueChange = { negStr = it },
                        label = { Text("Negative") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("प्रश्न पेस्ट करें या फाइल चुनें:", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = selectedFileName,
                        color = PrimaryRose,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryRose.copy(alpha = 0.14f))
                            .border(1.dp, PrimaryRose, RoundedCornerShape(8.dp))
                            .clickable { filePicker.launch("text/plain") }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = rawTextContent,
                    onValueChange = { rawTextContent = it },
                    placeholder = { Text("TOPIC: विषय...\\nQ: 1. प्रश्न...\\nA) विकल्प 1\\nB) विकल्प 2\\nANS: A\\nSOL: व्याख्या...", color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
                if (rawTextContent.isNotBlank()) {
                    val previewCount = remember(rawTextContent) { TxtQuestionParser.parse(rawTextContent).size }
                    Text(
                        text = "✅ मान्य प्रश्न: $previewCount",
                        color = if (previewCount > 0) CorrectGreen else WrongRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = {
                        val result = TxtQuestionParser.parseWithValidation(rawTextContent)
                        if (result.questions.isEmpty()) {
                            Toast.makeText(context, "फाइल में कोई मान्य प्रश्न नहीं मिला! ANS: जांचें।", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (result.warnings.isNotEmpty()) {
                            Toast.makeText(context, "${result.warnings.size} प्रश्न अधूरे होने के कारण छोड़े गए।", Toast.LENGTH_LONG).show()
                        }
                        val cfg = TestConfig(
                            id = ProfileAndHistoryStorage.customQuizIdForText(rawTextContent),
                            title = title.ifBlank { "Custom Mock Test" },
                            examName = examName.trim().ifEmpty { "RPSC" },
                            subject = subject.trim().ifEmpty { "General" },
                            timeMinutes = timeStr.toIntOrNull() ?: 60,
                            marksPerCorrect = marksStr.toDoubleOrNull() ?: 2.0,
                            negativeMark = negStr.toDoubleOrNull() ?: 0.0
                        )
                        onSaveCustomQuiz(cfg, rawTextContent, result.questions)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("💾 Save & Load Custom Quiz", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

// ==================== 16. FULL-SCREEN TEACHER ADMIN PORTAL (SWIPE TABS + BOTTOM PUBLISH + LAST USED DEFAULTS) ====================
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdminPortalScreenUI(
    savedToken: String,
    groupedSubjects: Map<String, List<OnlineQuizMeta>>,
    latestQuiz: OnlineQuizMeta?,
    onBack: () -> Unit,
    onActionSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("mock_test_prefs", Context.MODE_PRIVATE) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    var token by remember { mutableStateOf(savedToken) }
    var isBusy by remember { mutableStateOf(false) }

    val existingSubjects = groupedSubjects.keys.toList()

    // Default values loaded from the LAST created/published quiz (or latest online quiz if first time)
    val examChoices = listOf("RPSC", "RSSB", "CTET", "UGC NET")
    val defaultExam = prefs.getString("last_admin_exam", null)
        ?: latestQuiz?.examName
        ?: "RPSC"
    val defaultSubject = prefs.getString("last_admin_subject", null)
        ?: latestQuiz?.subject
        ?: existingSubjects.firstOrNull()
        ?: "Sociology"
    val defaultTitle = prefs.getString("last_admin_title", null)
        ?: latestQuiz?.title
        ?: "Rajasthan SET & UGC NET Mock Test"
    val defaultSubtitle = prefs.getString("last_admin_subtitle", null)
        ?: latestQuiz?.subtitle
        ?: "Target Preparation & Revision Test"
    val defaultTime = prefs.getString("last_admin_time", null)
        ?: latestQuiz?.timeMinutes?.toString()
        ?: "60"
    val defaultMarks = prefs.getString("last_admin_marks", null)
        ?: latestQuiz?.marksPerCorrect?.toString()
        ?: "2.0"
    val defaultNeg = prefs.getString("last_admin_neg", null)
        ?: latestQuiz?.negativeMark?.toString()
        ?: "0.0"

    var examName by remember { mutableStateOf(defaultExam) }
    var subject by remember { mutableStateOf(defaultSubject) }
    var title by remember { mutableStateOf(defaultTitle) }
    var subtitle by remember { mutableStateOf(defaultSubtitle) }
    var timeStr by remember { mutableStateOf(defaultTime) }
    var marksStr by remember { mutableStateOf(defaultMarks) }
    var negStr by remember { mutableStateOf(defaultNeg) }
    var selectedFileName by remember { mutableStateOf("📂 .TXT फाइल चुनें") }
    var rawTextContent by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedFileName = "🔒 ${uri.lastPathSegment ?: "quiz.txt"}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                rawTextContent = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .imePadding()
    ) {
        // Clean Top Bar (Only Back + Title, no cramped top button)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = !isBusy,
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("← Back", color = PrimaryRose, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("🔒 Teacher Admin Panel", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }

        // Tabs synced with smooth HorizontalPager (slides smoothly with finger like phone app drawer!)
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = CardDark,
            contentColor = PrimaryRose
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    if (!isBusy) scope.launch { pagerState.animateScrollToPage(0) }
                },
                text = { Text("Publish New", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    if (!isBusy) scope.launch { pagerState.animateScrollToPage(1) }
                },
                text = { Text("Delete / Manage", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            )
        }

        // Smooth App-Drawer Style HorizontalPager
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isBusy,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page == 0) {
                // PAGE 0: PUBLISH NEW QUIZ
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text("GitHub Fine-Grained PAT (Keystore Encrypted)") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Text("Select Exam Name (परीक्षा चुनें):", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            examChoices.forEach { ex ->
                                val isSel = examName.equals(ex, ignoreCase = true)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSel) PrimaryRose else HomeBox)
                                        .border(1.dp, if (isSel) Color.White else BorderColor, RoundedCornerShape(10.dp))
                                        .clickable { examName = ex }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(ex, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = subject,
                            onValueChange = { subject = it },
                            label = { Text("Subject / Folder Name ($examName के अंदर)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (existingSubjects.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                existingSubjects.forEach { sub ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(HomeBox)
                                            .clickable { subject = sub }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(sub, color = TextMain, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Quiz Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = subtitle,
                            onValueChange = { subtitle = it },
                            label = { Text("Subtitle / Description") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = timeStr,
                                onValueChange = { timeStr = it },
                                label = { Text("Time (Min)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = marksStr,
                                onValueChange = { marksStr = it },
                                label = { Text("Marks") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = negStr,
                                onValueChange = { negStr = it },
                                label = { Text("Negative") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("प्रश्न पेस्ट करें या .TXT चुनें:", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = selectedFileName,
                                color = PrimaryRose,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(PrimaryRose.copy(alpha = 0.14f))
                                    .border(1.dp, PrimaryRose, RoundedCornerShape(8.dp))
                                    .clickable { filePicker.launch("text/plain") }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = rawTextContent,
                            onValueChange = { rawTextContent = it },
                            placeholder = { Text("TOPIC: विषय...\\nQ: 1. प्रश्न...\\nA) विकल्प 1\\nB) विकल्प 2\\nANS: A\\nSOL: व्याख्या...", color = TextMuted, fontSize = 12.sp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        )
                        if (rawTextContent.isNotBlank()) {
                            val previewCount = remember(rawTextContent) { TxtQuestionParser.parse(rawTextContent).size }
                            Text(
                                text = "✅ पब्लिश के लिए तैयार प्रश्न: $previewCount",
                                color = if (previewCount > 0) CorrectGreen else WrongRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        // Bottom Centered Full-Width Publish Button
                        Button(
                            enabled = !isBusy,
                            onClick = {
                                val result = TxtQuestionParser.parseWithValidation(rawTextContent)
                                if (token.isBlank()) {
                                    Toast.makeText(context, "कृपया अपना GitHub Token डालें!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (result.questions.isEmpty()) {
                                    Toast.makeText(context, "कृपया सही प्रश्नों और ANS: वाली .txt फाइल या टेक्स्ट डालें!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (result.warnings.isNotEmpty()) {
                                    Toast.makeText(context, "चेतावनी: ${result.warnings.size} प्रश्नों में ANS: गलत था (छोड़े गए)।", Toast.LENGTH_LONG).show()
                                }
                                // Save these settings as the new default for next time!
                                prefs.edit()
                                    .putString("last_admin_exam", examName.trim().ifEmpty { "RPSC" })
                                    .putString("last_admin_subject", subject.trim().ifEmpty { "General Tests" })
                                    .putString("last_admin_title", title.trim())
                                    .putString("last_admin_subtitle", subtitle.trim())
                                    .putString("last_admin_time", timeStr.trim())
                                    .putString("last_admin_marks", marksStr.trim())
                                    .putString("last_admin_neg", negStr.trim())
                                    .apply()

                                isBusy = true
                                scope.launch {
                                    val res = GitHubCloudService.publishEncryptedQuizToGitHub(
                                        token = token.trim(),
                                        title = title.trim(),
                                        subtitle = subtitle.trim(),
                                        examName = examName.trim().ifEmpty { "RPSC" },
                                        subject = subject.trim().ifEmpty { "General Tests" },
                                        timeMinutes = timeStr.toIntOrNull() ?: 60,
                                        marks = marksStr.toDoubleOrNull() ?: 2.0,
                                        neg = negStr.toDoubleOrNull() ?: 0.0,
                                        rawTxtContent = rawTextContent,
                                        questionCount = result.questions.size
                                    )
                                    isBusy = false
                                    res.onSuccess { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        onActionSuccess(token.trim())
                                        onBack()
                                    }.onFailure { err ->
                                        Toast.makeText(context, "Publish Failed: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text(
                                text = if (isBusy) "⏳ Publishing Encrypted Quiz..." else "🚀 Publish Encrypted Quiz",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            } else {
                // PAGE 1: DELETE / MANAGE QUIZZES & FOLDERS
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (groupedSubjects.isEmpty()) {
                        item {
                            Text("अभी कोई ऑनलाइन टेस्ट मौजूद नहीं है।", color = TextMuted, fontSize = 13.sp)
                        }
                    } else {
                        items(groupedSubjects.entries.toList()) { (subName, testList) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardDark)
                                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📁 $subName (${testList.size})",
                                        color = SecondarySky,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        enabled = !isBusy,
                                        onClick = {
                                            if (token.isBlank()) {
                                                Toast.makeText(context, "पहले Publish टैब में GitHub Token डालें!", Toast.LENGTH_SHORT).show()
                                                return@TextButton
                                            }
                                            isBusy = true
                                            scope.launch {
                                                val res = GitHubCloudService.deleteSubjectFolderFromGitHub(
                                                    token = token.trim(),
                                                    subjectName = subName,
                                                    quizzesInFolder = testList
                                                )
                                                isBusy = false
                                                res.onSuccess { msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    onActionSuccess(token.trim())
                                                }.onFailure { err ->
                                                    Toast.makeText(context, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Text("🗑️ Del Folder", color = WrongRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                HorizontalDivider(color = BorderColor, modifier = Modifier.padding(vertical = 6.dp))
                                testList.forEach { quiz ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "• [${quiz.examName}] ${quiz.title}",
                                            color = TextMain,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            enabled = !isBusy,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            onClick = {
                                                if (token.isBlank()) {
                                                    Toast.makeText(context, "पहले Publish टैब में GitHub Token डालें!", Toast.LENGTH_SHORT).show()
                                                    return@TextButton
                                                }
                                                isBusy = true
                                                scope.launch {
                                                    val res = GitHubCloudService.deleteQuizFromGitHub(token.trim(), quiz)
                                                    isBusy = false
                                                    res.onSuccess { msg ->
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                        onActionSuccess(token.trim())
                                                    }.onFailure { err ->
                                                        Toast.makeText(context, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("🗑️", fontSize = 14.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 17. ESCAPED TWO-COLUMN A4 PDF PRINT HELPER (NO WATERMARK) ====================
@Suppress("StaticFieldLeak")
private var activePrintWebView: WebView? = null
fun printTwoColumnA4ResultPdf(
    context: Context,
    config: TestConfig,
    studentName: String,
    questions: List<Question>,
    userAnswers: List<Int?>
) {
    var correct = 0
    var wrong = 0
    var skipped = 0
    val letters = listOf("A", "B", "C", "D", "E")
    val qHtmlBuilder = StringBuilder()

    fun esc(s: String): String = TextUtils.htmlEncode(s).replace("\n", "<br>")

    questions.forEachIndexed { idx, q ->
        val uAns = userAnswers.getOrNull(idx)
        val printColor = when (uAns) {
            null -> "#ef4444"
            q.correctAnswer -> "#10b981"
            else -> "#ef4444"
        }
        if (uAns == null) skipped++ else if (uAns == q.correctAnswer) correct++ else wrong++

        val uAnsText = if (uAns != null) "(${letters.getOrElse(uAns) { "" }}) ${esc(q.options.getOrElse(uAns) { "" })}" else "कोई नहीं"
        val cAnsText = "(${letters.getOrElse(q.correctAnswer) { "" }}) ${esc(q.options.getOrElse(q.correctAnswer) { "" })}"

        val optsHtml = q.options.mapIndexed { i, opt -> "<div>(${letters.getOrElse(i) { "" }}) ${esc(opt)}</div>" }.joinToString("")
        qHtmlBuilder.append(
            """
            <div class="print-q-box">
                <div class="print-q-head"><span>Q${idx + 1}.</span><span class="print-q-topic">${esc(q.topic)}</span></div>
                <div class="print-q-text">${esc(q.text)}</div>
                <div class="print-options-grid">$optsHtml</div>
                <div class="print-ans-row"><strong>आपका उत्तर:</strong> <span style="color:$printColor">$uAnsText</span></div>
                <div class="print-ans-row"><strong>सही उत्तर:</strong> <span style="color:#10b981">$cAnsText</span></div>
                <div class="print-sol-box"><strong>व्याख्या:</strong> ${esc(q.solution)}</div>
            </div>
            """.trimIndent()
        )
    }

    val totalMarks = (correct * config.marksPerCorrect) - (wrong * config.negativeMark)
    val maxMarks = questions.size * config.marksPerCorrect
    val percentage = if (maxMarks > 0) ((totalMarks / maxMarks) * 100.0).coerceIn(0.0, 100.0) else 0.0
    val attempted = correct + wrong
    val accuracy = if (attempted > 0) ((correct.toDouble() / attempted) * 100).roundToInt() else 0
    val negInfo = if (config.negativeMark > 0) "-%.2f Negative".format(config.negativeMark) else "No Negative"

    val fullHtml = """
        <!DOCTYPE html>
        <html lang="hi">
        <head>
        <meta charset="UTF-8">
        <style>
            @page { size: A4 portrait; margin: 8mm; }
            body { background: white; color: black; font-family: sans-serif; margin: 0; padding: 0; }
            .print-header-box {
                border: 1.5px solid #000;
                padding: 10px 14px;
                border-radius: 6px;
                margin-bottom: 12px;
                text-align: center;
                background: #f8fafc;
            }
            .print-header-box h2 { font-size: 16px; margin: 0 0 4px 0; }
            .print-header-box h3 { font-size: 12px; margin: 0 0 4px 0; color: #333; }
            .print-header-box p { font-size: 11px; margin: 0 0 8px 0; color: #555; }
            .print-score-row {
                display: flex;
                justify-content: space-around;
                border-top: 1px dashed #000;
                padding-top: 6px;
                font-size: 11px;
                font-weight: bold;
            }
            .two-col-container {
                column-count: 2;
                column-gap: 14px;
                column-rule: 1px solid #cbd5e1;
            }
            .print-q-box {
                break-inside: avoid;
                page-break-inside: avoid;
                margin-bottom: 10px;
                padding-bottom: 8px;
                border-bottom: 1px solid #e2e8f0;
            }
            .print-q-head { display: flex; justify-content: space-between; font-weight: bold; font-size: 11px; margin-bottom: 3px; }
            .print-q-topic { background: #e11d48; color: #fff; padding: 1px 6px; font-size: 9px; border-radius: 10px; }
            .print-q-text { font-size: 11px; font-weight: 600; margin-bottom: 4px; line-height: 1.35; }
            .print-options-grid { margin-bottom: 4px; font-size: 10.5px; padding-left: 6px; line-height: 1.3; }
            .print-ans-row { font-size: 10px; margin-bottom: 2px; }
            .print-sol-box { background: #f8fafc; border-left: 3px solid #e11d48; padding: 4px 6px; margin-top: 4px; font-size: 10px; line-height: 1.3; }
        </style>
        </head>
        <body>
            <div class="print-header-box">
                <h2>${esc(config.title)}</h2>
                <h3>Candidate: ${esc(studentName)} | Total Questions: ${questions.size}</h3>
                <p>Marking: +${"%.2f".format(config.marksPerCorrect)} / Correct | $negInfo</p>
                <div class="print-score-row">
                    <span>Score: ${"%.2f".format(totalMarks)} / ${"%.2f".format(maxMarks)} (${"%.1f".format(percentage)}%)</span>
                    <span>Correct: $correct</span>
                    <span>Wrong: $wrong</span>
                    <span>Skipped: $skipped</span>
                    <span>Accuracy: $accuracy%</span>
                </div>
            </div>
            <div class="two-col-container">
                $qHtmlBuilder
            </div>
        </body>
        </html>
    """.trimIndent()

    val webView = WebView(context)
    activePrintWebView = webView
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val adapter = view.createPrintDocumentAdapter(config.title)
            printManager.print("${config.title}_Result", adapter, PrintAttributes.Builder().build())
        }
    }
    webView.loadDataWithBaseURL(null, fullHtml, "text/HTML", "UTF-8", null)
}
