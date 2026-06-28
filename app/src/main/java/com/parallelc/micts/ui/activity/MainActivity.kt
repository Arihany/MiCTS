package com.parallelc.micts.ui.activity

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.parallelc.micts.BuildConfig
import com.parallelc.micts.R
import com.parallelc.micts.config.AppConfig.CONFIG_NAME
import com.parallelc.micts.config.AppConfig.DEFAULT_CONFIG
import com.parallelc.micts.config.AppConfig.KEY_ASYNC_TRIGGER
import com.parallelc.micts.config.AppConfig.KEY_DEFAULT_DELAY
import com.parallelc.micts.config.AppConfig.KEY_TILE_DELAY
import com.parallelc.micts.config.AppConfig.KEY_VIBRATE
import com.parallelc.micts.module
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.lsposed.hiddenapibypass.HiddenApiBypass

const val LOG_TAG = BuildConfig.APP_NAME
private const val ALLOW_ACTIVITY_VIS_FALLBACK = false
private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
private val ASSISTANT_HANDOFF_ACTIVITY_NAMES = listOf(
    "com.google.android.apps.search.assistant.surfaces.launcher.AssistantHandoffActivity",
    "com.google.android.apps.search.assistant.surfaces.launcher.handoff.AssistantHandoffActivity",
    "com.google.android.apps.search.assistant.surfaces.launcher.LauncherAssistantHandoffActivity",
    "com.google.android.googlequicksearchbox.Launch.AssistantHandoffActivity",
)

private fun vibrateOnTrigger(context: Context) {
    runCatching {
        (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).run {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setFlags(128)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), attr)
            } else {
                vibrate(longArrayOf(0, 1, 75, 76), -1, attr)
            }
        }
    }.onFailure { e ->
        val errMsg = "triggerCircleToSearch vibrate failed: " + e.stackTraceToString()
        module?.log(Log.ERROR, LOG_TAG, errMsg) ?: Log.e(LOG_TAG, errMsg)
    }
}

private fun triggerAssistantHandoff(context: Context, vibrate: Boolean): Boolean {
    if (BuildConfig.APP_NAME != "MiCTS") return false

    val packageManager = context.packageManager
    val candidates = buildList {
        addAll(ASSISTANT_HANDOFF_ACTIVITY_NAMES)
        val queryIntent = Intent(Intent.ACTION_MAIN).setPackage(GOOGLE_APP_PACKAGE)
        addAll(
            packageManager.queryIntentActivities(queryIntent, 0)
                .map { it.activityInfo.name }
                .filter { it.contains("AssistantHandoff", ignoreCase = true) }
        )
    }.distinct()

    for (activityName in candidates) {
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(GOOGLE_APP_PACKAGE, activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val result = runCatching {
            context.startActivity(intent)
        }.onFailure { e ->
            if (e !is ActivityNotFoundException && e !is SecurityException) {
                val errMsg = "triggerAssistantHandoff failed: $activityName\n" + e.stackTraceToString()
                module?.log(Log.ERROR, LOG_TAG, errMsg) ?: Log.e(LOG_TAG, errMsg)
            }
        }.isSuccess

        if (result) {
            if (vibrate) vibrateOnTrigger(context)
            return true
        }
    }

    return false
}

@SuppressLint("PrivateApi")
fun triggerCircleToSearch(
    entryPoint: Int,
    context: Context?,
    vibrate: Boolean,
    allowVisFallback: Boolean = true,
): Boolean {
    if (context != null && triggerAssistantHandoff(context, vibrate)) {
        return true
    }
    if (!allowVisFallback) {
        return false
    }

    val result =  runCatching {
        val bundle = Bundle()
        if (BuildConfig.APP_NAME == "MiCTS") {
            bundle.putLong("invocation_time_ms", SystemClock.elapsedRealtime())
            bundle.putInt("omni.entry_point", entryPoint)
            bundle.putBoolean("micts_trigger", true)
        }
        val iVimsClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService")
        val vis = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "voiceinteraction")
        val vims = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, vis)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HiddenApiBypass.invoke(iVimsClass, vims, "showSessionFromSession", null, bundle, 7, "hyperOS_home") as Boolean
        } else {
            HiddenApiBypass.invoke(iVimsClass, vims, "showSessionFromSession", null, bundle, 7) as Boolean
        }
    }.onFailure { e ->
        val errMsg = "triggerCircleToSearch invoke omni failed: " + e.stackTraceToString()
        module?.log(Log.ERROR, LOG_TAG, errMsg) ?: Log.e(LOG_TAG, errMsg)
    }.getOrDefault(false)
    if (result && vibrate && context != null) {
        vibrateOnTrigger(context)
    }
    return result
}

class MainActivity : ComponentActivity() {
    suspend fun delayAndTrigger(delayMs: Long, vibrate: Boolean) {
        if (delayMs > 0) {
            delay(delayMs)
        }
        if (!triggerCircleToSearch(1, this, vibrate, ALLOW_ACTIVITY_VIS_FALLBACK)) {
            Toast.makeText(this, getString(R.string.trigger_failed), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        val prefs = getSharedPreferences(CONFIG_NAME, MODE_PRIVATE)
        val key = if (intent.getBooleanExtra("from_tile", false)) KEY_TILE_DELAY else KEY_DEFAULT_DELAY
        val delayMs = prefs.getLong(key, DEFAULT_CONFIG[key] as Long)
        val vibrate = prefs.getBoolean(KEY_VIBRATE, DEFAULT_CONFIG[KEY_VIBRATE] as Boolean)

        if (prefs.getBoolean(KEY_ASYNC_TRIGGER, DEFAULT_CONFIG[KEY_ASYNC_TRIGGER] as Boolean)) {
            lifecycleScope.launch {
                delayAndTrigger(delayMs, vibrate)
            }
        } else {
            runBlocking {
                delayAndTrigger(delayMs, vibrate)
            }
        }
    }
}
