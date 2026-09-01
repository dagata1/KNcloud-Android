package com.v2ray.ang.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.v2ray.ang.R
import com.v2ray.ang.databinding.DialogModernConfirmBinding
import com.v2ray.ang.databinding.DialogNodeEditBinding
import com.v2ray.ang.dto.ProfileItem

object DialogUtil {

    /**
     * Displays a modern unified confirm dialog styled matching the home card theme.
     */
    fun showConfirmDialog(
        context: Context,
        @DrawableRes iconRes: Int = R.drawable.ic_logout_24dp,
        title: String,
        message: String,
        confirmText: String = context.getString(android.R.string.ok),
        cancelText: String = context.getString(android.R.string.cancel),
        onConfirm: () -> Unit
    ): AlertDialog {
        val binding = DialogModernConfirmBinding.inflate(LayoutInflater.from(context))
        binding.ivDialogIcon.setImageResource(iconRes)
        binding.tvDialogTitle.text = title
        binding.tvDialogMessage.text = message
        binding.btnDialogConfirm.text = confirmText
        binding.btnDialogCancel.text = cancelText

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        binding.btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnDialogConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
        return dialog
    }

    fun showConfirmDialog(
        context: Context,
        @DrawableRes iconRes: Int = R.drawable.ic_logout_24dp,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes confirmTextRes: Int = android.R.string.ok,
        onConfirm: () -> Unit
    ): AlertDialog {
        return showConfirmDialog(
            context = context,
            iconRes = iconRes,
            title = context.getString(titleRes),
            message = context.getString(messageRes),
            confirmText = context.getString(confirmTextRes),
            onConfirm = onConfirm
        )
    }

    /**
     * Displays a styled dialog for editing server address and port in classic mode.
     */
    fun showEditNodeDialog(
        context: Context,
        profile: ProfileItem,
        onConfirm: (address: String, port: String) -> Boolean
    ): AlertDialog {
        val binding = DialogNodeEditBinding.inflate(LayoutInflater.from(context))
        binding.tvNodeName.text = profile.remarks
        val currentServer = profile.server.orEmpty()
        val currentPort = profile.serverPort.orEmpty()

        binding.etServerAddress.setText(currentServer)
        binding.etServerAddress.setSelection(binding.etServerAddress.text?.length ?: 0)

        binding.etServerPort.setText(currentPort)
        binding.etServerPort.setSelection(binding.etServerPort.text?.length ?: 0)

        binding.etServerAddress.doAfterTextChanged {
            binding.tilServerAddress.error = null
        }
        binding.etServerPort.doAfterTextChanged {
            binding.tilServerPort.error = null
        }

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        binding.btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.etServerPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnDialogConfirm.performClick()
                true
            } else {
                false
            }
        }

        binding.btnDialogConfirm.setOnClickListener {
            val address = binding.etServerAddress.text?.toString()?.trim().orEmpty()
            val port = binding.etServerPort.text?.toString()?.trim().orEmpty()

            if (address.isEmpty()) {
                binding.tilServerAddress.error = context.getString(R.string.server_lab_address)
                return@setOnClickListener
            }

            val portInt = Utils.parseInt(port)
            if (portInt <= 0 || portInt > 65535) {
                binding.tilServerPort.error = context.getString(R.string.server_lab_port)
                return@setOnClickListener
            }

            if (onConfirm(address, port)) {
                dialog.dismiss()
            }
        }

        dialog.show()
        return dialog
    }
}
