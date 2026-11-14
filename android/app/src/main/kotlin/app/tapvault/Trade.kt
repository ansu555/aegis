package app.tapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tapvault.core.ChainAddress
import app.tapvault.core.IntentToken
import app.tapvault.core.KnownTokens
import app.tapvault.core.Prices
import app.tapvault.core.Ramp
import app.tapvault.core.Swap
import app.tapvault.core.SwapQuote
import app.tapvault.core.SwapRecord
import app.tapvault.core.SwapState
import app.tapvault.core.TokenAmount
import app.tapvault.core.TokenInfo
import kotlinx.coroutines.delay
import java.math.BigInteger

private enum class TradeTab(val label: String) { Swap("Swap"), Bridge("Bridge"), Buy("Buy"), Sell("Sell") }

/**
 * Trade: swap tokens and bridge to and from other chains through NEAR
 * Intents, buy and sell for cash through Ramp Network. Mainnet only; every
 * swap and bridge-out is a normal payment from the wallet to a deposit
 * address, approved like any other.
 */
@Composable
fun TradeScreen(vm: AppViewModel) {
    LaunchedEffect(vm.network, vm.activeId) { vm.refreshPrices(); vm.refreshTokens() }
    // follow unfinished swaps while Trade is open
    LaunchedEffect(vm.swaps) {
        while (vm.swaps.any { !it.state.done }) { vm.refreshSwaps(); delay(10_000) }
    }
    if (!vm.tradeAvailable) {
        Spacer(Modifier.height(10.dp))
        Notice(
            Ic.Info, "Mainnet only",
            if (vm.network.isTestnet && vm.mainnetEnabled) "Swaps, bridges, buying and selling use real markets, which only exist on NEAR mainnet. Switch in Settings → Network."
            else "Swaps, bridges, buying and selling use real markets, which only exist on NEAR mainnet. This build is testnet only until Tapvault has been tested with real cards and its contract reviewed.",
            Tv.Text2,
        )
        Spacer(Modifier.height(14.dp))
        Caption("On testnet you can still add, receive and send tokens from Home.", Modifier.padding(start = 4.dp), color = Tv.Text3)
        return
    }
    var tab by remember { mutableStateOf(TradeTab.Swap) }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TradeTab.entries.forEach { t -> Chip(t.label, selected = t == tab) { tab = t } }
    }
    Spacer(Modifier.height(16.dp))
    when (tab) {
        TradeTab.Swap -> SwapPanel(vm, bridge = false)
        TradeTab.Bridge -> BridgePanel(vm)
        TradeTab.Buy -> RampPanel(vm, buy = true)
        TradeTab.Sell -> RampPanel(vm, buy = false)
    }
    if (vm.swaps.isNotEmpty()) {
        SectionHeader("Recent", Modifier.padding(top = 26.dp))
        Group {
            vm.swaps.take(10).forEachIndexed { i, r ->
                if (i > 0) GroupDivider()
                SwapRow(vm, r)
            }
        }
    }
}

@Composable
private fun SwapRow(vm: AppViewModel, r: SwapRecord) {
    val uri = LocalUriHandler.current
    val color = when (r.state) {
        SwapState.SUCCESS -> Tv.Positive
        SwapState.REFUNDED, SwapState.FAILED, SwapState.INCOMPLETE_DEPOSIT -> Tv.Warn
        else -> Tv.Text2
    }
    Row_(
        when (r.kind) { SwapRecord.Kind.SWAP -> Ic.Refresh; SwapRecord.Kind.BRIDGE_OUT -> Ic.Send; SwapRecord.Kind.BRIDGE_IN -> Ic.Receive },
        r.title,
        "${r.amountIn} → ${r.amountOut} · " + (if (r.state == SwapState.FAILED && r.kind == SwapRecord.Kind.BRIDGE_IN) "Expired" else r.state.label),
        tint = color,
        chevron = r.txHash != null,
        onClick = r.txHash?.let { h -> { uri.openUri(vm.near.explorerTx(h)) } },
    )
}

