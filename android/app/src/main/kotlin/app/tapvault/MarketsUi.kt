package app.tapvault

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tapvault.core.ChartRange
import app.tapvault.core.IntentToken
import app.tapvault.core.KnownTokens
import app.tapvault.core.MarketCoin
import app.tapvault.core.Markets
import app.tapvault.core.Network
import app.tapvault.core.PriceSeries
import app.tapvault.core.TrendingToken

/**
 * One row of the market, from CoinGecko ([coinId]) or from a pool trending
 * on NEAR ([pool] + [token]). [nearContract] is the token on NEAR, when it
 * has one; [assets] are what NEAR Intents trades for it.
 */
class MarketItem(
    val key: String,
    val symbol: String,
    val name: String,
    val price: Double?,
    val change24h: Double?,
    val sparkline: List<Double>,
    val coinId: String?,
    val pool: String?,
    val token: String?,
    val nearContract: String?,
    val assets: List<IntentToken>,
    val marketCap: Double? = null,
    val volume24h: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val ath: Double? = null,
    val liquidity: Double? = null,
    val createdAt: Long? = null,
    val rank: Int? = null,
    val image: String? = null,
) {
    val verified get() = nearContract != null && KnownTokens.find(Network.MAINNET, nearContract) != null
    val meme get() = coinId in Markets.MEME_IDS || Markets.isMemeContract(nearContract)
    /** Something Tapvault can swap to (on NEAR) or bridge to (a chain it knows addresses for). */
    val tradeTarget: IntentToken? get() = assets.firstOrNull { it.onNear && it.nearContract != null }
        ?: assets.firstOrNull { !it.onNear && app.tapvault.core.ChainAddress.chain(it.blockchain) != null }
    val tradable get() = tradeTarget != null
    /** Only on NEAR, and not on Tapvault's list: its name is whatever its creator chose. */
    val unverifiedOnNear get() = nearContract != null && !verified && assets.none { !it.onNear }

    companion object {
        fun of(c: MarketCoin, assets: List<IntentToken>) = MarketItem(
            key = "cg:" + c.id, symbol = c.symbol, name = c.name, price = c.price, change24h = c.change24h, sparkline = c.sparkline,
            coinId = c.id, pool = null, token = null, nearContract = assets.firstOrNull { it.onNear }?.nearContract, assets = assets,
            marketCap = c.marketCap, volume24h = c.volume24h, high24h = c.high24h, low24h = c.low24h, ath = c.ath, rank = c.rank, image = c.image,
        )

        fun of(t: TrendingToken, asset: IntentToken?, image: String? = null) = MarketItem(
            key = "gt:" + t.address, symbol = t.symbol, name = t.poolName, price = t.price, change24h = t.change24h, sparkline = emptyList(),
            coinId = null, pool = t.pool, token = t.address, nearContract = t.address, assets = listOfNotNull(asset),
            marketCap = t.marketCap, volume24h = t.volume24h, liquidity = t.liquidity, createdAt = t.createdAt, image = image,
        )
    }
}

private enum class MarketList(val label: String) { Top("Top"), Near("On NEAR"), Memes("Memes"), Trending("Trending"), Gainers("Gainers"), Losers("Losers") }

private fun changeColor(v: Double?) = when {
    v == null || kotlin.math.abs(v) < 0.005 -> Tv.Text2
    v >= 0 -> Tv.Positive
    else -> Tv.Danger
}

/**
 * Markets: cards for the biggest coins, then every coin NEAR Intents
 * trades (top, on NEAR, memes, movers) and what trends on NEAR. Tapping a
 * coin opens its chart and stats; [onTrade] opens Swap with it.
 */
