package com.v2ray.ang.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.v2ray.ang.R
import com.v2ray.ang.databinding.DialogModernConfirmBinding

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
}
