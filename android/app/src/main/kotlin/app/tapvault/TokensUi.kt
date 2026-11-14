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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tapvault.core.Asset
import app.tapvault.core.Near
import app.tapvault.core.MAX_LIMITS
import app.tapvault.core.Prices
import app.tapvault.core.TokenAmount
import app.tapvault.core.TokenBalance
import app.tapvault.core.TokenInfo
import kotlinx.coroutines.launch

/**
 * Home's token list: NEAR, then the tokens the wallet shows (built-in ones
 * with a balance, and the ones the user added), then Manage tokens. Tap a
 * token for its details.
 */
@Composable
fun TokensSection(vm: AppViewModel, modifier: Modifier = Modifier) {
    var manage by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<TokenBalance?>(null) }
    LaunchedEffect(vm.activeId, vm.network) { vm.refreshTokens(); vm.refreshPrices() }
    Column(modifier) {
        SectionHeader("Tokens", action = "Manage") { manage = true }
        Group {
            TokenRow("NEAR", "NEAR", vm.balance?.let { Near.format(it, 4) }, verified = true, usd = vm.balance?.let { b -> vm.prices?.value(null, b, 24) })
            vm.shownTokens.forEach { tb ->
                GroupDivider()
                TokenRow(tb.token.symbol, tb.token.name, TokenAmount.format(tb.raw, tb.token.decimals), tb.token.verified, vm.prices?.value(tb.token.contract, tb.raw, tb.token.decimals)) { open = tb }
            }
            if (vm.tokens == null) {
                GroupDivider()
                Box(Modifier.fillMaxWidth().padding(16.dp)) { Shimmer(Modifier.size(180.dp, 18.dp)) }
            }
            GroupDivider()
            Row_(Ic.Plus, "Manage tokens", vm.foundTokens.size.let { if (it > 0) "Add, hide · $it more found in this wallet" else "Add a token, hide one" }, chevron = true, onClick = { manage = true })
        }
    }
    if (manage) ManageTokensSheet(vm, { manage = false }) { manage = false; open = it }
    open?.let { tb -> TokenSheet(vm, tb) { open = null } }
}

@Composable
private fun TokenRow(symbol: String, name: String, amount: String?, verified: Boolean, usd: java.math.BigDecimal? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenMark(symbol)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(symbol, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (verified) name else "Unverified · $name", style = Type.Caption, color = if (verified) Tv.Text2 else Tv.Warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        if (amount == null) Shimmer(Modifier.size(60.dp, 18.dp)) else Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = Type.Amount, maxLines = 1)
            usd?.let { Text(Prices.usd(it), style = Type.Caption, color = Tv.Text2, maxLines = 1) }
        }
    }
}

/** A token's mark: its symbol's first letter in a circle (token icons are whatever their creator uploaded, so none are drawn). */
@Composable
fun TokenMark(symbol: String, size: Dp = 40.dp) {
    Box(Modifier.size(size).background(Tv.GlassStrong, CircleShape).glassRim(size / 2, shape = CircleShape), contentAlignment = Alignment.Center) {
        Text(symbol.trim().firstOrNull()?.uppercase() ?: "?", style = Type.Title.copy(fontSize = (size.value * 0.4f).sp), color = Tv.Text)
    }
}