/** Tokens the wallet can pay a swap from: NEAR tokens on Home with a balance (native NEAR can't be swapped: 1Click takes wNEAR). */
private fun sources(vm: AppViewModel): List<TokenInfo> = vm.shownTokens.filter { it.raw.signum() > 0 }.map { it.token }

/**
 * Swap (to a token on NEAR) or bridge out (to [chain], paid to [recipient]):
 * pick, type an amount, see a live preview quote, review, slide.
 */
@Composable
private fun SwapPanel(vm: AppViewModel, bridge: Boolean, chain: String? = null, recipient: String = "", recipientOk: Boolean = true) {
    var from by remember { mutableStateOf<TokenInfo?>(null) }
    var to by remember(chain) { mutableStateOf<IntentToken?>(null) }
    var amount by remember { mutableStateOf("") }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<SwapQuote?>(null) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf(false) }
    val srcs = sources(vm)
    LaunchedEffect(srcs.size) { if (from == null) from = srcs.firstOrNull() }
    // default output: USDC on NEAR for a swap; the same symbol on the chosen chain for a bridge
    LaunchedEffect(vm.intentTokens, chain, from) {
        if (to == null || (bridge && to?.blockchain != chain)) {
            to = if (bridge) vm.intentTokens.filter { it.blockchain == chain }.let { l -> l.firstOrNull { it.symbol == from?.symbol } ?: l.firstOrNull { it.symbol.startsWith("USDC") } ?: l.firstOrNull() }
            else vm.intentTokens.firstOrNull { it.nearContract == KnownTokens.on(vm.network).firstOrNull()?.contract }
        }
    }
    val f = from
    val t = to
    val raw = f?.let { TokenAmount.parse(amount, it.decimals) }
    val have = f?.let { ff -> vm.tokens?.firstOrNull { it.token.contract == ff.contract }?.raw }
    val tooMuch = raw != null && have != null && raw > have
    val same = !bridge && f != null && t?.nearContract == f.contract
    // live preview, debounced
    LaunchedEffect(f, t, amount, recipient, recipientOk) {
        quote = null; quoteError = null
        if (f == null || t == null || raw == null || same || (bridge && (!recipientOk || recipient.isBlank()))) return@LaunchedEffect
        delay(600)
        try { quote = vm.previewSwap(f, t, raw, if (bridge) recipient.trim() else null) } catch (e: Exception) { quoteError = e.message ?: "No quote" }
    }

    if (srcs.isEmpty()) {
        Notice(
            Ic.Info, "Nothing to swap from yet",
            "Swaps start from a token in this wallet, like USDC or wNEAR. NEAR itself can't be swapped yet: NEAR Intents takes wrapped NEAR, and this wallet's contract can't wrap. Buy USDC, or receive a token, then swap it.",
            Tv.Text2,
        )
        return
    }
    PickRow("You pay", f?.let { "${it.symbol} · ${have?.let { h -> TokenAmount.format(h, it.decimals) } ?: "…"} held" } ?: "Choose") { pickFrom = true }
    Spacer(Modifier.height(10.dp))
    TvField("Amount", amount, { amount = it.trim().replace(',', '.') }, placeholder = "0.0", number = true,
        error = when { tooMuch -> "More than the wallet holds"; amount.isNotEmpty() && raw == null -> "Enter an amount like 10"; else -> null },
        trailing = {
            Text("Max", style = Type.CaptionStrong, color = Tv.Text, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Tv.GlassStrong)
                .clickable { if (f != null && have != null) amount = TokenAmount.format(have, f.decimals, maxFraction = f.decimals) }.padding(horizontal = 12.dp, vertical = 8.dp))
        })
    Spacer(Modifier.height(10.dp))
    PickRow(if (bridge) "They get (on ${ChainAddress.chain(chain ?: "")?.name ?: "the other chain"})" else "You get", t?.symbol ?: if (vm.intentTokens.isEmpty()) "Loading…" else "Choose") { if (vm.intentTokens.isNotEmpty()) pickTo = true }
    if (same) Caption("Pick a different token to get.", Modifier.padding(top = 6.dp, start = 4.dp), color = Tv.Warn)

    val q = quote
    val instant = f != null && raw != null && vm.swapIsInstant(f, raw)
    Spacer(Modifier.height(14.dp))
    Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        when {
            q != null && f != null && t != null -> {
                InfoRow("You get about", "${TokenAmount.format(q.amountOut, t.decimals, maxFraction = 6)} ${t.symbol}")
                InfoRow("At least", "${TokenAmount.format(q.minAmountOut, t.decimals, maxFraction = 6)} ${t.symbol}")
                Swap.rate(q, f.decimals, t.decimals)?.let { InfoRow("Rate", "1 ${f.symbol} = $it ${t.symbol}") }
                q.amountInUsd?.toBigDecimalOrNull()?.let { InfoRow("Value", Prices.usd(it)) }
                InfoRow("Takes", if (q.timeEstimateSecs > 0) "about ${(q.timeEstimateSecs + 59) / 60} min" else "a few minutes")
            }
            quoteError != null -> Caption(quoteError!!, Modifier.padding(vertical = 8.dp), color = Tv.Warn)
            raw != null && t != null && !same -> Row(Modifier.padding(vertical = 10.dp)) { Shimmer(Modifier.size(200.dp, 18.dp)) }
            else -> Caption("Enter an amount to see a quote.", Modifier.padding(vertical = 8.dp), color = Tv.Text3)
        }
        InfoRow("Route", "NEAR Intents")
        if (raw != null && f != null) InfoRow(
            "Goes out", if (instant) "Instantly" else "Would wait: not allowed for swaps",
            if (instant) Tv.Positive else Tv.Warn,
        )
    }
    if (raw != null && f != null && !instant) Notice(
        Ic.Timer, "Make ${f.symbol} instant first",
        "A quote lasts minutes, and a payment over your instant limit waits ${hoursLabel(vm.wallet?.delays?.transfer ?: 3600)}. Set an instant limit for ${f.symbol} on its token page (Home → ${f.symbol}), or swap less." +
            if (vm.wallet?.allowlist?.isNotEmpty() == true) " With instant receivers set, deposit addresses always wait." else "",
        Tv.Warn, Modifier.padding(top = 12.dp),
    )
    Spacer(Modifier.height(14.dp))
    TvButton(
        if (bridge) "Review bridge" else "Review swap", Modifier.fillMaxWidth(),
        enabled = vm.busy == null && q != null && instant && !tooMuch && !same && (!bridge || recipientOk),
    ) { review = true }

    if (pickFrom) TokenListSheet("Pay with", srcs.map { PickItem(it.symbol, it.name + if (!it.verified) " · unverified" else "", it.verified) }, { pickFrom = false }) { i -> from = srcs[i]; pickFrom = false }
    if (pickTo) {
        val list = if (bridge) vm.intentTokens.filter { it.blockchain == chain } else vm.intentTokens.filter { it.onNear && it.nearContract != null }
        val verified = KnownTokens.on(vm.network).map { it.contract }.toSet()
        val sorted = list.sortedWith(compareBy({ it.nearContract !in verified && !bridge }, { it.symbol }))
        TokenListSheet("Get", sorted.map { PickItem(it.symbol, if (bridge) (ChainAddress.chain(it.blockchain)?.name ?: it.blockchain) else (it.nearContract?.let { c -> if (c in verified) "Verified · ${shortAccount(c, 22)}" else "Unverified · ${shortAccount(c, 22)}" } ?: ""), bridge || it.nearContract in verified) }, { pickTo = false }) { i -> to = sorted[i]; pickTo = false }
    }
    if (review && q != null && f != null && t != null && raw != null) GlassSheet({ review = false }) {
        Text(if (bridge) "Review bridge" else "Review swap", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            InfoRow("You pay", "${TokenAmount.format(raw, f.decimals, maxFraction = f.decimals)} ${f.symbol}")
            InfoRow("You get at least", "${TokenAmount.format(q.minAmountOut, t.decimals, maxFraction = 6)} ${t.symbol}")
            if (bridge) {
                InfoRow("On", ChainAddress.chain(t.blockchain)?.name ?: t.blockchain)
            }
            Swap.rate(q, f.decimals, t.decimals)?.let { InfoRow("Rate", "1 ${f.symbol} = $it ${t.symbol}") }
            InfoRow("Route", "NEAR Intents (1Click)")
            InfoRow("Goes out", "Instantly", Tv.Positive)
        }
        if (bridge) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("To (on ${ChainAddress.chain(t.blockchain)?.name})", style = Type.Caption, color = Tv.Text2)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        val r = recipient.trim(); val cut = (r.length - 6).coerceAtLeast(0)
                        append(r.substring(0, cut))
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Tv.Text))
                        append(r.substring(cut)); pop()
                    },
                    style = Type.Body, color = Tv.Text2, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Notice(
            Ic.Info, "What your card approves",
            "A payment of ${f.symbol} from this wallet to a NEAR Intents deposit address. What arrives is NEAR Intents' promise, not something your card or the wallet's contract can enforce. If the price moves too far, the deposit comes back to this wallet, less a small fee.",
            Tv.Text2, Modifier.padding(top = 12.dp),
        )
        if (t.onNear && t.nearContract !in KnownTokens.on(vm.network).map { it.contract }.toSet()) Notice(
            Ic.Warning, "Check the token", "${t.symbol} isn't on Tapvault's list. Its name is whatever its creator chose.", Tv.Warn, Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(20.dp))
        SlideToConfirm(if (bridge) "Slide to bridge" else "Slide to swap") {
            review = false
            vm.swap(f, t, amount, if (bridge) recipient.trim() else null)
        }
        Spacer(Modifier.height(6.dp))
        TvButton("Edit", Modifier.fillMaxWidth(), Btn.Ghost) { review = false }
    }
}

