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

package org.tfv.deskflow.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import arrow.core.raise.catch
import io.github.oshai.kotlinlogging.Level
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.tfv.deskflow.BuildConfig
import org.tfv.deskflow.client.Client
import org.tfv.deskflow.client.ClientEventBus
import org.tfv.deskflow.client.events.ClientEvent
import org.tfv.deskflow.client.events.ConnectionEvent
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.events.MouseEvent
import org.tfv.deskflow.client.events.ScreenEvent
import org.tfv.deskflow.client.ext.toDebugString
import org.tfv.deskflow.client.manager.ClipboardSendManager
import org.tfv.deskflow.client.models.ClipboardData
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.data.aidl.ConnectionState
import org.tfv.deskflow.data.aidl.IConnectionService
import org.tfv.deskflow.data.aidl.IConnectionServiceCallback
import org.tfv.deskflow.data.aidl.Result
import org.tfv.deskflow.data.aidl.ScreenState
import org.tfv.deskflow.data.appPrefsStore
import org.tfv.deskflow.data.models.AppPrefs
import org.tfv.deskflow.data.models.AppPrefsKt.ScreenConfigKt.serverConfig
import org.tfv.deskflow.data.models.AppPrefsKt.loggingConfig
import org.tfv.deskflow.data.models.AppPrefsKt.screenConfig
import org.tfv.deskflow.data.models.copy
import org.tfv.deskflow.ext.copy
import org.tfv.deskflow.ext.toServerTarget
import org.tfv.deskflow.logging.AndroidForwardingLogger
import org.tfv.deskflow.logging.LogRecordEvent

class ConnectionService : Service() {
  companion object {
    private val log = KLoggingManager.forwardingLogger<ConnectionService>()
  }

  private var connectionStateUpdateJob: Job? = null

  private val serviceScope =
    CoroutineScope(Dispatchers.Default + SupervisorJob())

  private val broadcastLock = Any()
  private var broadcastThread: Thread? = null
  private val broadcastThreadFactory = ThreadFactory { r ->
    synchronized(broadcastLock) {
      if (broadcastThread == null)
        broadcastThread = Thread(r, "BroadcastEventLoopThread")

      return@ThreadFactory broadcastThread
    }
  }
  private val broadcastExecutor =
    Executors.newSingleThreadExecutor(broadcastThreadFactory)

  private val client = Client()

  /** The connection state model */
  private lateinit var connectionStateModel: ConnectionStateModel

  private val connectionCallbacks =
    RemoteCallbackList<IConnectionServiceCallback>()

  private val binder =
    object : IConnectionService.Stub() {

      override fun setClipboardData(bundle: Bundle): Result {
        return catch({
          val clipboardData = bundle.getSerializable("clipboardData",ClipboardData::class.java)
          require(clipboardData != null) {
            "clipboardData was not valid in clipboard data bundle"
          }
          client.sendClipboard(clipboardData)
          return Result().apply {
            ok = true
            message = ""
          }
        }) { err: Throwable ->
          log.error(err) { "Failed to set clipboard data" }
          return Result().apply {
            ok = false
            message = err.message
          }
        }

      }

      override fun setEnabled(enabled: Boolean): Result {
        connectionStateModel.updateState { it.copy(isEnabled = enabled) }

        return Result().apply {
          ok = true
          message = ""
        }
      }

      override fun setCaptureKeyMode(enabled: Boolean): Result {
        connectionStateModel.updateState {
          it.copy(isCaptureKeyModeEnabled = enabled)
        }
        return Result().apply {
          ok = true
          message = ""
        }
      }

      override fun setLogForwardingLevel(levelName: String?): Result {
        val result =
          Result().apply {
            ok = false
            message = ""
          }
        if (levelName != null) {
          catch({
            val level = Level.valueOf(levelName)
            runBlocking {
              appPrefsStore.updateData { appPrefs ->
                appPrefs.copy {
                  loggingConfig {
                    forwardingLevel = level.name
                    forwardingEnabled = true
                  }
                }
              }
            }
            AndroidForwardingLogger.forwardingLevel = level
            result.ok = true
          }) { err: Throwable ->
            log.error(err) { "Invalid log level: $levelName" }
            result.message = err.message
          }
        } else {
          result.message = "levelName is null"
        }

        return result
      }

      override fun registerCallback(callback: IConnectionServiceCallback?) {
        if (callback != null) connectionCallbacks.register(callback)
      }

      override fun unregisterCallback(callback: IConnectionServiceCallback?) {
        if (callback != null) connectionCallbacks.unregister(callback)
      }

      override fun updateScreenState(screenState: ScreenState?): Result {
        val res = Result()
        if (screenState == null)
          return res.apply {
            ok = false
            message = "screenState is null"
          }
        runBlocking {
          appPrefsStore.updateData { appPrefs ->
            return@updateData appPrefs
              .toBuilder()
              .apply {
                screen = screenConfig {
                  name = screenState.name
                  server = serverConfig {
                    address = screenState.server.address
                    port = screenState.server.port
                    useTls = screenState.server.useTls
                  }
                }
              }
              .build()
          }
        }

        return res.apply { ok = true }
      }