@Composable
fun MarketsPanel(vm: AppViewModel, onTrade: (IntentToken) -> Unit) {
    LaunchedEffect(Unit) { vm.refreshMarkets() }
    var list by remember { mutableStateOf(MarketList.Top) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<MarketItem?>(null) }
    // NEAR trades as wNEAR; wNEAR itself isn't listed beside it
    val coins = vm.marketCoins.filter { it.id != "wrapped-near" }.map { MarketItem.of(it, vm.assetsOf(if (it.id == "near") "wrapped-near" else it.id)) }
    val featured = coins.filter { it.tradable && it.coinId !in STABLES }.let { l -> l.filter { it.coinId == "near" } + l.filter { it.coinId != "near" } }.take(8)

    if (featured.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            featured.forEachIndexed { i, c -> CoinCard(c, i) { open = c } }
        }
        Spacer(Modifier.height(20.dp))
    } else if (vm.marketsLoading) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { Shimmer(Modifier.size(158.dp, 184.dp)) } }
        Spacer(Modifier.height(20.dp))
    }

    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MarketList.entries.forEach { l -> Chip(l.label, selected = l == list) { list = l } }
    }
    Spacer(Modifier.height(12.dp))
    TvField("", query, { query = it }, placeholder = "Search coins")
    Spacer(Modifier.height(12.dp))

    val tradable = coins.filter { it.tradable }
    val rows: List<MarketItem> = when (list) {
        MarketList.Top -> tradable
        MarketList.Near -> tradable.filter { it.assets.any { a -> a.onNear } }
        MarketList.Memes -> tradable.filter { it.meme } + vm.trending.filter { Markets.isMemeContract(it.address) && tradable.none { c -> c.nearContract == it.address } }.map { MarketItem.of(it, vm.assetOnNear(it.address), vm.iconFor(vm.assetFor(it.address))) }
        // a trending token 1Click knows (often a bridged asset like eth.omft.near) shows as its coin; the rest as their pool
        MarketList.Trending -> vm.trending.map { t ->
            val a = vm.assetFor(t.address)
            a?.coingeckoId?.let { id -> coins.firstOrNull { it.coinId == id } }
                ?: coins.firstOrNull { it.nearContract == t.address }?.let { c -> MarketItem.of(t, vm.assetOnNear(t.address)).withCoin(c) }
                ?: MarketItem.of(t, a, vm.iconFor(a))
        }.distinctBy { it.key }
        MarketList.Gainers -> tradable.filter { (it.change24h ?: 0.0) > 0 }.sortedByDescending { it.change24h }
        MarketList.Losers -> tradable.filter { (it.change24h ?: 0.0) < 0 }.sortedBy { it.change24h }
    }.filter { q -> query.isBlank() || q.symbol.contains(query.trim(), true) || q.name.contains(query.trim(), true) }

    when {
        rows.isEmpty() && vm.marketsLoading -> Group { repeat(6) { i -> if (i > 0) GroupDivider(); ShimmerRow() } }
        rows.isEmpty() && vm.marketError != null -> Notice(Ic.Offline, "Markets aren't loading", vm.marketError, Tv.Warn) {
            TvButton("Try again", Modifier.fillMaxWidth(), Btn.Secondary, height = 48.dp) { vm.refreshMarkets(force = true) }
        }
        rows.isEmpty() -> Caption(if (query.isNotBlank()) "No coin matches \"${query.trim()}\"." else "Nothing here right now.", Modifier.padding(start = 4.dp), color = Tv.Text3)
        else -> {
            Group {
                rows.take(80).forEachIndexed { i, it ->
                    if (i > 0) GroupDivider()
                    MarketRow(it) { open = it }
                }
            }
        }
    }
    open?.let { item ->
        MarketSheet(vm, item, { open = null }) { a -> open = null; onTrade(a) }
    }
}

/** A trending entry that CoinGecko also lists borrows its market data (and so its chart). */
private fun MarketItem.withCoin(c: MarketItem) = MarketItem(
    key, symbol, c.name, price ?: c.price, change24h ?: c.change24h, c.sparkline, c.coinId, pool, token, nearContract, assets.ifEmpty { c.assets },
    c.marketCap ?: marketCap, volume24h, c.high24h, c.low24h, c.ath, liquidity, createdAt, c.rank, c.image ?: image,
)

private val STABLES = setOf("usd-coin", "tether", "dai", "frax", "ethena-usde", "usd1-wlfi", "global-dollar", "usdt0", "xdai")

