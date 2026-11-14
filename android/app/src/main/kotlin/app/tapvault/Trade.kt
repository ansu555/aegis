package app.tapvault

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.tapvault.core.ChainAddress
import app.tapvault.core.IntentToken
import app.tapvault.core.KnownTokens
import app.tapvault.core.Markets
import app.tapvault.core.Network
import app.tapvault.core.SwapQuote
import app.tapvault.core.SwapRecord
import app.tapvault.core.SwapState
import app.tapvault.core.TokenAmount
import app.tapvault.core.TokenInfo
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

private enum class TradeTab(val label: String) { Markets("Markets"), Swap("Swap") }

/**
 * Trade: markets and one swap screen. A swap whose token is on another
 * chain (either side) is a bridge through NEAR Intents. On mainnet (in a
 * build with mainnet) every swap is a normal payment to a deposit address,
 * approved like any other; elsewhere Trade is a preview
 * ([AppViewModel.tradePreview]) with live markets and dry quotes, and
 * nothing is sent.
 */
@Composable
fun TradeScreen(vm: AppViewModel) {
    LaunchedEffect(vm.network, vm.activeId) { vm.refreshPrices(); vm.refreshTokens(); vm.refreshMarkets() }
    // follow unfinished swaps while Trade is open
    LaunchedEffect(vm.swaps) {
        while (vm.swaps.any { !it.state.done }) { vm.refreshSwaps(); delay(10_000) }
    }
    var tab by remember { mutableStateOf(TradeTab.Markets) }
    var preset by remember { mutableStateOf<IntentToken?>(null) }
    GlassScrollTabs(TradeTab.entries.map { it.label }, tab.ordinal, Modifier.padding(top = 6.dp)) { tab = TradeTab.entries[it] }
    Spacer(Modifier.height(16.dp))
    when (tab) {
        TradeTab.Markets -> MarketsPanel(vm) { a -> preset = a; tab = TradeTab.Swap }
        TradeTab.Swap -> SwapPanel(vm, preset)
    }
    if (!vm.tradePreview && vm.swaps.isNotEmpty() && tab == TradeTab.Swap) {
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

/** The chain an asset lives on, by name ("Ethereum"); "NEAR" for NEAR tokens. */
private fun chainName(t: IntentToken) = if (t.onNear) "NEAR" else ChainAddress.chain(t.blockchain)?.name ?: t.blockchain

private fun IntentToken.sameAs(o: IntentToken?) = o != null && assetId == o.assetId

/** "33.2484", "0.00012345": six significant digits, no trailing zeros. */
private fun show(v: BigDecimal): String =
    if (v.signum() == 0) "0" else v.round(MathContext(6, RoundingMode.DOWN)).stripTrailingZeros().toPlainString()

/**
 * One screen for swaps and bridges: what you send (a token in this wallet,
 * or one on another chain to bring in) and what you receive (any token on
 * NEAR, or one on another chain, which takes the receiver's address).
 */
@Composable
private fun SwapPanel(vm: AppViewModel, preset: IntentToken?) {
    val preview = vm.tradePreview
    val all = vm.intentTokens
    val known = KnownTokens.on(Network.MAINNET).map { it.contract }
    val nearTokens = all.filter { it.onNear && it.nearContract != null && it.price != null }
        .sortedWith(compareBy({ it.nearContract !in known }, { known.indexOf(it.nearContract) }, { it.symbol.lowercase() }))
    val otherChains = all.filter { !it.onNear && ChainAddress.chain(it.blockchain) != null && it.price != null }
        .sortedWith(compareBy({ it.symbol.lowercase() }, { chainName(it) }))
    // what can be sent from here: in a preview every NEAR token, else the wallet's tokens with a balance
    val walletContracts = vm.shownTokens.filter { it.raw.signum() > 0 }.map { it.token.contract }
    val sendable = if (preview) nearTokens else nearTokens.filter { it.nearContract in walletContracts }

    var from by remember { mutableStateOf<IntentToken?>(null) }
    var to by remember(preset) { mutableStateOf(preset) }
    var amount by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<Boolean?>(null) } // true: send side, false: receive side
    var quote by remember { mutableStateOf<SwapQuote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<SwapRecord?>(null) }
    var flips by remember { mutableStateOf(0) }

    LaunchedEffect(all.size, sendable.size, preset) {
        if (from == null || from!!.sameAs(to)) from = (sendable.firstOrNull { it.nearContract == "wrap.near" && !it.sameAs(to) } ?: sendable.firstOrNull { !it.sameAs(to) })
        if (to == null) to = nearTokens.firstOrNull { it.nearContract == known.first() && !it.sameAs(from) } ?: nearTokens.firstOrNull { !it.sameAs(from) }
    }

    val f = from
    val t = to
    val fromInfo = f?.takeIf { it.onNear }?.let { vm.tokenInfoOf(it) }
    val bridgeIn = f != null && !f.onNear
    val bridgeOut = !bridgeIn && t != null && !t.onNear
    val addressChain = when { bridgeIn -> f!!.blockchain; bridgeOut -> t!!.blockchain; else -> null }
    val addressProblem = if (addressChain == null || address.isBlank()) null else ChainAddress.problem(addressChain, address)
    val addressOk = addressChain == null || (address.isNotBlank() && addressProblem == null)
    val raw = f?.let { TokenAmount.parse(amount, it.decimals) }?.takeIf { it.signum() > 0 }
    val have = if (preview || fromInfo == null) null else vm.tokens?.firstOrNull { it.token.contract == fromInfo.contract }?.raw
    val tooMuch = raw != null && have != null && raw > have
    val inUsd = if (raw != null && f?.price != null) BigDecimal(raw).movePointLeft(f.decimals).multiply(f.price) else null
    // without a quote yet (no address, still loading): an estimate from 1Click's prices
    val estimate = if (inUsd != null && t?.price != null && t.price!!.signum() > 0) inUsd.divide(t.price, 12, RoundingMode.DOWN) else null
    val outAmount = quote?.let { q -> t?.let { BigDecimal(q.amountOut).movePointLeft(it.decimals) } } ?: estimate

    LaunchedEffect(f, t, amount, address) {
        quote = null; quoteError = null; quoting = false
        if (f == null || t == null || raw == null || f.sameAs(t) || !addressOk) return@LaunchedEffect
        if (!bridgeIn && fromInfo == null) return@LaunchedEffect
        quoting = true
        delay(500)
        try {
            quote = if (bridgeIn) vm.previewBridgeIn(f, t, raw, address.trim()) else vm.previewSwap(fromInfo!!, t, raw, if (bridgeOut) address.trim() else null)
        } catch (e: Exception) {
            quoteError = e.message ?: "No quote right now"
        }
        quoting = false
    }

    if (all.isEmpty()) {
        Shimmer(Modifier.fillMaxWidth().height(170.dp)); Spacer(Modifier.height(8.dp)); Shimmer(Modifier.fillMaxWidth().height(170.dp))
        return
    }

    Box {
        Column {
            // ---- send
            SwapCard(
                vm, f, "Send", onPick = { pick = true },
                addressLabel = if (bridgeIn) "Refund address on ${chainName(f!!)}" else null, address = address, onAddress = { address = it.trim() }, addressError = if (bridgeIn) addressProblem else null,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (have != null && fromInfo != null) SmallPill("Half") { amount = TokenAmount.format(have.divide(BigInteger.TWO), fromInfo.decimals, maxFraction = fromInfo.decimals) }
                    else Spacer(Modifier.width(56.dp))
                    BasicTextField(
                        amount, { v -> amount = v.trim().replace(',', '.').filter { it.isDigit() || it == '.' } },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = Type.Hero.copy(fontSize = 40.sp, color = if (tooMuch) Tv.Danger else Tv.Text, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        cursorBrush = SolidColor(Tv.Text),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (amount.isEmpty()) Text("0", style = Type.Hero.copy(fontSize = 40.sp), color = Tv.Text3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                inner()
                            }
                        },
                    )
                    if (have != null && fromInfo != null) SmallPill("Max") { amount = TokenAmount.format(have, fromInfo.decimals, maxFraction = fromInfo.decimals) }
                    else Spacer(Modifier.width(56.dp))
                }
                Text(
                    when {
                        have != null && fromInfo != null -> "Balance: ${TokenAmount.format(have, fromInfo.decimals)} ${fromInfo.symbol}"
                        inUsd != null -> "≈ " + Markets.price(inUsd.toDouble())
                        else -> " "
                    },
                    style = Type.Caption, color = if (tooMuch) Tv.Danger else Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            // the flip button sits on the seam between the cards
            Box(Modifier.fillMaxWidth().height(8.dp).zIndex(1f), contentAlignment = Alignment.Center) {
                val turn by animateFloatAsState(flips * 180f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "flip")
                Box(
                    Modifier.requiredSize(48.dp).clip(CircleShape).background(Tv.Accent).border(4.dp, Tv.Bg, CircleShape)
                        .pressable(haptic = true) {
                            val a = from; val b = to
                            if (a != null && b != null) {
                                from = b
                                // two other-chain tokens can't pair: the far side becomes USDC on NEAR
                                to = if (!b.onNear && !a.onNear) nearTokens.firstOrNull { it.nearContract == known.first() } else a
                                address = ""; amount = ""; flips++
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { TvIcon(Ic.Down, 20.dp, Tv.OnAccent, Modifier.rotate(turn)) }
            }
            // ---- receive
            SwapCard(
                vm, t, "Receive", onPick = { pick = false },
                addressLabel = if (bridgeOut) "Their address on ${chainName(t!!)}" else null, address = address, onAddress = { address = it.trim() }, addressError = if (bridgeOut) addressProblem else null,
            ) {
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    when {
                        quoting && outAmount == null -> Shimmer(Modifier.size(140.dp, 36.dp))
                        outAmount != null -> Text((if (quote == null) "≈" else "") + show(outAmount), style = Type.Hero.copy(fontSize = 40.sp), color = if (quote == null) Tv.Text2 else Tv.Text, maxLines = 1)
                        else -> Text("0", style = Type.Hero.copy(fontSize = 40.sp), color = Tv.Text3)
                    }
                }
                val outUsd = quote?.amountOutUsd?.toBigDecimalOrNull() ?: estimate?.let { e -> t?.price?.let { e.multiply(it) } }
                Text(outUsd?.let { "≈ " + Markets.price(it.toDouble()) } ?: " ", style = Type.Caption, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }

    // ---- action
    val q = quote
    val instant = preview || bridgeIn || (fromInfo != null && raw != null && vm.swapIsInstant(fromInfo, raw))
    val verb = if (bridgeIn || bridgeOut) "Bridge" else "Swap"
    val ready = raw != null && t != null && f != null && !f.sameAs(t) && addressOk && outAmount != null
    Spacer(Modifier.height(16.dp))
    TvButton(
        when {
            tooMuch -> "Not enough ${f?.symbol}"
            !instant && raw != null -> "Over your instant limit"
            bridgeIn && !preview -> "Get deposit address"
            else -> verb
        },
        Modifier.fillMaxWidth(),
        enabled = vm.busy == null && ready && !tooMuch && instant && (preview || q != null),
    ) {
        if (bridgeIn && !preview) vm.bridgeIn(f!!, t!!, amount, address) { shown = it } else review = true
    }
    when {
        !instant && raw != null && fromInfo != null -> Caption("Set an instant limit for ${fromInfo.symbol} on its token page, or swap less.", Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center, color = Tv.Text3)
        quoteError != null -> Caption(quoteError!!, Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center, color = Tv.Warn)
    }

    // ---- details
    if (f != null && t != null && raw != null && outAmount != null) {
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().surface(22.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val inn = BigDecimal(raw).movePointLeft(f.decimals)
            if (inn.signum() > 0) InfoRow("Rate", "1 ${f.symbol} = ${show(outAmount.divide(inn, 12, RoundingMode.DOWN))} ${t.symbol}")
            if (q != null) {
                InfoRow("Minimum received", "${show(BigDecimal(q.minAmountOut).movePointLeft(t.decimals))} ${t.symbol}")
                val a = q.amountInUsd?.toBigDecimalOrNull(); val b = q.amountOutUsd?.toBigDecimalOrNull()
                if (a != null && b != null && a.signum() > 0) InfoRow("Fees and price impact", String.format(java.util.Locale.US, "%.2f%%", (a - b).divide(a, 6, RoundingMode.HALF_UP).toDouble() * 100))
                if (q.timeEstimateSecs > 0) InfoRow("Arrives in", if (q.timeEstimateSecs < 90) "~${q.timeEstimateSecs} s" else "~${(q.timeEstimateSecs + 59) / 60} min")
            }
            InfoRow("Route", if (bridgeIn || bridgeOut) "NEAR Intents · ${chainName(if (bridgeIn) f else t)}" else "NEAR Intents")
        }
    }

    pick?.let { sendSide ->
        val items = if (sendSide) sendable + otherChains else if (bridgeIn) nearTokens.filter { it.nearContract in known } else nearTokens + otherChains
        TokenPicker(vm, if (sendSide) "Send" else "Receive", items.filter { !it.sameAs(if (sendSide) to else from) }, showBalances = sendSide && !preview, onDismiss = { pick = null }) { picked ->
            if (sendSide) {
                from = picked
                if (!picked.onNear && to?.onNear == false) to = nearTokens.firstOrNull { it.nearContract == known.first() }
            } else to = picked
            address = ""; pick = null
        }
    }
    if (review && f != null && t != null && raw != null && outAmount != null) {
        ConfirmSheet(vm, f, t, raw, q, outAmount, if (bridgeOut) address.trim() else null, bridgeIn, preview, onDismiss = { review = false }) {
            review = false
            if (fromInfo != null) vm.swap(fromInfo, t, amount, if (bridgeOut) address.trim() else null)
        }
    }
    shown?.let { r -> DepositSheet(r) { shown = null } }
}

/** One side of a swap: its token chip, a "Send"/"Receive" pill, the amount, and an address when the token is on another chain. */
@Composable
private fun SwapCard(
    vm: AppViewModel, token: IntentToken?, label: String, onPick: () -> Unit,
    addressLabel: String?, address: String, onAddress: (String) -> Unit, addressError: String?,
    body: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Tv.Surface)
            .background(Brush.linearGradient(listOf(Color(0xFF6E5BB0).copy(alpha = 0.30f), Color(0xFFB06E8C).copy(alpha = 0.12f), Color.Transparent)))
            .border(1.dp, Tv.Stroke, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).background(Tv.GlassStrong).clickable(onClick = onPick).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (token != null) TokenMark(token.symbol, 32.dp, vm.iconFor(token)) else Box(Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(token?.symbol ?: "Choose", style = Type.BodyStrong, maxLines = 1)
                    if (token != null && !token.onNear) Text(chainName(token), style = Type.Overline, color = Tv.Text2)
                }
                Spacer(Modifier.width(4.dp))
                TvIcon(Ic.ChevronDown, 16.dp, Tv.Text2)
            }
            Spacer(Modifier.weight(1f))
            Text(label, style = Type.Caption, color = Tv.Text2, modifier = Modifier.border(1.dp, Tv.StrokeStrong, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(14.dp))
        body()
        // room for the flip button on the seam
        Spacer(Modifier.height(10.dp))
        if (addressLabel != null) {
            Spacer(Modifier.height(12.dp))
            TvField("", address, onAddress, placeholder = addressLabel, error = addressError)
        }
    }
}

@Composable
private fun SmallPill(text: String, onClick: () -> Unit) {
    Text(
        text, style = Type.CaptionStrong, color = Tv.OnAccent,
        modifier = Modifier.width(56.dp).clip(RoundedCornerShape(50)).background(Tv.Accent).clickable(onClick = onClick).padding(vertical = 5.dp),
        textAlign = TextAlign.Center,
    )
}

/** Pick a token: search, logos, the chain it's on, and (sending) what the wallet holds. */
@Composable
private fun TokenPicker(vm: AppViewModel, title: String, items: List<IntentToken>, showBalances: Boolean, onDismiss: () -> Unit, onPick: (IntentToken) -> Unit) {
    var q by remember { mutableStateOf("") }
    val known = KnownTokens.on(Network.MAINNET).map { it.contract }
    GlassSheet(onDismiss) {
        Text(title, style = Type.H2)
        Spacer(Modifier.height(12.dp))
        TvField("", q, { q = it }, placeholder = "Search name or chain", leading = Ic.Search)
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
            val shown = items.filter { q.isBlank() || it.symbol.contains(q.trim(), true) || chainName(it).contains(q.trim(), true) }
            if (shown.isEmpty()) Caption("Nothing matches.", Modifier.padding(8.dp), color = Tv.Text3)
            shown.forEach { it ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(it) }.padding(horizontal = 6.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    TokenMark(it.symbol, 38.dp, vm.iconFor(it))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.symbol, style = Type.BodyStrong, maxLines = 1)
                            if (it.onNear && it.nearContract !in known) Text("  unverified", style = Type.Caption, color = Tv.Warn)
                        }
                        Text(if (it.onNear) "NEAR" + (it.nearContract?.let { c -> " · " + shortAccount(c, 18) } ?: "") else chainName(it), style = Type.Caption, color = Tv.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val bal = if (showBalances) vm.tokens?.firstOrNull { tb -> tb.token.contract == it.nearContract } else null
                    Text(
                        bal?.let { b -> TokenAmount.format(b.raw, b.token.decimals) } ?: Markets.price(it.price?.toDouble()),
                        style = Type.Body, color = Tv.Text2, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Before a swap goes out: what is paid and what arrives. On mainnet a slide
 * sends it; in a preview the same summary shows what mainnet would do.
 */
@Composable
private fun ConfirmSheet(
    vm: AppViewModel, f: IntentToken, t: IntentToken, raw: BigInteger, q: SwapQuote?, out: BigDecimal, recipient: String?, bridgeIn: Boolean, preview: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    val pay = show(BigDecimal(raw).movePointLeft(f.decimals))
    val atLeast = q?.let { show(BigDecimal(it.minAmountOut).movePointLeft(t.decimals)) }
    val time = q?.timeEstimateSecs?.takeIf { it > 0 }?.let { if (it < 90) "~$it s" else "~${(it + 59) / 60} min" }
    GlassSheet(onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TokenMark(f.symbol, 52.dp, vm.iconFor(f))
            Box(Modifier.padding(horizontal = 14.dp)) { TvIcon(Ic.Chevron, 22.dp, Tv.Text2) }
            TokenMark(t.symbol, 52.dp, vm.iconFor(t))
        }
        Spacer(Modifier.height(14.dp))
        Text("$pay ${f.symbol}", style = Type.H2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("for ${atLeast?.let { "at least $it" } ?: "≈ ${show(out)}"} ${t.symbol}" + (if (!t.onNear) " on ${chainName(t)}" else ""), style = Type.Body, color = Tv.Text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        val approve = if (vm.wallet?.phoneOnly == true) "Approve with your fingerprint" else "Tap your card, then fingerprint"
        val steps = if (bridgeIn) listOf(
            Ic.Qr to "Get a deposit address on ${chainName(f)}",
            Ic.Send to "Send $pay ${f.symbol} from your ${chainName(f)} wallet",
            Ic.Receive to "Get ≈ ${show(out)} ${t.symbol} here",
        ) else listOf(
            Ic.Card to approve,
            Ic.Send to "Pay $pay ${f.symbol} to NEAR Intents",
            Ic.Receive to ("Get ${atLeast ?: "≈ " + show(out)} ${t.symbol}" + (recipient?.let { " at ${shortAccount(it, 14)}" } ?: "") + (time?.let { " · $it" } ?: "")),
        )
        Column(Modifier.fillMaxWidth().surface(22.dp).padding(horizontal = 16.dp, vertical = 6.dp)) {
            steps.forEach { (icon, text) ->
                Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(icon, Tv.Text, Tv.GlassStrong, 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = Type.Body, modifier = Modifier.weight(1f))
                }
            }
        }
        if (t.onNear && t.nearContract !in KnownTokens.on(Network.MAINNET).map { it.contract }) {
            Caption("${t.symbol} isn't on Aegis's list: ${shortAccount(t.nearContract ?: "", 28)}", Modifier.fillMaxWidth().padding(top = 10.dp), align = TextAlign.Center, color = Tv.Warn)
        }
        Spacer(Modifier.height(18.dp))
        if (preview) {
            if (vm.network.isTestnet && vm.mainnetEnabled) {
                TvButton("Switch to mainnet", Modifier.fillMaxWidth(), icon = Ic.Layers, enabled = vm.busy == null) { onDismiss(); vm.switchNetwork(Network.MAINNET) }
            } else {
                TvButton("Available on mainnet", Modifier.fillMaxWidth(), icon = Ic.Lock, enabled = false) {}
            }
            Caption("Preview · nothing is sent", Modifier.fillMaxWidth().padding(top = 8.dp), align = TextAlign.Center, color = Tv.Text3)
        } else {
            SlideToConfirm(if (recipient != null) "Slide to bridge" else "Slide to swap") { onConfirm() }
            Spacer(Modifier.height(6.dp))
            TvButton("Edit", Modifier.fillMaxWidth(), Btn.Ghost) { onDismiss() }
        }
    }
}

@Composable
private fun DepositSheet(r: SwapRecord, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    GlassSheet(onDismiss) {
        Text("Send ${r.amountIn}", style = Type.H2, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Caption("on ${r.chain}, to this address. This wallet gets ${r.amountOut}.", Modifier.fillMaxWidth().padding(top = 4.dp), align = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrCode(r.depositAddress, Modifier.size(220.dp), reveal = true) }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().surface(20.dp).clickable { clip.setText(AnnotatedString(r.depositAddress)) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deposit address · tap to copy", style = Type.Caption, color = Tv.Text2, modifier = Modifier.weight(1f))
                TvIcon(Ic.Copy, 16.dp, Tv.Text3)
            }
            Text(r.depositAddress, style = Type.Body, modifier = Modifier.padding(top = 2.dp))
        }
        Caption(
            "Only ${r.from} on ${r.chain}, exactly ${r.amountIn}." +
                (r.deadline?.let { " Works until " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) + "." } ?: ""),
            Modifier.fillMaxWidth().padding(top = 10.dp), align = TextAlign.Center, color = Tv.Warn,
        )
        Spacer(Modifier.height(12.dp))
        TvButton("Done", Modifier.fillMaxWidth(), Btn.Secondary) { onDismiss() }
    }
}
