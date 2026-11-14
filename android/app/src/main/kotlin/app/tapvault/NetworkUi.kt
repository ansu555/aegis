package app.tapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tapvault.core.Network

/**
 * The app with the testnet strip above it: while a testnet wallet is shown
 * in a build that also has mainnet, every screen starts with an amber
 * "TESTNET · not real money" line (as in Phantom's testnet mode). The strip
 * takes the status bar's place, so the screens below it lay out unchanged.
 * A build without mainnet is testnet throughout and shows no strip.
 */
@Composable
fun NetworkFrame(vm: AppViewModel) {
    val strip = vm.network.isTestnet && vm.mainnetEnabled
    Column(Modifier.fillMaxSize().background(Tv.Bg)) {
        if (strip) TestnetStrip()
        Box(Modifier.weight(1f).let { if (strip) it.consumeWindowInsets(WindowInsets.statusBars) else it }) { TapvaultApp(vm) }
    }
}

@Composable
private fun TestnetStrip() {
    Box(Modifier.fillMaxWidth().background(Tv.Warn).statusBarsPadding().padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
        Text("TESTNET · not real money", style = Type.Overline, color = Color(0xFF1A1206))
    }
}

/** Settings → Network: which network's wallets are shown. No wallet ever changes network. */
@Composable
fun NetworkSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    GlassSheet(onDismiss) {
        Text("Network", style = Type.H2)
        Caption(
            "Each wallet lives on one network. Switching shows that network's wallets; nothing moves between them.",
            Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NetworkOption(
                Network.MAINNET, "Real NEAR. Swap, buy and sell come here first.",
                selected = vm.network.isMainnet,
                enabled = vm.mainnetEnabled && vm.busy == null,
                badge = if (vm.mainnetEnabled) null else "Coming soon",
            ) { vm.switchNetwork(Network.MAINNET); onDismiss() }
            NetworkOption(
                Network.TESTNET, "Test NEAR, free from the faucet. Not real money.",
                selected = vm.network.isTestnet,
                enabled = vm.busy == null,
            ) { vm.switchNetwork(Network.TESTNET); onDismiss() }
        }
        if (!vm.mainnetEnabled) Caption(
            "Mainnet opens once Aegis has been tested with real bank cards and its contract has been reviewed.",
            Modifier.padding(top = 14.dp),
        )
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun NetworkOption(n: Network, detail: String, selected: Boolean, enabled: Boolean, badge: String? = null, onPick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) Tv.GlassStrong else Tv.Glass)
            .glassRim(22.dp, if (selected) Color.White.copy(alpha = 0.3f) else Tv.Stroke)
            .pressable(enabled = enabled && !selected, onClick = onPick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // radio
        Box(
            Modifier.size(22.dp).border(2.dp, if (selected) Tv.Accent else Tv.StrokeStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) Box(Modifier.size(10.dp).background(Tv.Accent, CircleShape)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (n.isMainnet) "NEAR Mainnet" else "NEAR Testnet", style = Type.BodyStrong, color = if (enabled || selected) Tv.Text else Tv.Text3)
            Text(detail, style = Type.Caption, color = if (enabled || selected) Tv.Text2 else Tv.Text3)
        }
        badge?.let {
            Spacer(Modifier.width(10.dp))
            Text(it, style = Type.CaptionStrong, color = Tv.Text2, modifier = Modifier.background(Tv.GlassStrong, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}