/** Manage tokens: show or hide each token, add one by its contract, or add one found in the wallet. */
@Composable
fun ManageTokensSheet(vm: AppViewModel, onDismiss: () -> Unit, onOpen: (TokenBalance) -> Unit) {
    var contract by remember { mutableStateOf("") }
    var looked by remember { mutableStateOf<TokenInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    GlassSheet(onDismiss) {
        Text("Manage tokens", style = Type.H2)
        Caption("Choose what Home shows. Hidden tokens stay in the wallet.", Modifier.padding(top = 4.dp, bottom = 14.dp))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            TvField(
                "Add a token by contract", contract, { contract = it.trim().lowercase(); looked = null; error = null },
                placeholder = if (vm.network.isMainnet) "token.near" else "usdc.fakes.testnet", error = error,
                trailing = {
                    Text(
                        if (looking) "…" else "Look up", style = Type.CaptionStrong, color = Tv.Text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Tv.GlassStrong)
                            .clickable(enabled = contract.isNotBlank() && !looking) {
                                looking = true
                                scope.launch {
                                    try { looked = vm.lookUpToken(contract) } catch (e: Exception) { error = e.message ?: "Couldn't look it up." }
                                    looking = false
                                }
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
            )
            looked?.let { t ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().surface(20.dp).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TokenMark(t.symbol)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${t.symbol} · ${t.name}", style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Caption("${t.decimals} decimals · ${shortAccount(t.contract, 30)}")
                        }
                    }
                    if (!t.verified) Notice(
                        Ic.Warning, "Unverified token",
                        "Anyone can create a token with any name, including copies of real ones. Add it only if you got this contract from a source you trust.",
                        Tv.Warn, Modifier.padding(top = 12.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    TvButton(if (t.contract in vm.tokenPrefs.added) "Already added" else "Add ${t.symbol}", Modifier.fillMaxWidth(), height = 48.dp, enabled = t.contract !in vm.tokenPrefs.added) {
                        vm.showToken(t.contract); looked = null; contract = ""
                    }
                }
            }

            val all = vm.tokens.orEmpty()
            if (all.isNotEmpty()) {
                Text("Your tokens", style = Type.Title, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp, start = 4.dp))
                Group {
                    all.forEachIndexed { i, tb ->
                        if (i > 0) GroupDivider()
                        val shown = vm.tokenPrefs.isShown(tb.token.contract, tb.token.verified, tb.raw)
                        Row(Modifier.fillMaxWidth().clickable { onOpen(tb) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            TokenMark(tb.token.symbol, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tb.token.symbol, style = Type.BodyStrong, maxLines = 1)
                                Caption(
                                    TokenAmount.format(tb.raw, tb.token.decimals) + if (!tb.token.verified) " · unverified" else "",
                                    color = if (tb.token.verified) Tv.Text2 else Tv.Warn,
                                )
                            }
                            Switch(
                                shown, { on -> if (on) vm.showToken(tb.token.contract) else vm.hideToken(tb.token.contract) },
                                colors = SwitchDefaults.colors(checkedThumbColor = Tv.OnAccent, checkedTrackColor = Tv.Accent, uncheckedThumbColor = Tv.Text2, uncheckedTrackColor = Tv.Glass, uncheckedBorderColor = Tv.StrokeStrong),
                            )
                        }
                    }
                }
            } else if (vm.tokens == null) Caption("Loading your tokens…", Modifier.padding(top = 18.dp, start = 4.dp))

            if (vm.foundTokens.isNotEmpty()) {
                Text("Found in this wallet", style = Type.Title, modifier = Modifier.padding(top = 22.dp, start = 4.dp))
                Caption("Sent to this wallet, not on Tapvault's list. Tokens can be sent to anyone unasked; their names can lie.", Modifier.padding(top = 2.dp, bottom = 10.dp, start = 4.dp))
                Group {
                    vm.foundTokens.forEachIndexed { i, tb ->
                        if (i > 0) GroupDivider()
                        Row_(
                            Ic.Warning, tb.token.symbol, "${TokenAmount.format(tb.raw, tb.token.decimals)} · ${shortAccount(tb.token.contract, 26)}",
                            tint = Tv.Warn, iconBg = Tv.WarnSoft, value = "Add", onClick = { vm.showToken(tb.token.contract) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** One token: balance, contract, whether the wallet can receive it (Enable if not), how sending it works. */
@Composable
fun TokenSheet(vm: AppViewModel, tb: TokenBalance, onDismiss: () -> Unit) {
    val t = tb.token
    val uri = LocalUriHandler.current
    val clip = LocalClipboardManager.current
    // null = checking, true/false = known, "unknown" when the token doesn't say
    var registered by remember { mutableStateOf<Boolean?>(tb.registered) }
    var unknown by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    LaunchedEffect(t.contract, vm.busy) {
        if (registered == true || vm.busy != null) return@LaunchedEffect
        try { vm.tokenRegistered(t.contract).let { if (it == null) unknown = true else registered = it } } catch (e: Exception) { checkFailed = true }
    }
    GlassSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenMark(t.symbol, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.symbol, style = Type.H2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Caption(if (t.verified) t.name else "Unverified · ${t.name}", color = if (t.verified) Tv.Text2 else Tv.Warn)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(TokenAmount.format(tb.raw, t.decimals, maxFraction = 8) + " " + t.symbol, style = Type.Hero)
        vm.prices?.let { p ->
            p.of(t.contract)?.let { price -> Caption("${Prices.usd(p.value(t.contract, tb.raw, t.decimals))} · 1 ${t.symbol} = ${Prices.usd(price)}", Modifier.padding(top = 4.dp)) }
        }
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { clip.setText(AnnotatedString(t.contract)) }, verticalAlignment = Alignment.CenterVertically) {
                InfoRow("Contract", shortAccount(t.contract, 24), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TvIcon(Ic.Copy, 16.dp, Tv.Text3)
            }
            InfoRow("Network", vm.network.displayName)
            InfoRow(
                "Receive", when {
                    registered == true -> "Ready"
                    registered == false -> "Not enabled yet"
                    unknown -> "Token doesn't say"
                    checkFailed -> "Couldn't check"
                    else -> "Checking…"
                },
                if (registered == true) Tv.Positive else if (registered == false) Tv.Warn else Tv.Text,
            )
            val limit = vm.wallet?.limits?.firstOrNull { it.asset == Asset.Ft(t.contract).json() }
            if (!vm.seedWallet) InfoRow(
                "Sending",
                if (limit != null) "Instant up to ${TokenAmount.format(limit.instantMax, t.decimals)} ${t.symbol}" else "Always waits ${hoursLabel(vm.wallet?.delays?.transfer ?: 3600)}",
            )
        }
        if (registered == false) {
            Notice(
                Ic.Info, "Enable ${t.symbol} to receive it",
                "A token only accepts accounts registered with it. Your " + (if (vm.store.gasAccount != null) "fee account" else "wallet") +
                    " pays the registration, usually about 0.00125 NEAR. No card needed: nothing leaves the wallet.",
                Tv.Text2, Modifier.padding(top = 14.dp),
            ) {
                TvButton("Enable ${t.symbol}", Modifier.fillMaxWidth(), Btn.Secondary, height = 48.dp, enabled = vm.busy == null) {
                    vm.enableToken(t) { registered = true }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Send", Modifier.weight(1f), icon = Ic.Send, height = 48.dp, enabled = vm.busy == null && tb.raw.signum() > 0 && vm.wallet?.frozen != true) {
                onDismiss(); vm.openSend(AppViewModel.SendDraft(token = t))
            }
            if (vm.tradeAvailable) TvButton("Swap", Modifier.weight(1f), Btn.Secondary, icon = Ic.Refresh, height = 48.dp, enabled = vm.busy == null) {
                onDismiss(); vm.go(Screen.Trade)
            }
        }
        if (!vm.seedWallet && vm.wallet != null) LimitEditor(vm, t)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Explorer", Modifier.weight(1f), Btn.Secondary, icon = Ic.External, height = 48.dp) { uri.openUri(vm.near.explorerAccount(t.contract)) }
            val shown = vm.tokenPrefs.isShown(t.contract, t.verified, tb.raw)
            TvButton(if (shown) "Hide" else "Show on Home", Modifier.weight(1f), Btn.Ghost, height = 48.dp) {
                if (shown) vm.hideToken(t.contract) else vm.showToken(t.contract)
                onDismiss()
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A token's instant limit: without one every transfer of it waits the
 * transfer delay (and swaps from it aren't possible). Setting or removing it
 * is a settings change that runs after the settings delay.
 */
@Composable
private fun LimitEditor(vm: AppViewModel, t: TokenInfo) {
    val w = vm.wallet ?: return
    val limit = w.limitFor(Asset.Ft(t.contract))
    var editing by remember { mutableStateOf(false) }
    var instant by remember { mutableStateOf(limit?.let { TokenAmount.format(it.instantMax, t.decimals, maxFraction = t.decimals) } ?: "") }
    var daily by remember { mutableStateOf(limit?.let { TokenAmount.format(it.dailyMax, t.decimals, maxFraction = t.decimals) } ?: "") }
    val pending = vm.proposals.any { p -> !p.stale && (p.describe().contains("SetLimit") || p.describe().contains("RemoveLimit")) }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).surface(20.dp).padding(14.dp)) {
        Text("Instant limit", style = Type.BodyStrong)
        Caption(
            when {
                limit != null -> "Up to ${TokenAmount.format(limit.instantMax, t.decimals)} ${t.symbol} at once, ${TokenAmount.format(limit.dailyMax, t.decimals)} a day go out instantly; more waits ${hoursLabel(w.delays.transfer)}. ${TokenAmount.format(limit.availableNow(), t.decimals)} left now."
                !w.canAddLimit(Asset.Ft(t.contract)) -> "The wallet already has $MAX_LIMITS instant limits, the most it holds. Every ${t.symbol} payment waits ${hoursLabel(w.delays.transfer)}."
                else -> "None: every ${t.symbol} payment waits ${hoursLabel(w.delays.transfer)}, and swaps from ${t.symbol} aren't possible."
            },
            Modifier.padding(top = 2.dp),
        )
        if (pending) Caption("A limit change is waiting in Activity.", Modifier.padding(top = 6.dp), color = Tv.Warn)
        if (editing) {
            Spacer(Modifier.height(10.dp))
            TvField("At once (${t.symbol})", instant, { instant = it.trim().replace(',', '.') }, placeholder = "50", number = true)
            Spacer(Modifier.height(8.dp))
            TvField("A day (${t.symbol})", daily, { daily = it.trim().replace(',', '.') }, placeholder = "200", number = true)
            Caption("Takes effect after ${hoursLabel(w.delays.config)}, approved " + (if (w.phoneOnly) "with your phone." else "with your phone and card."), Modifier.padding(top = 6.dp), color = Tv.Text3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Request", Modifier.weight(1f), height = 46.dp, enabled = vm.busy == null && TokenAmount.parse(instant, t.decimals) != null && TokenAmount.parse(daily, t.decimals) != null) {
                    editing = false; vm.setTokenLimit(t, instant, daily)
                }
                TvButton("Cancel", Modifier.weight(1f), Btn.Ghost, height = 46.dp) { editing = false }
            }
        } else if (w.canAddLimit(Asset.Ft(t.contract))) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(if (limit == null) "Make instant" else "Change", Modifier.weight(1f), Btn.Secondary, height = 46.dp, enabled = vm.busy == null && !w.frozen) { editing = true }
                if (limit != null) TvButton("Remove", Modifier.weight(1f), Btn.Ghost, height = 46.dp, enabled = vm.busy == null && !w.frozen) { vm.removeTokenLimit(t) }
            }
        }
    }
}
