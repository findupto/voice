package com.findupto.voicepos

/**
 * Fallback used by the voice command collector before the composable's local
 * payment handler is declared. UI payment actions continue to use the local
 * handler in PosApp.
 */
@Suppress("UNUSED_PARAMETER")
private fun pay(order: PendingSale) = Unit
