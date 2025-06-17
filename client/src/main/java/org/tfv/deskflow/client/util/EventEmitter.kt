/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.client.events

open class EventEmitter<Type, Payload> {
    private val listeners = mutableMapOf<Type, MutableList<(Payload?) -> Unit>>()

    /** Register a listener for [event]. */
    fun on(event: Type, listener: (payload: Payload?) -> Unit) {
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
    }

    /** Register a one-time listener for [event]. */
    fun once(event: Type, listener: (payload: Payload?) -> Unit) {
        val wrapper = createOnceWrapper(event, listener)
        on(event, wrapper)
    }

    private fun createOnceWrapper(event: Type, listener: (payload: Payload?) -> Unit): (payload: Payload?) -> Unit {
        var wrapper: ((payload: Payload?) -> Unit)? = null
        wrapper = { payload ->
            listener(payload)
            off(event, wrapper!!)
        }

        return wrapper
    }

    /** Remove a specific [listener] for [event]. */
    fun off(event: Type, listener: (payload: Payload?) -> Unit) {
        listeners[event]?.run {
            remove(listener)
            if (isEmpty()) listeners.remove(event)
        }
    }

    /** Emit [event], passing along an optional [payload]. */
    fun emit(event: Type, payload: Payload? = null) {
        listeners[event]?.toList()?.forEach { it(payload) }
    }

    /** Remove all listeners for [event], or all events if [event] is null. */
    fun clear(event: Type? = null) {
        if (event != null) listeners.remove(event)
        else listeners.clear()
    }
}
