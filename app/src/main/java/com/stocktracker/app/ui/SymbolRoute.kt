package com.stocktracker.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.di.ServiceLocator

/**
 * Turn a bare symbol into something the detail route can open.
 *
 * Several surfaces name a symbol and nothing else — an AI verdict row ("TRIM UNH"), a rebalance move,
 * a catalyst-calendar entry. Until now those were inert text, so acting on one meant memorising the
 * ticker and going to look for it. The route to open them has always existed; what was missing was
 * the type and the CoinGecko id that [Asset] needs, which only the watchlist knows.
 *
 * So: resolve against the watchlist when the name is tracked, and fall back to a plain STOCK asset
 * when it is not. The fallback is deliberate rather than a failure case — the whole point of the
 * parameterised detail route is that it builds its asset from the URL, so a scan result or an
 * analyst suggestion the user has never added still opens.
 *
 * The backend speaks Yahoo's form for crypto (BTC-USD), which is not what the watchlist stores, so
 * that suffix is stripped before matching. Stripping it from an equity would be wrong, but no US
 * equity ticker ends in "-USD".
 */
@Composable
fun rememberOpenSymbol(onOpenDetail: (Asset) -> Unit): (String) -> Unit {
    val watchlist by ServiceLocator.watchlistStore.watchlist.collectAsState(initial = emptyList())
    return remember(watchlist, onOpenDetail) {
        { raw ->
            val symbol = raw.trim().removeSuffix("-USD").removeSuffix("-usd")
            val known = watchlist.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
            onOpenDetail(known ?: Asset(symbol.uppercase(), AssetType.STOCK, symbol.uppercase()))
        }
    }
}
