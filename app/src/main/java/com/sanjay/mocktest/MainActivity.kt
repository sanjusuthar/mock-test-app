package com.sanjay.mocktest

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

// ==================== 1. GITHUB CONFIGURATION ====================
// यहाँ अपना GitHub Username और Repository नाम लिखें:
const val GITHUB_OWNER = "sanjusuthar"
const val GITHUB_REPO = "mock-test-app"
const val GITHUB_BRANCH = "main"

// ==================== 2. EXACT THEME COLORS FROM dynamicTest1.html ====================
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

// ==================== 3. DATA MODELS ====================
data class Question(
    val topic: String,
    val text: String,
    val options: List<String>,
    val correctAnswer: Int,
    val solution: String
)

data class TestConfig(
    val title: String = "Rajasthan SET & UGC NET Mock Test",
    val subtitle: String = "Target Preparation & Revision Test",
    val timeMinutes: Int = 60,
    val marksPerCorrect: Double = 2.0,
    val negativeMark: Double = 0.66
)

data class OnlineQuizMeta(
    val id: String,
    val title: String,
    val subtitle: String,
    val timeMinutes: Int,
    val marksPerCorrect: Double,
    val negativeMark: Double,
    val questionCount: Int,
    val fileUrl: String,
    val publishedDate: String
)

enum class AppScreen { HOME, TEST, RESULT }

// ==================== 4. TXT QUESTION PARSER (Exact Match to JS Parser) ====================
object TxtQuestionParser {
    fun parse(rawText: String): List<Question> {
        val parsed = mutableListOf<Question>()
        val blocks = rawText.trim().split(Regex("\\n\\s*\\n+"))

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            var qText = ""
            var topic = "General"
            var solution = "व्याख्या उपलब्ध नहीं है।"
            var answerIndex = 0
            val options = mutableListOf<String>()

            for (line in lines) {
                when {
                    line.matches(Regex("^(?i)(Q:|Question:).*")) -> {
                        qText = line.replace(Regex("^(?i)(Q:|Question:)\\s*"), "")
                    }
                    line.matches(Regex("^(?i)[A-E][).].*")) -> {
                        options.add(line.replace(Regex("^(?i)[A-E][).]\\s*"), ""))
                    }
                    line.matches(Regex("^(?i)(ANS:|Answer:).*")) -> {
                        val ansChar = line.replace(Regex("^(?i)(ANS:|Answer:)\\s*"), "").trim().uppercase()
                        val map = mapOf("A" to 0, "B" to 1, "C" to 2, "D" to 3, "E" to 4)
                        answerIndex = map[ansChar] ?: ((ansChar.toIntOrNull() ?: 1) - 1)
                    }
                    line.matches(Regex("^(?i)TOPIC:.*")) -> {
                        topic = line.replace(Regex("^(?i)TOPIC:\\s*"), "")
                    }
                    line.matches(Regex("^(?i)(SOL:|SOLUTION:).*")) -> {
                        solution = line.replace(Regex("^(?i)(SOL:|SOLUTION:)\\s*"), "")
                    }
                    options.isEmpty() -> {
                        qText = if (qText.isEmpty()) line else "$qText\n$line"
                    }
                }
            }
            if (qText.isNotEmpty() && options.size >= 2) {
                parsed.add(
                    Question(
                        topic = topic,
                        text = qText.replace("<br>", "\n"),
                        options = options,
                        correctAnswer = answerIndex.coerceIn(0, options.size - 1),
                        solution = solution.replace("<br>", "\n")
                    )
                )
            }
        }
        return parsed
    }
}

