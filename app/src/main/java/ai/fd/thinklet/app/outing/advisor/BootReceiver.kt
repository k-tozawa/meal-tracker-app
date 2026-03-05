package ai.fd.thinklet.app.outing.advisor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "デバイス起動検出。WakeWordService を開始します。")
            val serviceIntent = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
