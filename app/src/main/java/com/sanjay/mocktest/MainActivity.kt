package com.sanjay.mocktest

import android.content.Context
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
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
    val subject: String = "General",
    val timeMinutes: Int = 60,
    val marksPerCorrect: Double = 2.0,
    val negativeMark: Double = 0.0
)

data class OnlineQuizMeta(
    val id: String,
    val title: String,
    val subtitle: String,
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

enum class AppScreen { HOME, SUBJECT_TESTS, TEST, RESULT }

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

        // Split cleanly so TOPIC: before Q: stays with that question and never leaks into the previous solution
        val lines = rawText.replace("\r\n", "\n").lines()
        val blocks = mutableListOf<MutableList<String>>()
        var currentBlock = mutableListOf<String>()
        var currentHasQ = false
        var currentHasOptionsOrAns = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val isTopicStart = line.matches(Regex("^(?i)TOPIC:.*"))
            val isQStart = line.matches(Regex("^(?i)(Q:|Question:).*"))
            val isOptOrAns = line.matches(Regex("^(?i)([A-E][).]|ANS:|Answer:|SOL:|SOLUTION:).*"))

            if (isTopicStart && currentHasQ) {
                // New question's TOPIC: line arrived -> close previous question block first!
                blocks.add(currentBlock)
                currentBlock = mutableListOf()
                currentHasQ = false
                currentHasOptionsOrAns = false
            } else if (isQStart && currentHasQ) {
                // New Q: arrived without a preceding TOPIC: -> close previous question block!
                blocks.add(currentBlock)
                currentBlock = mutableListOf()
                currentHasQ = false
                currentHasOptionsOrAns = false
            }

            currentBlock.add(line)
            if (isQStart) currentHasQ = true
            if (isOptOrAns) currentHasOptionsOrAns = true
        }
        if (currentBlock.isNotEmpty()) blocks.add(currentBlock)

        var lastSeenTopic = "General"
        blocks.forEachIndexed { idx, block ->
            var qText = ""
            var topic = lastSeenTopic
            var solution = "व्याख्या उपलब्ध नहीं है।"
            var answerIndex: Int? = null
            val options = mutableListOf<String>()
            var inSolution = false

            for (line in block) {
                when {
                    line.matches(Regex("^(?i)TOPIC:.*")) -> {
                        topic = line.replace(Regex("^(?i)TOPIC:\\s*"), "").trim().ifEmpty { lastSeenTopic }
                        lastSeenTopic = topic
                        inSolution = false
                    }
                    line.matches(Regex("^(?i)(Q:|Question:).*")) -> {
                        qText = line.replace(Regex("^(?i)(Q:|Question:)\\s*"), "")
                        inSolution = false
                    }
                    line.matches(Regex("^(?i)[A-E][).].*")) -> {
                        options.add(line.replace(Regex("^(?i)[A-E][).]\\s*"), ""))
                        inSolution = false
                    }
                    line.matches(Regex("^(?i)(ANS:|Answer:).*")) -> {
                        val ansChar = line.replace(Regex("^(?i)(ANS:|Answer:)\\s*"), "").trim().uppercase()
                        val map = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3, "E" to 4)
                        answerIndex = map[ansChar] ?: ansChar.toIntOrNull()?.minus(1)
                        inSolution = false
                    }
                    line.matches(Regex("^(?i)(SOL:|SOLUTION:).*")) -> {
                        solution = line.replace(Regex("^(?i)(SOL:|SOLUTION:)\\s*"), "")
                        inSolution = true
                    }
                    inSolution -> {
                        solution += "\n$line"
                    }
                    options.isEmpty() -> {
                        qText = if (qText.isEmpty()) line else "$qText\n$line"
                    }
                }
            }

            if (qText.isNotEmpty() && options.size >= 2) {
                if (answerIndex == null || answerIndex !in options.indices) {
                    warnings.add("Q${idx + 1}: ANS: गलत या गायब है")
                } else {
                    parsed.add(
                        Question(
                            topic = topic,
                            text = qText.replace("<br>", "\n"),
                            options = options,
                            correctAnswer = answerIndex,
                            solution = solution.replace("<br>", "\n")
                        )
                    )
                }
            }
        }
        return ParseResult(parsed, warnings)
    }

    fun parse(rawText: String): List<Question> = parseWithValidation(rawText).questions
}

