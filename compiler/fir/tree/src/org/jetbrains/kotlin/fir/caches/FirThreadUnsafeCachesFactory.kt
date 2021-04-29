/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.caches

object FirThreadUnsafeCachesFactory : FirCachesFactory() {
    override fun <K : Any, V, CONTEXT> createCache(provider: FirCacheValueProvider<K, V, CONTEXT>): FirCache<K, V, CONTEXT> =
        FirThreadUnsafeCache(provider)


    override fun <K : Any, V, CONTEXT, DATA> createCacheWithPostCompute(
        provider: FirCacheValueProviderWithPostCompute<K, V, CONTEXT, DATA>
    ): FirCache<K, V, CONTEXT> =
        FirThreadUnsafeCacheWithPostCompute(provider)
}

@Suppress("UNCHECKED_CAST")
private class FirThreadUnsafeCache<K : Any, V, CONTEXT>(
    private val provider: FirCacheValueProvider<K, V, CONTEXT>
) : FirCache<K, V, CONTEXT>() {
    private val map = NullableMap<K, V>()

    override fun getValue(key: K, context: CONTEXT): V =
        map.getOrElse(key) {
            provider.createValue(key, context).also { createdValue ->
                map[key] = createdValue
            }
        }

    override fun getValueIfComputed(key: K): V? =
        map.getOrElse(key) { null as V }
}


private class FirThreadUnsafeCacheWithPostCompute<K : Any, V, CONTEXT, DATA>(
    private val provider: FirCacheValueProviderWithPostCompute<K, V, CONTEXT, DATA>,
) : FirCache<K, V, CONTEXT>() {
    private val map = NullableMap<K, V>()

    override fun getValue(key: K, context: CONTEXT): V =
        map.getOrElse(key) {
            val (createdValue, data) = provider.createValue(key, context)
            map[key] = createdValue
            provider.postCompute(key, createdValue, data)
            createdValue
        }


    @Suppress("UNCHECKED_CAST")
    override fun getValueIfComputed(key: K): V? =
        map.getOrElse(key) { null as V }
}
