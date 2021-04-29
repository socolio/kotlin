/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.fir.caches

import org.jetbrains.kotlin.fir.caches.*

object FirThreadSafeCachesFactory : FirCachesFactory() {
    override fun <K : Any, V, CONTEXT> createCache(provider: FirCacheValueProvider<K, V, CONTEXT>): FirCache<K, V, CONTEXT> =
        FirThreadSafeCache(provider)


    override fun <K : Any, V, CONTEXT, DATA> createCacheWithPostCompute(
        provider: FirCacheValueProviderWithPostCompute<K, V, CONTEXT, DATA>
    ): FirCache<K, V, CONTEXT> =
        FirThreadSafeCacheWithPostCompute(provider)
}