// ==================== 7. WALL-CLOCK RESUME STORAGE HELPER ====================
object ResumeStorage {
    private const val KEY_RESUME_JSON = "active_resume_state_v2"

    fun save(context: Context, state: SavedResumeState) {
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
            put("subject", state.config.subject)
            put("timeMinutes", state.config.timeMinutes)
            put("marksPerCorrect", state.config.marksPerCorrect)
            put("negativeMark", state.config.negativeMark)
            put("rawTxt", state.rawTxt)
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
                    id = obj.optString("id", "custom"),
                    title = obj.optString("title", "Mock Test"),
                    subtitle = obj.optString("subtitle", ""),
                    subject = obj.optString("subject", "General"),
                    timeMinutes = obj.optInt("timeMinutes", 60),
                    marksPerCorrect = obj.optDouble("marksPerCorrect", 2.0),
                    negativeMark = obj.optDouble("negativeMark", 0.0)
                ),
                rawTxt = obj.getString("rawTxt"),
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

// ==================== 8. GITHUB CLOUD SYNC (WITH OFFLINE CACHE & HTTP CHECKS) ====================
object GitHubCloudService {
    suspend fun fetchOnlineQuizzes(): List<OnlineQuizMeta> = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/quizzes/index.json?ref=$GITHUB_BRANCH&t=${System.currentTimeMillis()}"
        val apiConn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            useCaches = false
            setRequestProperty("Accept", "application/vnd.github.raw+json")
            setRequestProperty("Cache-Control", "no-cache")
        }

        val jsonText = if (apiConn.responseCode == 200) {
            apiConn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val rawUrl = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/quizzes/index.json?t=${System.currentTimeMillis()}"
            val rawConn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            if (rawConn.responseCode != 200) return@withContext emptyList()
            rawConn.inputStream.bufferedReader().use { it.readText() }
        }

