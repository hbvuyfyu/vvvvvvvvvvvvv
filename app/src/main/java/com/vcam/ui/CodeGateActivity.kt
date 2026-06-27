package com.vcam.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vcam.databinding.ActivityCodeGateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * CodeGateActivity — first screen.
 *
 * Logic:
 *  1. Check SharedPrefs for a previously-entered code.
 *  2. If found → verify the code is still in the remote `allcod` file.
 *       OK  → go to MainActivity
 *       MISSING → revoked: clear prefs, show message
 *       ERROR → allow offline pass-through (don't lock user out due to network)
 *  3. No stored code → show entry form.
 *  4. On form submit → fetch `allcod`, check code, store if valid, then proceed.
 */
class CodeGateActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME   = "vcam_gate"
        private const val KEY_CODE     = "access_code"

        /** Raw URL of the code list in the repo */
        private const val ALLCOD_URL =
            "https://raw.githubusercontent.com/hbvuyfyu/vvvvvvvvvvvvv/main/allcod"

        /** Fetch the allcod file; returns null on network error */
        suspend fun fetchAllCod(): String? = withContext(Dispatchers.IO) {
            try {
                URL(ALLCOD_URL).openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout    = 8_000
                }.getInputStream().bufferedReader().readText()
            } catch (e: Exception) { null }
        }

        /** True if [code] is a non-blank line in [content] */
        fun codeExistsIn(content: String, code: String): Boolean {
            val trimmed = code.trim()
            if (trimmed.isEmpty()) return false
            return content.lines().any { it.trim() == trimmed }
        }
    }

    private lateinit var binding: ActivityCodeGateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodeGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val stored = getStoredCode()

        if (stored != null) {
            // Returning user — verify code is still live
            showChecking(true)
            lifecycleScope.launch { verifyExistingCode(stored) }
        } else {
            showChecking(false)
        }

        setupInput()
    }

    // ── UI state ──────────────────────────────────────────────────────

    private fun showChecking(loading: Boolean) {
        binding.progressGate.visibility  = if (loading) View.VISIBLE else View.GONE
        binding.layoutEntry.visibility   = if (loading) View.GONE    else View.VISIBLE
        binding.tvStatus.text            = if (loading) getString(com.vcam.R.string.code_checking) else ""
    }

    private fun showEntry(statusMsg: String = "") {
        binding.progressGate.visibility = View.GONE
        binding.layoutEntry.visibility  = View.VISIBLE
        binding.tvStatus.text           = statusMsg
    }

    // ── Input setup ───────────────────────────────────────────────────

    private fun setupInput() {
        binding.etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                handleSubmit(); true
            } else false
        }
        binding.btnEnter.setOnClickListener { handleSubmit() }
    }

    // ── Existing user flow ────────────────────────────────────────────

    private suspend fun verifyExistingCode(stored: String) {
        val content = fetchAllCod()
        when {
            content == null -> {
                // Network error → allow offline access
                openMain()
            }
            codeExistsIn(content, stored) -> {
                openMain()
            }
            else -> {
                // Code was deleted from repo
                clearStoredCode()
                withContext(Dispatchers.Main) {
                    showEntry(getString(com.vcam.R.string.code_revoked))
                }
            }
        }
    }

    // ── New user flow ─────────────────────────────────────────────────

    private fun handleSubmit() {
        val code = binding.etCode.text?.toString()?.trim() ?: ""
        if (code.isEmpty()) return

        binding.btnEnter.isEnabled      = false
        binding.progressGate.visibility = View.VISIBLE
        binding.tvStatus.text           = getString(com.vcam.R.string.code_checking)
        binding.layoutEntry.visibility  = View.GONE

        lifecycleScope.launch {
            val content = fetchAllCod()
            withContext(Dispatchers.Main) {
                binding.progressGate.visibility = View.GONE
                binding.layoutEntry.visibility  = View.VISIBLE
                binding.btnEnter.isEnabled      = true

                when {
                    content == null -> {
                        binding.tvStatus.text = getString(com.vcam.R.string.no_internet)
                    }
                    codeExistsIn(content, code) -> {
                        storeCode(code)
                        openMain()
                    }
                    else -> {
                        binding.tvStatus.text = getString(com.vcam.R.string.code_invalid)
                        binding.etCode.text?.clear()
                    }
                }
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── SharedPreferences ─────────────────────────────────────────────

    private fun getStoredCode(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CODE, null)
    }

    private fun storeCode(code: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CODE, code).apply()
    }

    private fun clearStoredCode() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_CODE).apply()
    }
}