/** Bridge: send a token to an address on another chain, or get a deposit address that brings one here. */
@Composable
private fun BridgePanel(vm: AppViewModel) {
    var out by remember { mutableStateOf(true) }
    val chains = ChainAddress.CHAINS.filter { c -> vm.intentTokens.any { it.blockchain == c.id } }.ifEmpty { ChainAddress.CHAINS }
    var chain by remember { mutableStateOf("eth") }
    var pickChain by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Send out", selected = out) { out = true }
        Chip("Receive in", selected = !out) { out = false }
    }
    Spacer(Modifier.height(12.dp))
    PickRow(if (out) "To chain" else "From chain", ChainAddress.chain(chain)?.name ?: chain) { pickChain = true }
    Spacer(Modifier.height(10.dp))
    if (out) {
        var address by remember { mutableStateOf("") }
        val problem = if (address.isBlank()) null else ChainAddress.problem(chain, address)
        TvField("Their ${ChainAddress.chain(chain)?.name} address", address, { address = it.trim() }, placeholder = ChainAddress.chain(chain)?.example ?: "", error = problem)
        Spacer(Modifier.height(12.dp))
        SwapPanel(vm, bridge = true, chain = chain, recipient = address, recipientOk = address.isNotBlank() && problem == null)
    } else {
        BridgeInPanel(vm, chain)
    }
    if (pickChain) TokenListSheet("Chain", chains.map { PickItem(it.name, it.example, true) }, { pickChain = false }) { i -> chain = chains[i].id; pickChain = false }
}

