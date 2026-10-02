package com.findupto.voicepos

/** Fallback used by the voice command collector before PosApp's local payment handler is declared. */
@Suppress("UNUSED_PARAMETER")
fun pay(order: PendingSale) = Unit
