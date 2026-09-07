package com.example.opendash.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallInfoProvider {
    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall = _incomingCall.asStateFlow()

    fun update(call: IncomingCall?) {
        _incomingCall.value = call
    }
}