@Composable
private fun BridgeInPanel(vm: AppViewModel, chain: String) {
    val origin = vm.intentTokens.filter { it.blockchain == chain }
    val dests = vm.intentTokens.filter { it.nearContract != null && it.nearContract in KnownTokens.on(vm.network).map { k -> k.contract }.toSet() }
    var from by remember(chain) { mutableStateOf(origin.firstOrNull { it.symbol.startsWith("USDC") } ?: origin.firstOrNull()) }
    var to by remember(chain) { mutableStateOf<IntentToken?>(null) }
    LaunchedEffect(from, dests) { if (to == null) to = dests.firstOrNull { it.symbol == from?.symbol } ?: dests.firstOrNull { it.symbol == "USDC" } ?: dests.firstOrNull() }
    var amount by remember { mutableStateOf("") }
    var refund by remember { mutableStateOf("") }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<SwapRecord?>(null) }
    val f = from
    val raw = f?.let { TokenAmount.parse(amount, it.decimals) }
    val refundProblem = if (refund.isBlank()) null else ChainAddress.problem(chain, refund)
    PickRow("You send (on ${ChainAddress.chain(chain)?.name})", f?.symbol ?: "Loading…") { if (origin.isNotEmpty()) pickFrom = true }
    Spacer(Modifier.height(10.dp))
    TvField("Amount", amount, { amount = it.trim().replace(',', '.') }, placeholder = "0.0", number = true, error = if (amount.isNotEmpty() && raw == null) "Enter an amount like 10" else null)
    Spacer(Modifier.height(10.dp))
    PickRow("This wallet gets", to?.symbol ?: "Choose") { if (dests.isNotEmpty()) pickTo = true }
    Spacer(Modifier.height(10.dp))
    TvField("Your ${ChainAddress.chain(chain)?.name} address, for refunds", refund, { refund = it.trim() }, placeholder = ChainAddress.chain(chain)?.example ?: "", error = refundProblem)
    Caption("If the transfer can't complete, NEAR Intents sends your deposit back there.", Modifier.padding(top = 6.dp, start = 4.dp), color = Tv.Text3)
    Spacer(Modifier.height(14.dp))
    TvButton("Get deposit address", Modifier.fillMaxWidth(), enabled = vm.busy == null && f != null && to != null && raw != null && refund.isNotBlank() && refundProblem == null) {
        vm.bridgeIn(f!!, to!!, amount, refund) { shown = it }
    }
    if (pickFrom) TokenListSheet("Send", origin.map { PickItem(it.symbol, ChainAddress.chain(it.blockchain)?.name ?: it.blockchain, true) }, { pickFrom = false }) { i -> from = origin[i]; to = null; pickFrom = false }
    if (pickTo) TokenListSheet("Get", dests.map { PickItem(it.symbol, it.nearContract?.let { c -> shortAccount(c, 22) } ?: "", true) }, { pickTo = false }) { i -> to = dests[i]; pickTo = false }
    shown?.let { r -> DepositSheet(r) { shown = null } }
}

