package org.tfv.deskflow.components

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.annotation.RawRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tfv.deskflow.R
import org.tfv.deskflow.client.events.KeyboardEvent
import org.tfv.deskflow.client.util.AbstractDisposable
import org.tfv.deskflow.client.util.Keyboard
import org.tfv.deskflow.client.util.Keyboard.findModifierKey
import org.tfv.deskflow.client.util.SingletonThreadExecutor
import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.ext.systemActionsJson
import org.tfv.deskflow.services.keyboard.actions.VirtualKeyboardAction
import org.tfv.deskflow.types.EditorKeyboardAction
import org.tfv.deskflow.types.GlobalKeyboardAction
import org.tfv.deskflow.types.GlobalKeyboardManagerState
import org.tfv.deskflow.types.KeyboardAction
import org.tfv.deskflow.types.ShortcutKey

open class GlobalKeyboardManager(
  private val accessibilityService: AccessibilityService,
  private val ctx: Context = accessibilityService,
) : AbstractDisposable() {

  protected val fileManager = FileManager(ctx)

  protected val executor = SingletonThreadExecutor(javaClass.simpleName)

  protected val editableActionFlow = MutableSharedFlow<GlobalKeyboardAction>()

  val actionFlow: SharedFlow<GlobalKeyboardAction> =
    editableActionFlow.asSharedFlow()

  protected val editableState: MutableStateFlow<GlobalKeyboardManagerState> =
    MutableStateFlow(GlobalKeyboardManagerState())

  val state: StateFlow<GlobalKeyboardManagerState> = editableState.asStateFlow()

  private fun loadSystemActions() {
    editableState.value =
      editableState.value.copy(
        keyboardActions =
          loadKeyboardActions<String, GlobalKeyboardAction>(
            ctx,
            R.raw.global_actions_defaults,
          )
      )
  }

  init {
    loadSystemActions()
  }

  protected fun mutateState(
    mutator:
      (
        state: GlobalKeyboardManagerState, manager: GlobalKeyboardManager,
      ) -> GlobalKeyboardManagerState
  ): GlobalKeyboardManagerState {
    require(executor.isExecutorThread) {
      "mutateState() only works on executor thread"
    }

    val state = editableState.value
    val newState = mutator(editableState.value, this)
    if (state != newState) {
      editableState.value = newState
    }

    return newState
  }

  protected open fun processInternal(event: KeyboardEvent) {
    require(executor.isExecutorThread) {
      "processInternal() only works on executor thread"
    }

    val modKey = findModifierKey(event.id.toInt())
    if (modKey != null) {
      mutateState { state, manager ->
        val isPressed = event.type == KeyboardEvent.Type.Down
        state.copy(
          modifierKeys =
            state.modifierKeys.updateModifierKeys(isPressed, modKey)
        )
      }
      return
    }

    val state = editableState.value
    val shortcut = ShortcutKey(event.id.toInt(), state.modifierKeys)
    val action =
      state.keyboardActions.values.find { it.shortcutKeys.contains(shortcut) }
    if (action == null) {
      log.trace { "No action registered for shortcut (${shortcut.label})" }
      return
    }

    log.info { "Matched shortcut($shortcut) to action($action)" }
    if (action.execute == null) {
      log.debug { "Action($action) does not specify an execute function" }
    } else {
      log.debug { "Executing action($action), shortcut($shortcut) triggered" }
      action.execute.invoke(action)
    }

    log.debug { "Emitting action($action)" }

    runBlocking { editableActionFlow.emit(action) }
  }

  open fun process(event: KeyboardEvent) {
    executor.submit<Unit> { processInternal(event) }
  }

  fun dumpSystemActions() {
    val systemActionsJson = accessibilityService.systemActionsJson()

    val jsonFile = "system_actions.json"
    log.debug { "Writing system actions to file: $jsonFile" }
    fileManager.writeJsonJob(jsonFile, systemActionsJson).invokeOnCompletion {
      error ->
      when (error) {
        null -> log.debug { "Wrote $jsonFile successfully" }
        else ->
          log.error(error) { "Failed to write $jsonFile: ${error.message}" }
      }
    }
  }

  override fun onDispose() {
    executor.shutdown()
  }

  companion object {
    internal val log = KLoggingManager.logger(GlobalKeyboardManager::class)

    internal inline fun <
      reified K,
      reified T : KeyboardAction<String>,
    > loadKeyboardActions(ctx: Context, @RawRes rawFileResId: Int): Map<K, T> {
      val fileManager = FileManager(ctx)
      val actionMap = mutableMapOf<K, T>()
      val jsonActionDefaults =
        fileManager.readJsonFromRawResource<JsonArray>(rawFileResId)
      for (actionIdx in 0..<jsonActionDefaults.size) {
        val jsonAction = jsonActionDefaults[actionIdx].jsonObject

        val actionId =
          jsonAction["actionId"]!!.jsonPrimitive.intOrNull
            ?: throw Error("Missing actionId")
        val label =
          jsonAction["label"]!!.jsonPrimitive.content.apply {
            require(isNotBlank()) { "Missing label" }
          }

        val specialKey =
          jsonAction["specialKey"]?.jsonPrimitive?.content?.let { specialKeyName
            ->
            Keyboard.findSpecialKey(specialKeyName)
          }
        val ignoreIME =
          jsonAction["ignoreIME"]?.jsonPrimitive?.booleanOrNull ?: false

        val jsonShortcuts = jsonAction["defaultShortcutKeys"]?.jsonArray ?: JsonArray(emptyList())
        val defaultShortcutKeys = mutableListOf<ShortcutKey>()
        for (shortcutIdx in 0..<jsonShortcuts.size) {
          val shortcutElem = jsonShortcuts[shortcutIdx]
          val shortcutStr =
            shortcutElem.jsonPrimitive.content // .jsonPrimitive.content
          log.debug { "Parsing shortcut: $shortcutStr" }

          val shortcut = ShortcutKey.parseShortcut(shortcutStr)
          defaultShortcutKeys.add(shortcut)
        }

        when {
          T::class == GlobalKeyboardAction::class -> {
            actionMap[actionId.toString() as K] =
              GlobalKeyboardAction(
                id = actionId.toString(),
                actionId = actionId,
                label = label,
                shortcutKeys = defaultShortcutKeys.toList(),
                defaultShortcutKeys = defaultShortcutKeys,
                ignoreIME = ignoreIME,
                execute = {},
              )
                as T
          }
          T::class == EditorKeyboardAction::class -> {
            val actionType = VirtualKeyboardAction.fromActionId(actionId)
            actionMap[actionType as K] =
              EditorKeyboardAction(
                id = actionId.toString(),
                actionId = actionId,
                label = label,
                shortcutKeys = defaultShortcutKeys.toList(),
                defaultShortcutKeys = defaultShortcutKeys,
                specialKey = specialKey,
                execute = {},
              )
                as T
          }
          else -> throw Error("Unsupported action type: ${T::class}")
        }

        // TODO: Load custom shortcuts from AppPrefs here and
        //   replace shortcutKeys if a custom shortcut key has
        //   been set

      }

      return actionMap
    }
  }
}
