package com.personal.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private val Bg = Color(0xFF050B14)
private val Panel = Color(0xFF0D1A2B)
private val Cyan = Color(0xFF55DDF5)
private val Muted = Color(0xFF9BB2C9)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var voiceResult: ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        requestRuntimePermissions()
        setContent { JarvisScreen(this) }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
    }

    fun speak(text: String) {
        tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
    }

    fun listen(onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("No speech recognition service is installed. Install an offline-capable recognizer or type your command.")
            return
        }
        speech?.destroy()
        speech = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        voiceResult = onText
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { onError("Voice input failed (code $error). Try again or type instead.") }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (phrase.isNullOrBlank()) onError("I didn't catch that.") else voiceResult?.invoke(phrase)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS")
        }
        speech?.startListening(intent)
    }

    fun processCommand(command: String, endpoint: String, haUrl: String, haToken: String, callback: (String) -> Unit) {
        val lower = command.lowercase(Locale.US).trim()
        when {
            lower.startsWith("open ") -> {
                val name = lower.removePrefix("open ").trim()
                val packageName = when {
                    "youtube" in name -> "com.google.android.youtube"
                    "whatsapp" in name -> "com.whatsapp"
                    "settings" in name -> "com.android.settings"
                    "maps" in name || "google maps" in name -> "com.google.android.apps.maps"
                    else -> null
                }
                val launch = packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    startActivity(launch)
                    callback("Opening $name.")
                } else callback("I couldn't launch $name. Try YouTube, WhatsApp, Maps, or Settings.")
            }
            lower.startsWith("remind me in ") -> {
                val match = Regex("""remind me in\s+(\d+)\s+(second|seconds|minute|minutes|hour|hours)\s+(?:to\s+)?(.+)""").find(lower)
                if (match == null) callback("Try: remind me in 10 minutes to drink water.")
                else {
                    val amount = match.groupValues[1].toLong()
                    val unit = match.groupValues[2]
                    val text = match.groupValues[3]
                    val multiplier = if (unit.startsWith("second")) 1000L else if (unit.startsWith("hour")) 3600000L else 60000L
                    scheduleReminder(text, amount * multiplier)
                    callback("Reminder set for $text in $amount $unit.")
                }
            }
            lower.contains("read notifications") || lower.contains("read my notifications") -> {
                val latest = NoticeStore.items.take(5)
                callback(if (latest.isEmpty()) "No notifications captured yet. Enable JARVIS notification access in Android Settings." else
                    latest.joinToString(". ") { "${it.app.substringAfterLast('.')}: ${it.title}. ${it.body}" })
            }
            lower.startsWith("remember ") -> {
                val item = command.substringAfter("remember ", "").trim()
                if (item.isNotBlank()) {
                    val prefs = getSharedPreferences("jarvis_memory", MODE_PRIVATE)
                    val old = prefs.getString("notes", "").orEmpty()
                    prefs.edit().putString("notes", (old + "\n• " + item).takeLast(12000)).apply()
                    callback("Saved to local memory.")
                } else callback("Tell me what you want me to remember.")
            }
            lower.contains("what do you remember") || lower.contains("show memory") -> {
                callback(getSharedPreferences("jarvis_memory", MODE_PRIVATE).getString("notes", "").orEmpty().ifBlank { "Your memory is empty. Say: remember that I prefer concise answers." })
            }
            lower.startsWith("home ") -> controlHome(command, haUrl, haToken, callback)
            else -> askLocalModel(command, endpoint, callback)
        }
    }

    private fun scheduleReminder(text: String, delayMs: Long) {
        val intent = Intent(this, ReminderReceiver::class.java).putExtra("text", text)
        val pi = PendingIntent.getBroadcast(this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis() + delayMs.coerceAtLeast(1000L)
        if (Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarm.set(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun controlHome(command: String, haUrl: String, token: String, callback: (String) -> Unit) {
        val match = Regex("""home\s+(on|off)\s+([a-zA-Z0-9_.]+)""", RegexOption.IGNORE_CASE).find(command)
        if (match == null) {
            callback("For Home Assistant, use: home on light.living_room or home off switch.plug. Configure your local server URL and token in the app.")
            return
        }
        if (haUrl.isBlank() || token.isBlank()) {
            callback("Home Assistant isn't configured yet. Enter its local URL and long-lived access token in the settings fields.")
            return
        }
        val action = if (match.groupValues[1].equals("on", true)) "turn_on" else "turn_off"
        val entity = match.groupValues[2]
        executor.execute {
            try {
                val url = URL(haUrl.trimEnd('/') + "/api/services/" + entity.substringBefore('.') + "/" + action)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(JSONObject().put("entity_id", entity).toString().toByteArray()) }
                val code = conn.responseCode
                runOnUiThread { callback(if (code in 200..299) "Home Assistant accepted the $action command for $entity." else "Home Assistant returned HTTP $code. Check URL, token, and entity ID.") }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { callback("Couldn't reach Home Assistant. Check that the server is on the same network and the URL is correct.") }
            }
        }
    }

    private fun askLocalModel(prompt: String, endpoint: String, callback: (String) -> Unit) {
        val memory = getSharedPreferences("jarvis_memory", MODE_PRIVATE).getString("notes", "").orEmpty()
        executor.execute {
            try {
                val url = URL(endpoint.trimEnd('/') + "/v1/chat/completions")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 120000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                val system = "You are JARVIS, a calm, concise personal assistant. Use this user-approved local memory when relevant:\n$memory"
                val body = JSONObject()
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                    .put("temperature", 0.7)
                    .put("max_tokens", 400)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                val answer = if (code in 200..299) {
                    JSONObject(response).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                        ?: "The local model returned an unexpected response."
                } else "Local model returned HTTP $code. Check that llama-server is running and the endpoint is correct."
                conn.disconnect()
                runOnUiThread { callback(answer) }
            } catch (e: Exception) {
                runOnUiThread { callback("I can't reach your local AI model. Start llama-server first and set the endpoint to http://127.0.0.1:8080. Details: ${e.message ?: "connection failed"}") }
            }
        }
    }

    fun openNotificationSettings() {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }

    override fun onDestroy() {
        speech?.destroy()
        tts?.shutdown()
        executor.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun JarvisScreen(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080") }
    var haUrl by remember { mutableStateOf(prefs.getString("ha_url", "") ?: "") }
    var haToken by remember { mutableStateOf(prefs.getString("ha_token", "") ?: "") }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Systems ready. Start your local model, then speak or type a command.") }
    var busy by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Cyan, background = Bg, surface = Panel, onBackground = Color.White,
        onSurface = Color.White, secondary = Cyan
    )) {
        Column(Modifier.fillMaxSize().background(Bg).padding(18.dp)) {
            Text("J A R V I S", color = Cyan, fontSize = 30.sp)
            Text("PERSONAL LOCAL INTELLIGENCE", color = Muted, fontSize = 10.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(118.dp).background(Panel, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("◉", color = Cyan, fontSize = 46.sp)
                    Text(if (busy) "PROCESSING" else "STANDBY", color = Muted, fontSize = 11.sp, letterSpacing = 3.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("JARVIS RESPONSE", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
            Box(Modifier.fillMaxWidth().weight(1f).background(Panel, RoundedCornerShape(16.dp)).padding(14.dp)) {
                LazyColumn {
                    item { Text(output, color = Color.White, fontSize = 15.sp) }
                    items(history) { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).background(Color(0xFF14263A), RoundedCornerShape(12.dp)).padding(12.dp)) {
                    if (input.isEmpty()) Text("Tell JARVIS what to do…", color = Muted)
                    BasicTextField(value = input, onValueChange = { input = it }, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val text = input.trim()
                    if (text.isNotBlank()) {
                        history.add("YOU: $text")
                        busy = true
                        input = ""
                        activity.processCommand(text, endpoint, haUrl, haToken) { answer ->
                            output = answer
                            history.add("JARVIS: $answer")
                            busy = false
                            activity.speak(answer)
                        }
                    }
                }) { Text("SEND") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    busy = true
                    activity.listen({ phrase ->
                        input = phrase
                        history.add("YOU (VOICE): $phrase")
                        activity.processCommand(phrase, endpoint, haUrl, haToken) { answer ->
                            output = answer
                            history.add("JARVIS: $answer")
                            busy = false
                            activity.speak(answer)
                        }
                    }, { error -> output = error; busy = false })
                }) { Text("🎙 SPEAK") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { activity.openNotificationSettings() }) { Text("NOTIFICATIONS") }
            }
            Spacer(Modifier.height(8.dp))
            Text("LOCAL MODEL ENDPOINT", color = Cyan, fontSize = 10.sp)
            OutlinedTextField(value = endpoint, onValueChange = { endpoint = it; prefs.edit().putString("endpoint", it).apply() },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("http://127.0.0.1:8080") })
            Text("OPTIONAL HOME ASSISTANT", color = Cyan, fontSize = 10.sp)
            OutlinedTextField(value = haUrl, onValueChange = { haUrl = it; prefs.edit().putString("ha_url", it).apply() },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Local server URL, e.g. http://192.168.1.5:8123") })
            OutlinedTextField(value = haToken, onValueChange = { haToken = it; prefs.edit().putString("ha_token", it).apply() },
                modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Home Assistant token (stored on device)") })
            Text("Commands: open YouTube • remind me in 10 minutes to stretch • remember that I prefer short answers • read notifications • home on light.living_room",
                color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}