@Composable
private fun DepositSheet(r: SwapRecord, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    GlassSheet(onDismiss) {
        Text("Send ${r.amountIn}", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Caption("on ${r.chain}, to this address. This wallet gets ${r.amountOut}.", Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(r.depositAddress, Modifier.size(220.dp)) }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().surface(20.dp).clickable { clip.setText(AnnotatedString(r.depositAddress)) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deposit address · tap to copy", style = Type.Caption, color = Tv.Text2, modifier = Modifier.weight(1f))
                TvIcon(Ic.Copy, 16.dp, Tv.Text3)
            }
            Text(r.depositAddress, style = Type.Body, modifier = Modifier.padding(top = 2.dp))
        }
        Notice(
            Ic.Warning, "Only ${r.from} on ${r.chain}",
            "Send exactly ${r.amountIn}. Another token or chain is lost; less than that is refunded." +
                (r.deadline?.let { " The address works until " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) + "." } ?: ""),
            Tv.Warn, Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
        TvButton("Done", Modifier.fillMaxWidth(), Btn.Secondary) { onDismiss() }
    }
}

/** Buy or sell for cash through Ramp Network's page: KYC and payment happen there, never in Tapvault. */
@Composable
private fun RampPanel(vm: AppViewModel, buy: Boolean) {
    val uri = LocalUriHandler.current
    var asset by remember { mutableStateOf("NEAR") }
    val usdc = KnownTokens.on(vm.network).firstOrNull { it.symbol == "USDC" }
    var registered by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(asset, vm.busy) {
        registered = null
        if (asset == "USDC" && usdc != null && vm.busy == null) registered = runCatching { vm.tokenRegistered(usdc.contract) }.getOrNull()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Ramp.ASSETS.forEach { (sym, _) -> Chip(sym, selected = asset == sym) { asset = sym } }
    }
    Spacer(Modifier.height(14.dp))
    Notice(
        if (buy) Ic.Card else Ic.Send, if (buy) "Buy $asset with your bank card or transfer" else "Sell $asset for cash",
        if (buy) "${Ramp.PROVIDER} opens in your browser with this wallet's account filled in. They check your identity and take the payment; Tapvault never sees your card or bank details. The $asset arrives here, usually within minutes."
        else "${Ramp.PROVIDER} opens in your browser. When they show a deposit address and amount, come back and tap \"Pay the sale\": it's a normal payment from this wallet, approved with your card, so it follows your instant limits.",
        Tv.Text2,
    )
    if (asset == "USDC" && registered == false && usdc != null) Notice(
        Ic.Info, "Enable USDC first", "This wallet must be registered with USDC to receive it (about 0.00125 NEAR from your fee account).", Tv.Warn, Modifier.padding(top = 12.dp),
    ) { TvButton("Enable USDC", Modifier.fillMaxWidth(), Btn.Secondary, height = 48.dp, enabled = vm.busy == null) { vm.enableToken(usdc) } }
    Spacer(Modifier.height(14.dp))
    TvButton(
        "Continue with ${Ramp.PROVIDER}", Modifier.fillMaxWidth(), icon = Ic.External,
        enabled = vm.busy == null && (asset != "USDC" || !buy || registered == true),
    ) { uri.openUri(vm.rampUrl(buy, asset)) }
    if (!buy) {
        Spacer(Modifier.height(10.dp))
        TvButton("Pay the sale", Modifier.fillMaxWidth(), Btn.Secondary, enabled = vm.busy == null) {
            vm.openSend(AppViewModel.SendDraft(token = if (asset == "USDC") usdc else null, label = "Sale via ${Ramp.PROVIDER}"))
        }
    }
    Caption("Availability, limits and fees depend on your country and are set by ${Ramp.PROVIDER}.", Modifier.padding(top = 12.dp, start = 4.dp), color = Tv.Text3)
}

@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Tv.Glass).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Caption, color = Tv.Text2)
            Text(value, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TvIcon(Ic.ChevronDown, 18.dp, Tv.Text2)
    }
}

private class PickItem(val title: String, val sub: String, val trusted: Boolean)

/** A searchable list sheet for tokens and chains. */
@Composable
private fun TokenListSheet(title: String, items: List<PickItem>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    GlassSheet(onDismiss) {
        Text(title, style = Type.H2)
        Spacer(Modifier.height(12.dp))
        if (items.size > 8) TvField("", q, { q = it }, placeholder = "Search")
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
            Group {
                var first = true
                items.forEachIndexed { i, it ->
                    if (q.isNotBlank() && !it.title.contains(q.trim(), ignoreCase = true) && !it.sub.contains(q.trim(), ignoreCase = true)) return@forEachIndexed
                    if (!first) GroupDivider()
                    first = false
                    Row(Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TokenMark(it.title, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.title, style = Type.BodyStrong, maxLines = 1)
                            Caption(it.sub, color = if (it.trusted) Tv.Text2 else Tv.Warn)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