// ==================== 5. GITHUB CLOUD SYNC & ADMIN PUBLISHER ====================
object GitHubCloudService {
    suspend fun fetchOnlineQuizzes(): List<OnlineQuizMeta> = withContext(Dispatchers.IO) {
        val urlStr = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/quizzes/index.json?t=${System.currentTimeMillis()}"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        if (conn.responseCode != 200) return@withContext emptyList()

        val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(jsonText)
        val list = mutableListOf<OnlineQuizMeta>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                OnlineQuizMeta(
                    id = obj.optString("id", "quiz_$i"),
                    title = obj.optString("title", "Mock Test"),
                    subtitle = obj.optString("subtitle", "Online Practice Test"),
                    timeMinutes = obj.optInt("timeMinutes", 60),
                    marksPerCorrect = obj.optDouble("marksPerCorrect", 2.0),
                    negativeMark = obj.optDouble("negativeMark", 0.0),
                    questionCount = obj.optInt("questionCount", 0),
                    fileUrl = obj.optString("fileUrl", ""),
                    publishedDate = obj.optString("publishedDate", "")
                )
            )
        }
        list
    }

    suspend fun downloadQuizText(fileUrl: String): String = withContext(Dispatchers.IO) {
        val sep = if (fileUrl.contains("?")) "&" else "?"
        val conn = URL("$fileUrl${sep}t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    // आपके डिवाइस से सीधे GitHub पर नया टेस्ट पब्लिश करने का फंक्शन
    suspend fun publishQuizToGitHub(
        token: String,
        title: String,
        subtitle: String,
        timeMinutes: Int,
        marks: Double,
        neg: Double,
        rawTxtContent: String,
        questionCount: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileId = "quiz_${System.currentTimeMillis()}"
            val txtPath = "quizzes/$fileId.txt"

            // Step 1: Upload the .txt question file to GitHub Repo
            putFileToGitHub(
                token = token,
                path = txtPath,
                content = rawTxtContent,
                commitMsg = "Publish quiz: $title"
            )

            // Step 2: Fetch existing quizzes/index.json & its SHA
            val indexPath = "quizzes/index.json"
            val existingFile = getFileFromGitHub(token, indexPath)
            val currentArray = if (existingFile != null) JSONArray(existingFile.first) else JSONArray()

            val rawFileUrl = "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/$GITHUB_BRANCH/$txtPath"
            val today = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())

            val newEntry = JSONObject().apply {
                put("id", fileId)
                put("title", title)
                put("subtitle", subtitle)
                put("timeMinutes", timeMinutes)
                put("marksPerCorrect", marks)
                put("negativeMark", neg)
                put("questionCount", questionCount)
                put("fileUrl", rawFileUrl)
                put("publishedDate", today)
            }

            // नए टेस्ट को सबसे ऊपर जोड़ें
            val updatedArray = JSONArray()
            updatedArray.put(newEntry)
            for (i in 0 until currentArray.length()) {
                updatedArray.put(currentArray.getJSONObject(i))
            }

            // Step 3: Commit updated quizzes/index.json
            putFileToGitHub(
                token = token,
                path = indexPath,
                content = updatedArray.toString(2),
                commitMsg = "Update index.json with $title",
                sha = existingFile?.second
            )

            Result.success("टेस्ट सफलतापूर्वक ऑनलाइन पब्लिश हो गया! अब सभी स्टूडेंट्स को होम पेज पर दिखेगा।")
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
}

// ==================== 6. MAIN ACTIVITY & UI ====================
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
    var testConfig by remember { mutableStateOf(TestConfig()) }
    var questions by remember { mutableStateOf<List<Question>>(emptyList()) }
    var candidateName by remember { mutableStateOf("") }

    // Test Active State
    var currentQIndex by remember { mutableStateOf(0) }
    var userAnswers by remember { mutableStateOf<List<Int?>>(emptyList()) }
    var remainingSeconds by remember { mutableStateOf(0) }
    var showPaletteSheet by remember { mutableStateOf(false) }
    var showSubmitConfirm by remember { mutableStateOf(false) }

    // Modals for Custom Local Quiz & Admin Online Publish
    var showCustomQuizModal by remember { mutableStateOf(false) }
    var showAdminPublishModal by remember { mutableStateOf(false) }

    // Online Quizzes State
    var onlineQuizzes by remember { mutableStateOf<List<OnlineQuizMeta>>(emptyList()) }
    var isLoadingOnline by remember { mutableStateOf(false) }

    // Load saved local custom quiz & fetch online quizzes on startup
    LaunchedEffect(Unit) {
        val savedTxt = prefs.getString("saved_custom_txt", null)
        val savedTitle = prefs.getString("saved_title", "Rajasthan SET & UGC NET Mock Test") ?: "Mock Test"
        val savedTime = prefs.getInt("saved_time", 60)
        val savedMarks = prefs.getFloat("saved_marks", 2.0f).toDouble()
        val savedNeg = prefs.getFloat("saved_neg", 0.0f).toDouble()

        if (!savedTxt.isNullOrBlank()) {
            questions = TxtQuestionParser.parse(savedTxt)
            testConfig = TestConfig(title = savedTitle, timeMinutes = savedTime, marksPerCorrect = savedMarks, negativeMark = savedNeg)
        }

        isLoadingOnline = true
        try {
            onlineQuizzes = GitHubCloudService.fetchOnlineQuizzes()
        } catch (_: Exception) {
        } finally {
            isLoadingOnline = false
        }
    }

    // Countdown Timer Effect when on TEST screen
    LaunchedEffect(currentScreen, remainingSeconds) {
        if (currentScreen == AppScreen.TEST && remainingSeconds > 0) {
            delay(1000L)
            remainingSeconds--
            if (remainingSeconds <= 0) {
                currentScreen = AppScreen.RESULT
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        when (currentScreen) {
            AppScreen.HOME -> {
                HomeScreenUI(
                    testConfig = testConfig,
                    questionCount = questions.size,
                    candidateName = candidateName,
                    onCandidateNameChange = { candidateName = it },
                    onlineQuizzes = onlineQuizzes,
                    isLoadingOnline = isLoadingOnline,
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
                    onSelectOnlineQuiz = { meta ->
                        scope.launch {
                            try {
                                Toast.makeText(context, "टेस्ट डाउनलोड हो रहा है...", Toast.LENGTH_SHORT).show()
                                val rawTxt = GitHubCloudService.downloadQuizText(meta.fileUrl)
                                val parsed = TxtQuestionParser.parse(rawTxt)
                                if (parsed.isNotEmpty()) {
                                    questions = parsed
                                    testConfig = TestConfig(
                                        title = meta.title,
                                        subtitle = meta.subtitle,
                                        timeMinutes = meta.timeMinutes,
                                        marksPerCorrect = meta.marksPerCorrect,
                                        negativeMark = meta.negativeMark
                                    )
                                    Toast.makeText(context, "'${meta.title}' लोड हो गया! अपना नाम लिखकर शुरू करें।", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "टेस्ट लोड करने में त्रुटि: इंटरनेट जांचें", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onStartTest = {
                        if (questions.isEmpty()) {
                            Toast.makeText(context, "कृपया पहले नीचे से कोई ऑनलाइन टेस्ट चुनें या Custom .txt फाइल लोड करें!", Toast.LENGTH_LONG).show()
                        } else if (candidateName.isBlank()) {
                            Toast.makeText(context, "कृपया टेस्ट शुरू करने से पहले अपना नाम लिखें!", Toast.LENGTH_SHORT).show()
                        } else {
                            userAnswers = List(questions.size) { null }
                            currentQIndex = 0
                            remainingSeconds = testConfig.timeMinutes * 60
                            currentScreen = AppScreen.TEST
                        }
                    },
                    onOpenCustomModal = { showCustomQuizModal = true },
                    onOpenAdminModal = { showAdminPublishModal = true }
                )
            }

            AppScreen.TEST -> {
                TestScreenUI(
                    testConfig = testConfig,
                    candidateName = candidateName,
                    questions = questions,
                    currentQIndex = currentQIndex,
                    userAnswers = userAnswers,
                    remainingSeconds = remainingSeconds,
                    onSelectOption = { optIdx ->
                        val mutable = userAnswers.toMutableList()
                        mutable[currentQIndex] = optIdx
                        userAnswers = mutable
                    },
                    onPrev = { if (currentQIndex > 0) currentQIndex-- },
                    onNext = {
                        if (currentQIndex < questions.size - 1) currentQIndex++
                        else showSubmitConfirm = true
                    },
                    onSkip = {
                        if (currentQIndex < questions.size - 1) currentQIndex++
                        else showSubmitConfirm = true
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
                    timeSpentSeconds = (testConfig.timeMinutes * 60) - remainingSeconds,
                    onPrintPdf = {
                        printExactA4ResultPdf(
                            context = context,
                            config = testConfig,
                            studentName = candidateName,
                            questions = questions,
                            userAnswers = userAnswers
                        )
                    },
                    onRetake = { currentScreen = AppScreen.HOME }
                )
            }
        }

        // Question Palette Bottom Dialog
        if (showPaletteSheet && currentScreen == AppScreen.TEST) {
            AlertDialog(
                onDismissRequest = { showPaletteSheet = false },
                containerColor = CardDark,
                title = {
                    Text("Question Palette", color = TextMain, fontWeight = FontWeight.Bold)
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

        // Submit Confirmation Dialog
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
                            currentScreen = AppScreen.RESULT
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

        // Modal 1: Local Custom Quiz Builder (For Students & Teacher)
        if (showCustomQuizModal) {
            CustomQuizDialog(
                initialConfig = testConfig,
                onDismiss = { showCustomQuizModal = false },
                onSaveCustomQuiz = { newCfg, rawTxt, parsedList ->
                    testConfig = newCfg
                    questions = parsedList
                    prefs.edit()
                        .putString("saved_custom_txt", rawTxt)
                        .putString("saved_title", newCfg.title)
                        .putInt("saved_time", newCfg.timeMinutes)
                        .putFloat("saved_marks", newCfg.marksPerCorrect.toFloat())
                        .putFloat("saved_neg", newCfg.negativeMark.toFloat())
                        .apply()
                    showCustomQuizModal = false
                    Toast.makeText(context, "Custom Quiz लोड हो गया (${parsedList.size} प्रश्न)!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Modal 2: Admin Online Quiz Publisher (Push directly from your device to GitHub)
        if (showAdminPublishModal) {
            AdminPublishDialog(
                savedToken = prefs.getString("gh_pat_token", "") ?: "",
                onDismiss = { showAdminPublishModal = false },
                onPublishedSuccess = { tokenUsed ->
                    prefs.edit().putString("gh_pat_token", tokenUsed).apply()
                    showAdminPublishModal = false
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

// ==================== 7. HOME SCREEN UI (With Online Published Quizzes + Custom Builder) ====================
@Composable
fun HomeScreenUI(
    testConfig: TestConfig,
    questionCount: Int,
    candidateName: String,
    onCandidateNameChange: (String) -> Unit,
    onlineQuizzes: List<OnlineQuizMeta>,
    isLoadingOnline: Boolean,
    onRefreshOnline: () -> Unit,
    onSelectOnlineQuiz: (OnlineQuizMeta) -> Unit,
    onStartTest: () -> Unit,
    onOpenCustomModal: () -> Unit,
    onOpenAdminModal: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HomeBgTop, HomeBgBot)))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Brand Header
        Text(
            text = testConfig.title.uppercase(),
            color = PrimaryRose,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
        Text(
            text = "ONLINE PRACTICE EXAM PORTAL",
            color = Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Main Active Test Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HomeCard)
                .border(1.dp, PrimaryRose.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = testConfig.title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = testConfig.subtitle,
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            // 2x2 Info Grid
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatInfoBox(Modifier.weight(1f), "$questionCount", "QUESTIONS", PrimaryRose)
                StatInfoBox(Modifier.weight(1f), "${testConfig.timeMinutes} Min", "DURATION", PrimaryRose)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatInfoBox(Modifier.weight(1f), "+%.2f".format(testConfig.marksPerCorrect), "MARKS/QN", CorrectGreen)
                val negStr = if (testConfig.negativeMark > 0) "-%.2f".format(testConfig.negativeMark) else "0.00"
                StatInfoBox(Modifier.weight(1f), negStr, "NEGATIVE", WrongRed)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Candidate Name Input
            OutlinedTextField(
                value = candidateName,
                onValueChange = onCandidateNameChange,
                placeholder = { Text("यहाँ अपना नाम लिखें...", color = TextMuted) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HomeBgTop, RoundedCornerShape(10.dp)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryRose,
                    unfocusedBorderColor = PrimaryRose,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Start Exam Button
            Button(
                onClick = onStartTest,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("▶|| परीक्षा शुरू करें", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Custom Local Quiz Button
            OutlinedButton(
                onClick = onOpenCustomModal,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("⚙️ Create Custom Quiz / Upload .TXT File", color = TextMain, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ==================== ONLINE PUBLISHED QUIZZES SECTION ====================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌐 ऑनलाइन पब्लिश किए गए टेस्ट (Live Tests)",
                color = PrimaryRose,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onRefreshOnline) {
                Text(if (isLoadingOnline) "Loading..." else "🔄 Refresh", color = SecondarySky, fontSize = 12.sp)
            }
        }

        if (onlineQuizzes.isEmpty() && !isLoadingOnline) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HomeCard)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "अभी कोई ऑनलाइन टेस्ट उपलब्ध नहीं है या GitHub Username सेट नहीं किया गया है।",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            onlineQuizzes.forEach { quiz ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(HomeCard)
                        .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                        .clickable { onSelectOnlineQuiz(quiz) }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(quiz.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "${quiz.questionCount} प्रश्न • ${quiz.timeMinutes} Min • ${quiz.publishedDate}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                    Button(
                        onClick = { onSelectOnlineQuiz(quiz) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRose),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Load Test", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Teacher / Admin Publish Button
        TextButton(onClick = onOpenAdminModal) {
            Text("🔒 Teacher Admin: Publish New Quiz Online", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
fun StatInfoBox(modifier: Modifier, value: String, label: String, valueColor: Color) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HomeBox)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ==================== 8. TEST SCREEN UI ====================
@Composable
fun TestScreenUI(
    testConfig: TestConfig,
    candidateName: String,
    questions: List<Question>,
    currentQIndex: Int,
    userAnswers: List<Int?>,
    remainingSeconds: Int,
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
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardDark)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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

        // Progress Bar
        LinearProgressIndicator(
            progress = { (currentQIndex + 1).toFloat() / questions.size.toFloat() },
            color = PrimaryRose,
            trackColor = BorderColor,
            modifier = Modifier.fillMaxWidth().height(3.dp)
        )

        // Sub-Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgDark)
                .padding(horizontal = 15.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("प्रश्न ${currentQIndex + 1} / ${questions.size}", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("उत्तर दिए: $answeredCount", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }

        // Question Area
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

                // Options List
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

        // Bottom Actions Bar
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

// ==================== 9. RESULT SCREEN UI ====================
@Composable
fun ResultScreenUI(
    testConfig: TestConfig,
    candidateName: String,
    questions: List<Question>,
    userAnswers: List<Int?>,
    timeSpentSeconds: Int,
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

    val totalMarks = max(0.0, (correct * testConfig.marksPerCorrect) - (wrong * testConfig.negativeMark))
    val maxMarks = questions.size * testConfig.marksPerCorrect
    val attempted = correct + wrong
    val accuracy = if (attempted > 0) ((correct.toDouble() / attempted) * 100).roundToInt() else 0
    val letters = listOf("A", "B", "C", "D", "E")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(15.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("EXAM RESULT", color = PrimaryRose, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$candidateName | ${testConfig.title}", color = TextMuted, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(15.dp))

            // Score Card
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
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                val negInfo = if (testConfig.negativeMark > 0) "-%.2f Negative".format(testConfig.negativeMark) else "No Negative"
                Text("Total Marks ($negInfo)", color = TextMuted, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 6 Stats Grid
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

            // Print PDF & Retake Buttons
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

// ==================== 10. CUSTOM LOCAL QUIZ MODAL (.TXT FILE PICKER) ====================
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
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(selectedFileName, color = TextMain, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = TxtQuestionParser.parse(rawTextContent)
                    if (parsed.isEmpty()) {
                        Toast.makeText(context, "कृपया सही फॉर्मेट वाली .txt फाइल चुनें!", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val cfg = TestConfig(
                        title = title.ifBlank { "Custom Mock Test" },
                        timeMinutes = timeStr.toIntOrNull() ?: 60,
                        marksPerCorrect = marksStr.toDoubleOrNull() ?: 2.0,
                        negativeMark = negStr.toDoubleOrNull() ?: 0.0
                    )
                    onSaveCustomQuiz(cfg, rawTextContent, parsed)
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

// ==================== 11. TEACHER ADMIN ONLINE PUBLISH MODAL ====================
@Composable
fun AdminPublishDialog(
    savedToken: String,
    onDismiss: () -> Unit,
    onPublishedSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(savedToken) }
    var title by remember { mutableStateOf("Rajasthan SET & UGC NET Mock Test") }
    var subtitle by remember { mutableStateOf("Target Preparation & Revision Test") }
    var timeStr by remember { mutableStateOf("60") }
    var marksStr by remember { mutableStateOf("2.0") }
    var negStr by remember { mutableStateOf("0.0") }
    var selectedFileName by remember { mutableStateOf("📂 Select .TXT File to Publish Online") }
    var rawTextContent by remember { mutableStateOf("") }
    var isPublishing by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedFileName = "📄 Ready: ${uri.lastPathSegment ?: "quiz.txt"}"
            context.contentResolver.openInputStream(uri)?.use { stream ->
                rawTextContent = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isPublishing) onDismiss() },
        containerColor = CardDark,
        title = { Text("🌐 Publish Quiz Online (GitHub)", color = PrimaryRose, fontWeight = FontWeight.Black, fontSize = 17.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "यहाँ से पब्लिश किया गया टेस्ट सभी स्टूडेंट्स के ऐप की होम स्क्रीन पर तुरंत दिखाई देगा।",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("GitHub Personal Access Token (PAT)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                    label = { Text("Subtitle / Subject") },
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
                        label = { Text("Marks") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = negStr,
                        onValueChange = { negStr = it },
                        label = { Text("Negative") },
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
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(selectedFileName, color = TextMain, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isPublishing,
                onClick = {
                    val parsed = TxtQuestionParser.parse(rawTextContent)
                    if (token.isBlank()) {
                        Toast.makeText(context, "कृपया अपना GitHub Token डालें!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (parsed.isEmpty()) {
                        Toast.makeText(context, "कृपया सही प्रश्नों वाली .txt फाइल चुनें!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isPublishing = true
                    scope.launch {
                        val res = GitHubCloudService.publishQuizToGitHub(
                            token = token.trim(),
                            title = title.trim(),
                            subtitle = subtitle.trim(),
                            timeMinutes = timeStr.toIntOrNull() ?: 60,
                            marks = marksStr.toDoubleOrNull() ?: 2.0,
                            neg = negStr.toDoubleOrNull() ?: 0.0,
                            rawTxtContent = rawTextContent,
                            questionCount = parsed.size
                        )
                        isPublishing = false
                        res.onSuccess { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onPublishedSuccess(token.trim())
                        }.onFailure { err ->
                            Toast.makeText(context, "Publish Failed: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CorrectGreen)
            ) {
                Text(if (isPublishing) "Publishing..." else "🚀 Publish Online", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPublishing) { Text("Close", color = TextMuted) }
        }
    )
}

// ==================== 12. EXACT A4 WATERMARK PDF PRINT HELPER ====================
fun printExactA4ResultPdf(
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

    questions.forEachIndexed { idx, q ->
        val uAns = userAnswers.getOrNull(idx)
        val printColor = when (uAns) {
            null -> "#ef4444"
            q.correctAnswer -> "#10b981"
            else -> "#ef4444"
        }
        if (uAns == null) skipped++ else if (uAns == q.correctAnswer) correct++ else wrong++

        val uAnsText = if (uAns != null) "(${letters.getOrElse(uAns) { "" }}) ${q.options.getOrElse(uAns) { "" }}" else "कोई नहीं"
        val cAnsText = "(${letters.getOrElse(q.correctAnswer) { "" }}) ${q.options.getOrElse(q.correctAnswer) { "" }}"

        val optsHtml = q.options.mapIndexed { i, opt -> "<div>(${letters.getOrElse(i) { "" }}) $opt</div>" }.joinToString("")
        qHtmlBuilder.append(
            """
            <div class="print-q-box">
                <div class="print-q-head"><span>Q${idx + 1}.</span><span class="print-q-topic">${q.topic}</span></div>
                <div class="print-q-text">${q.text}</div>
                <div class="print-options-grid">$optsHtml</div>
                <div class="print-ans-row"><strong>आपका उत्तर:</strong> <span style="color:$printColor">$uAnsText</span></div>
                <div class="print-ans-row"><strong>सही उत्तर:</strong> <span style="color:#10b981">$cAnsText</span></div>
                <div class="print-sol-box"><div class="print-sol-lbl">व्याख्या (Solution):</div>${q.solution}</div>
            </div>
            """.trimIndent()
        )
    }

    val totalMarks = max(0.0, (correct * config.marksPerCorrect) - (wrong * config.negativeMark))
    val maxMarks = questions.size * config.marksPerCorrect
    val attempted = correct + wrong
    val accuracy = if (attempted > 0) ((correct.toDouble() / attempted) * 100).roundToInt() else 0
    val negInfo = if (config.negativeMark > 0) "-%.2f Negative".format(config.negativeMark) else "No Negative Marking"

    val fullHtml = """
        <!DOCTYPE html>
        <html lang="hi">
        <head>
        <meta charset="UTF-8">
        <style>
            @page { size: A4 portrait; margin: 10mm; }
            body { background: white; color: black; font-family: sans-serif; margin: 0; padding: 0; }
            .wm-print { position: fixed; top: 45%; left: 50%; transform: translate(-50%, -50%) rotate(-45deg); font-size: 40px; font-weight: 900; color: #000; opacity: 0.08; z-index: 9999; white-space: nowrap; }
            .print-header-box { border: 2px solid #000; padding: 15px; border-radius: 8px; margin-bottom: 20px; text-align: center; background: #f8fafc; }
            .print-score-row { display: flex; justify-content: space-around; border-top: 1px dashed #000; padding-top: 10px; font-size: 13px; font-weight: bold; }
            .print-q-box { margin-bottom: 16px; padding-bottom: 10px; border-bottom: 1px solid #cbd5e1; page-break-inside: avoid; }
            .print-q-head { display: flex; justify-content: space-between; font-weight: bold; margin-bottom: 6px; }
            .print-q-topic { background: #e11d48; color: #fff; padding: 2px 8px; font-size: 11px; border-radius: 12px; }
            .print-q-text { font-size: 14px; font-weight: 600; margin-bottom: 6px; }
            .print-options-grid { margin-bottom: 6px; font-size: 13px; padding-left: 10px; }
            .print-ans-row { font-size: 12px; margin-bottom: 3px; }
            .print-sol-box { background: #f8fafc; border-left: 4px solid #e11d48; padding: 8px; margin-top: 6px; font-size: 12px; }
        </style>
        </head>
        <body>
            <div class="wm-print">${config.title.uppercase()}</div>
            <div class="print-header-box">
                <h2>${config.title}</h2>
                <h3>Candidate: $studentName | Total Questions: ${questions.size}</h3>
                <p>Marking: +${"%.2f".format(config.marksPerCorrect)} / Correct | $negInfo</p>
                <div class="print-score-row">
                    <span>Score: ${"%.2f".format(totalMarks)} / ${"%.2f".format(maxMarks)}</span>
                    <span>Correct: $correct</span>
                    <span>Wrong: $wrong</span>
                    <span>Skipped: $skipped</span>
                    <span>Accuracy: $accuracy%</span>
                </div>
            </div>
            $qHtmlBuilder
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
