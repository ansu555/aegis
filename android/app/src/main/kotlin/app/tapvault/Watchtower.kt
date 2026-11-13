package app.tapvault

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.tapvault.core.Integrity
import app.tapvault.core.NearClient
import app.tapvault.core.NearKey
import app.tapvault.core.TapvaultWallet
import app.tapvault.core.Watch

/**
 * Watchtower (plan 5.9): a periodic background job, about every 15 minutes
 * while the phone has a network, that reads each finished wallet on this
 * phone and notifies about what is new: a request this phone didn't make,
 * the wallet becoming frozen, the signer set changing, the integrity check
 * failing. It only reads (views); it never signs or sends anything.
 *
 * Android decides when periodic jobs actually run (Doze, battery saver), so
 * this is "usually within 15–30 minutes", not a guarantee. The contract's
 * delays are what make an alert useful: a transfer above the instant limit
 * or any settings change waits on chain, and the alert's Cancel / Freeze
 * opens the app where that is one tap away.
 */
object Watchtower {
    private const val JOB_ID = 4711
    private const val CHANNEL = "watchtower"
    const val EXTRA_WALLET = "tapvault.wallet"
    const val EXTRA_TARGET = "tapvault.target"

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchJob::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true) // survives a reboot (RECEIVE_BOOT_COMPLETED)
            .build()
        js.schedule(job)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    /** One pass over every finished wallet; returns the alerts it posted (also used by the job). */
    fun runOnce(ctx: Context): Int {
        val index = WalletIndex(ctx)
        if (!index.alertsOn) return 0
        val near = NearClient()
        var posted = 0
        for (id in index.ids) {
            val st = index.store(id)
            if (st.setupStep < 4) continue
            val acct = st.walletAccount ?: continue
            runCatching {
                val w = TapvaultWallet(near, acct, st.gasAccount!!, NearKey.fromSecret(st.gasKey!!))
                val s = w.state()
                val ps = w.proposals()
                val pub = runCatching { KeystoreSigner({ WalletIndex.keyAlias(id) }) { null }.publicKey }.getOrNull()
                val integrityOk = Integrity.Report.decode(st.integrity)?.ok ?: true
                val (alerts, memory) = Watch.check(Watch.Memory.decode(st.watchMemory), s, ps, pub?.let { s.phoneIndexOf(it) }, integrityOk)
                st.watchMemory = memory.encode()
                for (a in alerts) {
                    notify(ctx, id, index.name(id), a)
                    posted++
                }
            }
        }
        return posted
    }

    private fun notify(ctx: Context, walletId: String, walletName: String, a: Watch.Alert) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Wallet alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Requests you didn't make, freezes and signer changes"
            },
        )
        fun open(target: String, code: Int) = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_WALLET, walletId).putExtra(EXTRA_TARGET, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val base = (walletId.hashCode() and 0xffff) * 16 + a.kind.ordinal * 2
        val main = if (a.kind == Watch.Kind.UNKNOWN_REQUEST) "activity" else "security"
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("$walletName: ${a.title}")
            .setContentText(a.text)
            .setStyle(Notification.BigTextStyle().bigText(a.text))
            .setContentIntent(open(main, base))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .apply {
                if (a.kind == Watch.Kind.UNKNOWN_REQUEST) {
                    addAction(Notification.Action.Builder(null, "Cancel it", open("activity", base + 1)).build())
                    addAction(Notification.Action.Builder(null, "Freeze wallet", open("security", base + 1 + 1000)).build())
                }
            }
            .build()
        // One notification per wallet, kind and proposal; a repeat replaces it.
        nm.notify("$walletId/${a.kind}/${a.proposalId ?: 0}", 1, n)
    }
}

/** The JobScheduler entry point; network reads run on a plain thread. */
class WatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            runCatching { Watchtower.runOnce(applicationContext) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/**
 * App lock: balances and history stay hidden until the phone's owner unlocks
 * (fingerprint or screen lock). It only guards what the screen shows; the
 * wallet's funds are protected by the card + phone-key signatures either way.
 */
object AppLock {
    fun prompt(act: FragmentActivity, onUnlocked: () -> Unit) {
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = onUnlocked()
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Tapvault")
                .setSubtitle("Show your balance and activity")
                // WEAK + screen lock is allowed on every API level (STRONG + screen lock needs Android 11).
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }
}