      override fun getState(): ConnectionState {
        return runBlocking {
          return@runBlocking connectionStateModel.state
            .stateIn(serviceScope)
            .value
        }
      }
    }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  /** Event handler for `ClientEventBus` events. */
  private fun onClientEvent(event: ClientEvent) {
    when (event) {
      is ScreenEvent.AckReceived -> {
        connectionStateModel.updateState { it.copy(ackReceived = true) }
      }
      is ScreenEvent.Enter -> {
        connectionStateModel.updateState {
          it.copy(screen = it.screen.copy(isActive = true))
        }
      }
      is ScreenEvent.Leave -> {
        connectionStateModel.updateState {
          it.copy(screen = it.screen.copy(isActive = false))
        }
      }
      is ConnectionEvent -> {
        val connected = event is ConnectionEvent.Connected
        log.debug { "ConnectionEvent(connected=$connected)" }
        connectionStateModel.updateState {
          it.copy(
            isConnected = connected,
            ackReceived = if (connected) it.ackReceived else false,
          )
        }
      }
      is LogRecordEvent,
      is ScreenEvent.SetClipboard,
      is MouseEvent,
      is KeyboardEvent -> {
        val bundle =
          Bundle().apply {
            putString("type", event.javaClass.name)
            putSerializable("message", event)
          }

        sendToClients { onMessage(bundle) }
      }
    }
  }

  /** Check if the client is enabled and update if necessary. */
  private fun checkEnabled() {
    val state = connectionStateModel.state.value

    if (state.isEnabled != client.isEnabled) {
      log.info {
        "ConnectionService setting Client.isEnabled=${state.isEnabled}"
      }
      client.setEnabled(state.isEnabled)
    }
  }

  /** Check if the client is connected and update if necessary. */
  private suspend fun connectionStateUpdateJobRunnable() {
    connectionStateModel.state.collect { state ->
      log.info { "CHANGED: ConnectionState(${state.toDebugString()})" }

      checkEnabled()
      sendToClients { onStateChanged(state) }

      val screen = state.screen
      client.setTarget(screen.toServerTarget())
    }
  }

  /**
   * Update the service configuration when preferences
   *
   * This is attached to the `appPrefsStore` flow and will be called
   */
  private val onAppPrefsChanged = FlowCollector { appPrefs: AppPrefs ->
    appPrefs.logging?.let { logging ->
// TODO: Add toggle to settings to enable/disable forwarding
//      logging.forwardingEnabled
      AndroidForwardingLogger.setForwardingEnabled(true)
      val forwardingLevel =
        logging.forwardingLevel.uppercase().let { levelName ->
          Level.entries.find { level -> level.name == levelName } ?: Level.INFO
        }

      AndroidForwardingLogger.forwardingLevel = forwardingLevel

      log.info {
        "AppPrefs changed: forwardingLevel=${AndroidForwardingLogger.forwardingLevel}"
      }
    }
    connectionStateModel.updateScreenFromAppPrefs(appPrefs)
  }

  override fun onCreate() {
    super.onCreate()
    log.debug { "onCreate:${this::class.java.simpleName}" }

//    if (BuildConfig.DEBUG) {
//      android.os.Debug.waitForDebugger()
//    }

    connectionStateModel = ConnectionStateModel(this)
    serviceScope.launch {
      appPrefsStore.data.collect(onAppPrefsChanged)

      val appPrefs = appPrefsStore.data.first()
      connectionStateModel.updateScreenFromAppPrefs(appPrefs)
    }

    ClientEventBus.on(this::onClientEvent)

    connectionStateUpdateJob =
      serviceScope.launch { connectionStateUpdateJobRunnable() }
  }

  /**
   * Send a message to all registered clients.
   *
   * @param fn The function to call on each client.
   */
  private fun sendToClients(fn: IConnectionServiceCallback.() -> Unit) {
    if (broadcastExecutor.isShutdown) return

    broadcastExecutor.submit {
      synchronized(broadcastLock) {
        try {
          val count = connectionCallbacks.beginBroadcast()
          try {
            for (i in 0 until count) {
              try {
                connectionCallbacks.getBroadcastItem(i).fn()
              } catch (e: Exception) {
                log.error(e) { "Error sending to client" }
              }
            }
          } finally {
            connectionCallbacks.finishBroadcast()
          }
        } catch (err: Exception) {
          log.error(err) { "Error sendingToClients" }
        }
      }
    }
  }

  override fun onDestroy() {
    ClientEventBus.off(this::onClientEvent)

    synchronized(broadcastLock) {
      broadcastExecutor.shutdownNow()
      connectionCallbacks.kill()
    }

    client.dispose()

    connectionStateUpdateJob?.cancel()
    connectionStateUpdateJob = null

    serviceScope.cancel()

    super.onDestroy()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }
}