        val arr = JSONArray(jsonText)
        val list = mutableListOf<OnlineQuizMeta>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val rawSubject = obj.optString("subject", "").trim()
            val fileUrl = obj.optString("fileUrl", "")
            val derivedPath = obj.optString("repoPath", "").ifBlank {
                val marker = "/$GITHUB_BRANCH/"
                if (fileUrl.contains(marker)) fileUrl.substringAfter(marker) else ""
            }
            list.add(
                OnlineQuizMeta(
                    id = obj.optString("id", "quiz_$i"),
                    title = obj.optString("title", "Mock Test"),
                    subtitle = obj.optString("subtitle", "Online Practice Test"),
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
        list
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
        subject: String,
        timeMinutes: Int,
        marks: Double,
        neg: Double,
        rawTxtContent: String,
        questionCount: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safeFolder = subject.trim()
                .lowercase(Locale.ENGLISH)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifEmpty { "general" }

            val fileId = "quiz_${System.currentTimeMillis()}"
            val encPath = "quizzes/$safeFolder/$fileId.enc"
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
                put("subject", subject.trim().ifEmpty { "General Tests" })
                put("timeMinutes", timeMinutes)
                put("marksPerCorrect", marks)
                put("negativeMark", neg)
                put("questionCount", questionCount)
                put("fileUrl", rawFileUrl)
                put("repoPath", encPath)
                put("publishedDate", today)
            }

            val updatedArray = JSONArray()
            updatedArray.put(newEntry)
            for (i in 0 until currentArray.length()) {
                updatedArray.put(currentArray.getJSONObject(i))
            }

            putFileToGitHub(
                token = token,
                path = indexPath,
                content = updatedArray.toString(2),
                commitMsg = "Update index.json: $subject - $title",
                sha = existingFile?.second
            )

            Result.success("टेस्ट '$subject' फोल्डर में पब्लिश हो गया!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteQuizFromGitHub(token: String, quiz: OnlineQuizMeta): Result<String> = withContext(Dispatchers.IO) {
        try {
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

            if (quiz.repoPath.isNotBlank()) {
                deleteFileFromGitHub(token, quiz.repoPath, "Remove file for ${quiz.title}")
            }

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

            quizzesInFolder.forEach { q ->
                if (q.repoPath.isNotBlank()) {
                    try {
                        deleteFileFromGitHub(token, q.repoPath, "Cleanup $subjectName")
                    } catch (_: Exception) {
                    }
                }
            }

            Result.success("फोल्डर '$subjectName' और इसके सभी टेस्ट डिलीट हो गए!")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getFileFromGitHub(token: String, path: String): Pair<String, String>? {
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$path?ref=$GITHUB_BRANCH"
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) return null

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(response)
        val sha = json.getString("sha")
        val encoded = json.getString("content").replace("\n", "")
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        return Pair(decoded, sha)
    }

    private fun putFileToGitHub(
        token: String,
        path: String,
        content: String,
        commitMsg: String,
        sha: String? = null
    ) {
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$path"
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")

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
    }

    private fun deleteFileFromGitHub(token: String, path: String, commitMsg: String) {
        val fileInfo = getFileFromGitHub(token, path) ?: return
        val apiUrl = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/contents/$path"
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("Content-Type", "application/json")

        val body = JSONObject().apply {
            put("message", commitMsg)
            put("sha", fileInfo.second)
            put("branch", GITHUB_BRANCH)
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        conn.responseCode
    }
}

// ==================== 9. MAIN ACTIVITY ====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var previousScreenBeforeTest by remember { mutableStateOf(AppScreen.HOME) }
    var selectedSubjectFolder by remember { mutableStateOf("") }

    var testConfig by remember { mutableStateOf(TestConfig()) }
    var activeRawTxt by remember { mutableStateOf("") }
    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var candidateName by remember { mutableStateOf(prefs.getString("student_name", "") ?: "") }
    var hasCustomSavedQuiz by remember { mutableStateOf(false) }

    var savedResumeState by remember { mutableStateOf<SavedResumeState?>(null) }

    // Test Active State
    var currentQIndex by remember { mutableStateOf(0) }
    var userAnswers by remember { mutableStateOf<List<Int?>>(emptyList()) }
    var markedForReview by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var remainingSeconds by remember { mutableStateOf(0) }

    var showPaletteSheet by remember { mutableStateOf(false) }
    var showSubmitConfirm by remember { mutableStateOf(false) }
    var showExitTestConfirm by remember { mutableStateOf(false) }

    // Modals
    var showCustomQuizModal by remember { mutableStateOf(false) }
    var showPinGateModal by remember { mutableStateOf(false) }
    var showAdminModal by remember { mutableStateOf(false) }
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
            AppScreen.SUBJECT_TESTS -> currentScreen = AppScreen.HOME
            AppScreen.HOME -> {
                when {
                    showCustomQuizModal -> showCustomQuizModal = false
                    showPinGateModal -> showPinGateModal = false
                    showAdminModal -> showAdminModal = false
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

    LaunchedEffect(Unit) {
        savedResumeState = ResumeStorage.load(context)

        val savedTxt = prefs.getString("saved_custom_txt", null)
        if (!savedTxt.isNullOrBlank()) {
            val parsed = TxtQuestionParser.parse(savedTxt)
            if (parsed.isNotEmpty()) {
                hasCustomSavedQuiz = true
                if (savedResumeState == null) {
                    questions = parsed
                    activeRawTxt = savedTxt
                    testConfig = TestConfig(
                        id = "custom",
                        title = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                        timeMinutes = prefs.getInt("saved_time", 60),
                        marksPerCorrect = prefs.getFloat("saved_marks", 2.0f).toDouble(),
                        negativeMark = prefs.getFloat("saved_neg", 0.0f).toDouble()
                    )
                }
            }
        }

        isLoadingOnline = true
        try {
            onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes()
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .systemBarsPadding()
    ) {
        when (currentScreen) {
            AppScreen.HOME -> {
                HomeScreenUI(
                    candidateName = candidateName,
                    onCandidateNameChange = {
                        candidateName = it
                        prefs.edit().putString("student_name", it.trim()).apply()
                    },
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
                    groupedSubjects = groupedBySubject,
                    totalOnlineTests = onlineQuizzes.size,
                    isLoadingOnline = isLoadingOnline,
                    hasCustomSavedQuiz = hasCustomSavedQuiz,
                    customQuizTitle = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
                    customQuizCount = TxtQuestionParser.parse(prefs.getString("saved_custom_txt", "") ?: "").size,
                    onRefreshOnline = {
                        scope.launch {
                            isLoadingOnline = true
                            try {
                                onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes()
                            } catch (_: Exception) {
                            }
                            isLoadingOnline = false
                        }
                    },
                    onOpenSubjectFolder = { subjectName ->
                        selectedSubjectFolder = subjectName
                        currentScreen = AppScreen.SUBJECT_TESTS
                    },
                    onStartCustomQuiz = {
                        if (candidateName.isBlank()) {
                            Toast.makeText(context, "कृपया पहले ऊपर अपना नाम लिखें!", Toast.LENGTH_SHORT).show()
                        } else {
                            val savedTxt = prefs.getString("saved_custom_txt", "") ?: ""
                            val parsed = TxtQuestionParser.parse(savedTxt)
                            if (parsed.isNotEmpty()) {
                                activeRawTxt = savedTxt
                                questions = parsed
                                val tMins = prefs.getInt("saved_time", 60)
                                testConfig = TestConfig(
                                    id = "custom",
                                    title = prefs.getString("saved_title", "Custom Mock Test") ?: "Custom Mock Test",
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
                        }
                    },
                    onOpenCustomModal = { showCustomQuizModal = true },
                    onOpenAdminModal = { showPinGateModal = true }
                )
            }

            AppScreen.SUBJECT_TESTS -> {
                val folderTests = groupedBySubject[selectedSubjectFolder] ?: emptyList()
                SubjectTestsScreenUI(
                    subjectName = selectedSubjectFolder,
                    tests = folderTests,
                    isDownloading = isDownloadingQuiz,
                    onBack = { currentScreen = AppScreen.HOME },
                    onStartTest = { meta -> launchOnlineQuiz(meta) }
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
                    timeSpentSeconds = ((testConfig.timeMinutes * 60) - remainingSeconds).coerceAtLeast(0),
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

        if (showCustomQuizModal) {
            CustomQuizDialog(
                initialConfig = testConfig,
                onDismiss = { showCustomQuizModal = false },
                onSaveCustomQuiz = { newCfg, rawTxt, parsedList ->
                    testConfig = newCfg
                    activeRawTxt = rawTxt
                    questions = parsedList
                    hasCustomSavedQuiz = true
                    prefs.edit()
                        .putString("saved_custom_txt", rawTxt)
                        .putString("saved_title", newCfg.title)
                        .putInt("saved_time", newCfg.timeMinutes)
                        .putFloat("saved_marks", newCfg.marksPerCorrect.toFloat())
                        .putFloat("saved_neg", newCfg.negativeMark.toFloat())
                        .apply()
                    showCustomQuizModal = false
                    Toast.makeText(context, "Custom Quiz सेव हो गया (${parsedList.size} प्रश्न)!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // PIN Gate Before Opening Admin Panel
        if (showPinGateModal) {
            AdminPinGateDialog(
                isSettingNewPin = !SecureAdminStore.hasPin(context),
                onDismiss = { showPinGateModal = false },
                onPinVerified = { pin ->
                    if (!SecureAdminStore.hasPin(context)) {
                        SecureAdminStore.setPin(context, pin)
                    }
                    showPinGateModal = false
                    showAdminModal = true
                }
            )
        }

        // Admin Portal Dialog (Uses Keystore-Encrypted PAT)
        if (showAdminModal) {
            AdminPortalDialog(
                savedToken = SecureAdminStore.loadPat(context),
                groupedSubjects = groupedBySubject,
                onDismiss = { showAdminModal = false },
                onActionSuccess = { tokenUsed ->
                    SecureAdminStore.savePat(context, tokenUsed)
                    scope.launch {
                        isLoadingOnline = true
                        try {
                            onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes()
                        } catch (_: Exception) {
                        }
                        isLoadingOnline = false
                    }
                }
            )
        }
    }
}

// ==================== 10. ADMIN PIN LOCK DIALOG ====================
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

// ==================== 11. HOME SCREEN UI ====================
@Composable
fun HomeScreenUI(
    candidateName: String,
    onCandidateNameChange: (String) -> Unit,
    savedResumeState: SavedResumeState?,
    onResumeTest: (SavedResumeState) -> Unit,
    onDiscardResume: () -> Unit,
    groupedSubjects: Map<String, List<OnlineQuizMeta>>,
    totalOnlineTests: Int,
    isLoadingOnline: Boolean,
    hasCustomSavedQuiz: Boolean,
    customQuizTitle: String,
    customQuizCount: Int,
    onRefreshOnline: () -> Unit,
    onOpenSubjectFolder: (String) -> Unit,
    onStartCustomQuiz: () -> Unit,
    onOpenCustomModal: () -> Unit,
    onOpenAdminModal: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HomeBgTop, HomeBgBot)))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(HomeCard)
                .border(1.dp, PrimaryRose.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (candidateName.isNotBlank()) "नमस्ते, ${candidateName.trim()}! 👋" else "स्वागत है! 👋",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "अपनी तैयारी को परखें और रोज़ाना नए मॉक टेस्ट दें",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(PrimaryRose.copy(alpha = 0.15f))
                ) {
                    Text("🎓", fontSize = 20.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = candidateName,
                onValueChange = onCandidateNameChange,
                placeholder = { Text("यहाँ अपना नाम लिखें (रिजल्ट के लिए)...", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HomeBgTop, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryRose,
                    unfocusedBorderColor = BorderColor,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatInfoBox(Modifier.weight(1f), "${groupedSubjects.size}", "SUBJECTS", SecondarySky)
                StatInfoBox(Modifier.weight(1f), "$totalOnlineTests", "LIVE TESTS", PrimaryRose)
            }
        }

        if (savedResumeState != null) {
            Spacer(modifier = Modifier.height(16.dp))
            val answered = savedResumeState.userAnswers.count { it != null }
            val total = savedResumeState.userAnswers.size
            val minsLeft = savedResumeState.remainingSeconds / 60
            val secsLeft = savedResumeState.remainingSeconds % 60
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AmberGold.copy(alpha = 0.12f))
                    .border(1.dp, AmberGold, RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text = "⏳ अधूरा टेस्ट जारी रखें (Resume)",
                    color = AmberGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = savedResumeState.config.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "उत्तर दिए: $answered/$total • बचा समय: %02d:%02d".format(minsLeft, secsLeft),
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onResumeTest(savedResumeState) },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "▶ Resume Test",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    OutlinedButton(
                        onClick = onDiscardResume,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("हटाएं", color = WrongRed, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📚 विषय के अनुसार टेस्ट (Subjects)",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onRefreshOnline,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isLoadingOnline) "Loading..." else "🔄 Refresh",
                    color = SecondarySky,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "अभी कोई ऑनलाइन टेस्ट उपलब्ध नहीं है। ऊपर Refresh दबाएं।",
                    color = TextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val folderColors = listOf(PrimaryRose, SecondarySky, CorrectGreen, AmberGold, SkippedPurple)
            groupedSubjects.entries.forEachIndexed { index, (subjectName, testList) ->
                val accent = folderColors[index % folderColors.size]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(HomeCard)
                        .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                        .clickable { onOpenSubjectFolder(subjectName) }
                        .padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.16f))
                    ) {
                        Text("📁", fontSize = 22.sp)
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = subjectName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${testList.size} मॉक टेस्ट उपलब्ध",
                            color = TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        text = "खोलें ›",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "📂 खुद का कस्टम टेस्ट (.TXT फाइल से)",
            color = TextMain,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(HomeCard)
                .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                .padding(15.dp)
        ) {
            if (hasCustomSavedQuiz && customQuizCount > 0) {
                Text(
                    text = "सेव किया गया टेस्ट: $customQuizTitle ($customQuizCount प्रश्न)",
                    color = TextMain,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onStartCustomQuiz,
                        colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("▶ टेस्ट शुरू करें", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onOpenCustomModal,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("बदलें", color = TextMain, fontSize = 13.sp)
                    }
                }
            } else {
                Text(
                    text = "अपने फोन की किसी भी .txt फाइल से तुरंत ऑफलाइन टेस्ट बनाएं और अभ्यास करें।",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                OutlinedButton(
                    onClick = onOpenCustomModal,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚙️ Create Custom Quiz / Upload .TXT", color = TextMain, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = onOpenAdminModal) {
                Text("🔒 Teacher Admin: Publish / Delete Tests", color = TextMuted, fontSize = 12.sp)
            }
        }
    }
}

// ==================== 12. SUBJECT FOLDER TESTS SCREEN ====================
@Composable
fun SubjectTestsScreenUI(
    subjectName: String,
    tests: List<OnlineQuizMeta>,
    isDownloading: Boolean,
    onBack: () -> Unit,
    onStartTest: (OnlineQuizMeta) -> Unit
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
                    text = "📁 $subjectName",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tests.size} उपलब्ध टेस्ट",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tests) { quiz ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(HomeCard)
                        .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    Text(quiz.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (quiz.subtitle.isNotBlank()) {
                        Text(quiz.subtitle, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val negStr = if (quiz.negativeMark > 0) "-${quiz.negativeMark}" else "No Neg"
                        Text(
                            text = "${quiz.questionCount} प्रश्न • ${quiz.timeMinutes} Min • $negStr",
                            color = SecondarySky,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            enabled = !isDownloading,
                            onClick = { onStartTest(quiz) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("▶ Start Test", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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

// ==================== 14. RESULT SCREEN UI (TRUE NEGATIVE SCORE SUPPORT) ====================
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardDark)
                        .border(1.dp, PrimaryRose, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "%.2f / %.2f".format(totalMarks, maxMarks),
                        color = if (totalMarks < 0) WrongRed else Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
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
                val avgSec = if (questions.isNotEmpty()) timeSpentSeconds / questions.size else 0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatInfoBox(Modifier.weight(1f), "$accuracy%", "सटीकता", PrimaryRose)
                    StatInfoBox(Modifier.weight(1f), "${mins}m ${secs}s", "समय", PrimaryRose)
                    StatInfoBox(Modifier.weight(1f), "${avgSec}s", "औसत/प्रश्न", PrimaryRose)
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
                        colors = ButtonDefaults.buttonColors(containerColor = WrongRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("🔄 Retake Test", color = Color.White, fontWeight = FontWeight.Bold)
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

// ==================== 15. CUSTOM LOCAL QUIZ MODAL ====================
@Composable
fun CustomQuizDialog(
    initialConfig: TestConfig,
    onDismiss: () -> Unit,
    onSaveCustomQuiz: (TestConfig, String, List<Question>) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initialConfig.title) }
    var timeStr by remember { mutableStateOf(initialConfig.timeMinutes.toString()) }
    var marksStr by remember { mutableStateOf(initialConfig.marksPerCorrect.toString()) }
    var negStr by remember { mutableStateOf(initialConfig.negativeMark.toString()) }
    var selectedFileName by remember { mutableStateOf("📂 Click to select .txt file") }
    var rawTextContent by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedFileName = "📄 Selected: ${uri.lastPathSegment ?: "questions.txt"}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                rawTextContent = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).imePadding(),
        containerColor = CardDark,
        title = { Text("⚙️ Custom Quiz Configuration", color = PrimaryRose, fontWeight = FontWeight.Black, fontSize = 17.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Test Name / Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = { timeStr = it },
                        label = { Text("Time (Min)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = marksStr,
                        onValueChange = { marksStr = it },
                        label = { Text("Marks/Correct") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = negStr,
                    onValueChange = { negStr = it },
                    label = { Text("Negative Marking (0 for None)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryRose.copy(alpha = 0.08f))
                        .border(1.dp, PrimaryRose, RoundedCornerShape(8.dp))
                        .clickable { filePicker.launch("text/plain") }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(selectedFileName, color = TextMain, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("या नीचे सीधे प्रश्न Copy-Paste करें:", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = rawTextContent,
                    onValueChange = { rawTextContent = it },
                    placeholder = { Text("Q: प्रश्न...\nA) विकल्प 1\nB) विकल्प 2\nANS: A\nSOL: व्याख्या...", color = TextMuted, fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth().height(130.dp)
                )
                if (rawTextContent.isNotBlank()) {
                    val previewCount = remember(rawTextContent) { TxtQuestionParser.parse(rawTextContent).size }
                    Text(
                        text = "✅ मान्य प्रश्न: $previewCount",
                        color = if (previewCount > 0) CorrectGreen else WrongRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
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
                        id = "custom_${System.currentTimeMillis()}",
                        title = title.ifBlank { "Custom Mock Test" },
                        timeMinutes = timeStr.toIntOrNull() ?: 60,
                        marksPerCorrect = marksStr.toDoubleOrNull() ?: 2.0,
                        negativeMark = negStr.toDoubleOrNull() ?: 0.0
                    )
                    onSaveCustomQuiz(cfg, rawTextContent, result.questions)
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose)
            ) {
                Text("Save & Load Test", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        }
    )
}

// ==================== 16. TEACHER ADMIN PORTAL ====================
@Composable
fun AdminPortalDialog(
    savedToken: String,
    groupedSubjects: Map<String, List<OnlineQuizMeta>>,
    onDismiss: () -> Unit,
    onActionSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    var token by remember { mutableStateOf(savedToken) }
    var isBusy by remember { mutableStateOf(false) }

    val existingSubjects = groupedSubjects.keys.toList()
    var subject by remember { mutableStateOf(existingSubjects.firstOrNull() ?: "Computer Science") }
    var title by remember { mutableStateOf("Rajasthan SET & UGC NET Mock Test") }
    var subtitle by remember { mutableStateOf("Target Preparation & Revision Test") }
    var timeStr by remember { mutableStateOf("60") }
    var marksStr by remember { mutableStateOf("2.0") }
    var negStr by remember { mutableStateOf("0.0") }
    var selectedFileName by remember { mutableStateOf("📂 Select .TXT File (Auto-Encrypted)") }
    var rawTextContent by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedFileName = "🔒 Ready: ${uri.lastPathSegment ?: "quiz.txt"}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                rawTextContent = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f).imePadding(),
        containerColor = CardDark,
        title = {
            Column {
                Text("🔒 Teacher Admin Panel", color = PrimaryRose, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Spacer(modifier = Modifier.height(8.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = BgDark,
                    contentColor = PrimaryRose
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { if (!isBusy) selectedTab = 0 },
                        text = { Text("Publish New", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { if (!isBusy) selectedTab = 1 },
                        text = { Text("Delete / Manage", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub Fine-Grained PAT (Keystore Encrypted)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (selectedTab == 0) {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("Subject / Folder Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (existingSubjects.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            existingSubjects.forEach { sub ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(HomeBox)
                                        .clickable { subject = sub }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(sub, color = TextMain, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Quiz Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = subtitle,
                        onValueChange = { subtitle = it },
                        label = { Text("Subtitle / Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeStr,
                            onValueChange = { timeStr = it },
                            label = { Text("Time") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = marksStr,
                            onValueChange = { marksStr = it },
                            label = { Text("Marks") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = negStr,
                            onValueChange = { negStr = it },
                            label = { Text("Neg") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryRose.copy(alpha = 0.08f))
                            .border(1.dp, PrimaryRose, RoundedCornerShape(8.dp))
                            .clickable { filePicker.launch("text/plain") }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(selectedFileName, color = TextMain, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("या नीचे सीधे प्रश्न Copy-Paste करें:", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = rawTextContent,
                        onValueChange = { rawTextContent = it },
                        placeholder = { Text("Q: प्रश्न...\nA) विकल्प 1\nB) विकल्प 2\nANS: A\nSOL: व्याख्या...", color = TextMuted, fontSize = 11.sp) },
                        minLines = 4,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (rawTextContent.isNotBlank()) {
                        val previewCount = remember(rawTextContent) { TxtQuestionParser.parse(rawTextContent).size }
                        Text(
                            text = "✅ पब्लिश के लिए तैयार प्रश्न: $previewCount",
                            color = if (previewCount > 0) CorrectGreen else WrongRed,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    if (groupedSubjects.isEmpty()) {
                        Text("अभी कोई ऑनलाइन टेस्ट मौजूद नहीं है।", color = TextMuted, fontSize = 13.sp)
                    } else {
                        groupedSubjects.forEach { (subName, testList) ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(BgDark)
                                    .padding(10.dp)
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
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        enabled = !isBusy,
                                        onClick = {
                                            if (token.isBlank()) {
                                                Toast.makeText(context, "पहले ऊपर GitHub Token डालें!", Toast.LENGTH_SHORT).show()
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
                                        Text("🗑️ Del Folder", color = WrongRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                HorizontalDivider(color = BorderColor, modifier = Modifier.padding(vertical = 4.dp))
                                testList.forEach { quiz ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "• ${quiz.title}",
                                            color = TextMain,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            enabled = !isBusy,
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            onClick = {
                                                if (token.isBlank()) {
                                                    Toast.makeText(context, "पहले ऊपर GitHub Token डालें!", Toast.LENGTH_SHORT).show()
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
                                            Text("🗑️", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedTab == 0) {
                Button(
                    enabled = !isBusy,
                    onClick = {
                        val result = TxtQuestionParser.parseWithValidation(rawTextContent)
                        if (token.isBlank()) {
                            Toast.makeText(context, "कृपया अपना GitHub Token डालें!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (result.questions.isEmpty()) {
                            Toast.makeText(context, "कृपया सही प्रश्नों और ANS: वाली .txt फाइल चुनें!", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (result.warnings.isNotEmpty()) {
                            Toast.makeText(context, "चेतावनी: ${result.warnings.size} प्रश्नों में ANS: गलत था (छोड़े गए)।", Toast.LENGTH_LONG).show()
                        }
                        isBusy = true
                        scope.launch {
                            val res = GitHubCloudService.publishEncryptedQuizToGitHub(
                                token = token.trim(),
                                title = title.trim(),
                                subtitle = subtitle.trim(),
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
                                onDismiss()
                            }.onFailure { err ->
                                Toast.makeText(context, "Publish Failed: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen)
                ) {
                    Text(if (isBusy) "Encrypting..." else "🚀 Publish Encrypted", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) { Text("Close", color = TextMuted) }
        }
    )
}

// ==================== 17. ESCAPED TWO-COLUMN A4 PDF PRINT HELPER (NO WATERMARK) ====================
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
                    <span>Score: ${"%.2f".format(totalMarks)} / ${"%.2f".format(maxMarks)}</span>
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
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val adapter = view.createPrintDocumentAdapter(config.title)
            printManager.print("${config.title}_Result", adapter, PrintAttributes.Builder().build())
        }
    }
    webView.loadDataWithBaseURL(null, fullHtml, "text/HTML", "UTF-8", null)
}
