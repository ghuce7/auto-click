package com.personal.autoclicker.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.personal.autoclicker.model.Preset

class PresetStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<Preset>>() {}.type

    @Synchronized
    fun getAll(): List<Preset> {
        val json = prefs.getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val parsed: List<Preset>? = gson.fromJson(json, listType)
            parsed.orEmpty().filter { !it.name.isNullOrBlank() }
        } catch (e: JsonSyntaxException) {
            emptyList()
        }
    }

    @Synchronized
    fun save(preset: Preset) {
        val cleanName = preset.name.trim()
        val updated = getAll()
            .filterNot { it.name.equals(cleanName, ignoreCase = true) }
            .toMutableList()
        updated.add(preset.copy(name = cleanName))
        persist(updated)
    }

    @Synchronized
    fun delete(name: String) {
        persist(getAll().filterNot { it.name == name })
    }

    @Synchronized
    fun rename(oldName: String, newName: String): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return false
        val current = getAll()
        val target = current.firstOrNull { it.name == oldName } ?: return false
        val clash = current.any {
            it.name != oldName && it.name.equals(cleanName, ignoreCase = true)
        }
        if (clash) return false
        val updated = current.map { if (it === target) it.copy(name = cleanName) else it }
        persist(updated)
        return true
    }

    private fun persist(presets: List<Preset>) {
        prefs.edit().putString(KEY_PRESETS, gson.toJson(presets, listType)).apply()
    }

    companion object {
        private const val PREFS_NAME = "auto_clicker_presets"
        private const val KEY_PRESETS = "presets_json"
    }
}