/** A coin as a card: logo, symbol, a 7-day line, name, price and today's change. */
@Composable
private fun CoinCard(c: MarketItem, index: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val tints = listOf(Color(0xFF6E5BB0), Color(0xFFB06E8C), Color(0xFF5B7BB0), Color(0xFF8C6EB0))
    val tint = tints[index % tints.size]
    Column(
        Modifier.size(158.dp, 184.dp).clip(shape).background(Tv.Surface)
            .background(Brush.linearGradient(listOf(Color.Transparent, tint.copy(alpha = 0.42f)), start = Offset(0f, 0f), end = Offset(500f, 520f)))
            .border(1.dp, Tv.Stroke, shape).clickable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            TokenMark(c.symbol, 40.dp, c.image)
            Spacer(Modifier.weight(1f))
            Text(c.symbol, style = Type.CaptionStrong, color = Tv.Text, maxLines = 1, modifier = Modifier.border(1.dp, Tv.StrokeStrong, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Spacer(Modifier.weight(1f))
        if (c.sparkline.size > 1) Sparkline(c.sparkline, Modifier.fillMaxWidth().height(30.dp), changeColor(c.change24h).copy(alpha = 0.9f))
        Spacer(Modifier.height(10.dp))
        Text(c.name, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Markets.price(c.price), style = Type.Caption, color = Tv.Text2, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(Markets.percent(c.change24h), style = Type.CaptionStrong, color = changeColor(c.change24h), maxLines = 1)
        }
    }
}

@Composable
private fun MarketRow(it: MarketItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenMark(it.symbol, 38.dp, it.image)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(it.symbol, style = Type.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(it.name, style = Type.Caption, color = Tv.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (it.sparkline.size > 1) {
            Spacer(Modifier.width(8.dp))
            Sparkline(it.sparkline, Modifier.size(64.dp, 26.dp), changeColor((it.sparkline.last() - it.sparkline.first()).takeIf { _ -> it.sparkline.first() != 0.0 }))
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(92.dp)) {
            Text(Markets.price(it.price), style = Type.BodyStrong, maxLines = 1)
            Text(Markets.percent(it.change24h), style = Type.Caption, color = changeColor(it.change24h), maxLines = 1)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text, style = Type.Overline, color = color,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ShimmerRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Shimmer(Modifier.size(38.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Shimmer(Modifier.size(80.dp, 14.dp)); Spacer(Modifier.height(6.dp)); Shimmer(Modifier.size(130.dp, 12.dp)) }
        Shimmer(Modifier.size(70.dp, 28.dp))
    }
}

/**
 * A coin's page: price, a chart you can scrub, its numbers, and what
 * Tapvault can do with it (swap or bridge through NEAR Intents, buy).
 */
@Composable
private fun MarketSheet(vm: AppViewModel, item: MarketItem, onDismiss: () -> Unit, onTrade: (IntentToken) -> Unit) {
    var range by remember { mutableStateOf(ChartRange.D1) }
    var series by remember { mutableStateOf<PriceSeries?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var scrub by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(item.key, range) {
        series = null; error = null; scrub = null
        try { series = vm.chart(item.coinId, item.pool, item.token, range) } catch (e: Exception) { error = e.message ?: "No chart right now." }
    }
    val s = series
    val at = scrub?.takeIf { s != null && it in s.prices.indices }
    val shownPrice = if (at != null && s != null) s.prices[at] else item.price
    // the change over the range (to the scrubbed point while scrubbing); 24 h from the market before the chart loads
    val change = when {
        s != null && at != null && s.prices.first() != 0.0 -> (s.prices[at] - s.prices.first()) / s.prices.first() * 100
        s?.change != null -> s.change
        else -> item.change24h
    }
    val color = changeColor(change)
    GlassSheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TokenMark(item.symbol, 44.dp, item.image)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.symbol, style = Type.H2, maxLines = 1)
                        if (item.unverifiedOnNear) Tag("Unverified", Tv.Warn)
                    }
                    Caption(item.name + (item.rank?.let { " · #$it" } ?: ""))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(Markets.price(shownPrice), style = Type.Hero)
            Text(
                Markets.percent(change) + "  " + (if (at != null && s != null) chartTime(s.times[at], range) else range.label.let { if (it == "1D") "today" else "past ${it.lowercase()}" }),
                style = Type.CaptionStrong, color = color,
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                when {
                    s != null && s.prices.size > 1 -> PriceChart(s, Modifier.fillMaxWidth().height(200.dp), color, onScrub = { scrub = it })
                    error != null || (s != null && s.prices.size <= 1) -> Caption(error ?: "No trades in this range.", color = Tv.Text3)
                    else -> Shimmer(Modifier.fillMaxWidth().height(200.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ChartRange.entries.forEach { r -> Chip(r.label, selected = r == range) { range = r } }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().surface(20.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                item.marketCap?.let { InfoRow("Market cap", Markets.compact(it)) }
                item.volume24h?.let { InfoRow("24h volume", Markets.compact(it)) }
                if (item.low24h != null && item.high24h != null) InfoRow("24h range", Markets.price(item.low24h) + " – " + Markets.price(item.high24h))
                item.ath?.let { InfoRow("All-time high", Markets.price(it)) }
                item.liquidity?.let { InfoRow("Pool liquidity", Markets.compact(it)) }
                item.createdAt?.let { InfoRow("Pool created", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))) }
                item.nearContract?.let { InfoRow("On NEAR", shortAccount(it, 24)) }
                val chains = item.assets.filter { !it.onNear }.mapNotNull { app.tapvault.core.ChainAddress.chain(it.blockchain)?.name }.distinct()
                if (chains.isNotEmpty()) InfoRow("Bridges to", chains.take(4).joinToString(", ") + if (chains.size > 4) " +${chains.size - 4}" else "")
                InfoRow("Trades through", if (item.tradable) "NEAR Intents" else "—", if (item.tradable) Tv.Text else Tv.Text3)
            }
            Spacer(Modifier.height(18.dp))
            val target = item.tradeTarget
            TvButton(if (target == null) "Not tradable" else "Swap", Modifier.fillMaxWidth(), icon = Ic.Refresh, enabled = target != null) { target?.let(onTrade) }
        }
    }
}

private fun chartTime(ms: Long, r: ChartRange): String {
    val f = when (r) {
        ChartRange.H1, ChartRange.D1 -> "HH:mm"
        ChartRange.W1, ChartRange.M1 -> "d MMM, HH:mm"
        ChartRange.Y1 -> "d MMM yyyy"
    }
    return java.text.SimpleDateFormat(f, java.util.Locale.getDefault()).format(java.util.Date(ms))
}

/**
 * A price line with a soft fill, a dashed line at the starting price and,
 * when [interactive], a finger to scrub along it ([onScrub] gets the index
 * under the finger, null when it lifts).
 */
@Composable
fun PriceChart(series: PriceSeries, modifier: Modifier, color: Color, interactive: Boolean = true, onScrub: (Int?) -> Unit = {}) {
    val pts = series.prices
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val reveal = remember(series) { Animatable(0f) }
    LaunchedEffect(series) { reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    var at by remember(series) { mutableStateOf<Int?>(null) }
    fun indexAt(x: Float, w: Float) = ((x / (w - 8 * density)) * (pts.size - 1)).toInt().coerceIn(0, pts.size - 1)
    val input = if (!interactive) Modifier else Modifier
        .pointerInput(series) {
            detectTapGestures(onPress = { o ->
                at = indexAt(o.x, size.width.toFloat()); onScrub(at)
                tryAwaitRelease()
                at = null; onScrub(null)
            })
        }
        .pointerInput(series) {
            detectHorizontalDragGestures(
                onDragStart = { o -> at = indexAt(o.x, size.width.toFloat()); onScrub(at) },
                onDragEnd = { at = null; onScrub(null) },
                onDragCancel = { at = null; onScrub(null) },
            ) { change, _ -> change.consume(); at = indexAt(change.position.x, size.width.toFloat()); onScrub(at) }
        }
    val guide = Tv.StrokeStrong
    val dotRing = Tv.Sheet
    Canvas(modifier.then(input)) {
        if (pts.size < 2) return@Canvas
        val lo = pts.min()
        val hi = pts.max()
        val span = (hi - lo).takeIf { it > 0 } ?: (hi.takeIf { it != 0.0 } ?: 1.0)
        val top = 8.dp.toPx()
        val h = size.height - top * 2
        // room on the right for the end dot's glow
        val w = size.width - 8.dp.toPx()
        fun x(i: Int) = i.toFloat() / (pts.size - 1) * w
        fun y(v: Double) = top + (h - ((v - lo) / span * h)).toFloat()
        val line = Path().apply { pts.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val fill = Path().apply { addPath(line); lineTo(w, size.height); lineTo(0f, size.height); close() }
        // the starting price
        drawLine(guide, Offset(0f, y(pts.first())), Offset(size.width, y(pts.first())), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
        clipRect(right = size.width * reveal.value) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.24f), Color.Transparent), startY = 0f, endY = size.height))
            drawPath(line, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val i = at
        if (i != null) {
            drawLine(guide, Offset(x(i), 0f), Offset(x(i), size.height), 1.dp.toPx())
            drawCircle(color, 5.dp.toPx(), Offset(x(i), y(pts[i])))
            drawCircle(dotRing, 2.dp.toPx(), Offset(x(i), y(pts[i])))
        } else if (reveal.value >= 1f) {
            val e = Offset(w, y(pts.last()))
            drawCircle(color.copy(alpha = 0.22f), 8.dp.toPx(), e)
            drawCircle(color, 3.5.dp.toPx(), e)
        }
    }
}

/** A small line for a list row: the last 7 days. */
@Composable
fun Sparkline(points: List<Double>, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        // ~168 hourly points: every third is plenty at this size
        val pts = points.filterIndexed { i, _ -> i % 3 == 0 || i == points.size - 1 }
        val lo = pts.min()
        val span = (pts.max() - lo).takeIf { it > 0 } ?: 1.0
        val path = Path()
        pts.forEachIndexed { i, v ->
            val px = i.toFloat() / (pts.size - 1) * size.width
            val py = (size.height - (v - lo) / span * size.height).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
