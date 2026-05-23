// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import io.agents.pokeclaw.R
import java.util.Locale

object AppLocaleManager {

    const val SYSTEM_DEFAULT = ""
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh-CN"

    private const val PREFS_NAME = "pokeclaw_locale"
    private const val KEY_LANGUAGE_TAG = "app_language_tag"

    data class LanguageOption(
        val tag: String,
        val labelRes: Int,
    )

    val OPTIONS = listOf(
        LanguageOption(SYSTEM_DEFAULT, R.string.language_system_default),
        LanguageOption(ENGLISH, R.string.language_english),
        LanguageOption(SIMPLIFIED_CHINESE, R.string.language_simplified_chinese),
    )

    fun wrap(base: Context): Context {
        val tag = getLanguageTag(base)
        if (tag.isBlank()) {
            Locale.setDefault(base.resources.configuration.locales.get(0))
            return base
        }

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ContextWrapper(base.createConfigurationContext(config))
    }

    fun syncDefaultLocale(context: Context) {
        val tag = getLanguageTag(context)
        val locale = if (tag.isBlank()) {
            context.resources.configuration.locales.get(0)
        } else {
            Locale.forLanguageTag(tag)
        }
        Locale.setDefault(locale)
    }

    fun getLanguageTag(context: Context): String {
        return prefs(context).getString(KEY_LANGUAGE_TAG, SYSTEM_DEFAULT) ?: SYSTEM_DEFAULT
    }

    fun setLanguageTag(context: Context, tag: String) {
        val normalized = when (tag) {
            ENGLISH -> ENGLISH
            SIMPLIFIED_CHINESE -> SIMPLIFIED_CHINESE
            else -> SYSTEM_DEFAULT
        }
        prefs(context).edit().putString(KEY_LANGUAGE_TAG, normalized).apply()
    }

    fun selectedOptionIndex(context: Context): Int {
        val tag = getLanguageTag(context)
        return OPTIONS.indexOfFirst { it.tag == tag }.takeIf { it >= 0 } ?: 0
    }

    fun selectedLanguageLabel(context: Context): String {
        val index = selectedOptionIndex(context)
        return context.getString(OPTIONS[index].labelRes)
    }

    fun localeSignature(context: Context): String {
        val explicit = getLanguageTag(context)
        if (explicit.isNotBlank()) return explicit
        val locale = context.resources.configuration.locales.get(0)
        return "system:${locale.toLanguageTag()}"
    }

    fun shouldUseChinese(context: Context): Boolean {
        return when (getLanguageTag(context)) {
            SIMPLIFIED_CHINESE -> true
            ENGLISH -> false
            else -> context.resources.configuration.locales.get(0).language.equals("zh", ignoreCase = true)
        }
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
