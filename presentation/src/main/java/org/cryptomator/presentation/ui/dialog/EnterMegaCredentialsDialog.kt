package org.cryptomator.presentation.ui.dialog

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import org.cryptomator.generator.Dialog
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.DialogEnterMegaCredentialsBinding

@Dialog
class EnterMegaCredentialsDialog : BaseDialog<EnterMegaCredentialsDialog.Callback, DialogEnterMegaCredentialsBinding>(DialogEnterMegaCredentialsBinding::inflate) {

	interface Callback {
		fun onMegaCredentialsEntered(email: String, password: String, twoFactorCode: String)
	}

	override fun onStart() {
		super.onStart()
		val dialog = dialog as AlertDialog?
		dialog?.let {
			it.getButton(android.app.Dialog.BUTTON_POSITIVE).setOnClickListener {
				val email = binding.etMegaEmail.text?.toString()?.trim() ?: ""
				val password = binding.etMegaPassword.text?.toString() ?: ""
				val twoFactorCode = binding.etMega2fa.text?.toString()?.trim() ?: ""

				if (email.isEmpty()) {
					binding.tilMegaEmail.error = getString(R.string.screen_webdav_settings_msg_username_must_not_be_empty)
					return@setOnClickListener
				}
				if (password.isEmpty()) {
					binding.tilMegaPassword.error = getString(R.string.screen_webdav_settings_msg_password_must_not_be_empty)
					return@setOnClickListener
				}

				callback?.onMegaCredentialsEntered(email, password, twoFactorCode)
				dismiss()
			}
			it.setCanceledOnTouchOutside(false)
			binding.etMegaEmail.requestFocus()
		}
	}

	override fun setupDialog(builder: AlertDialog.Builder): android.app.Dialog {
		return builder.setTitle(R.string.dialog_enter_mega_title)
			.setPositiveButton(R.string.dialog_enter_mega_positive_button) { _: DialogInterface, _: Int -> }
			.setNegativeButton(R.string.dialog_enter_mega_negative_button) { _: DialogInterface, _: Int -> }
			.create()
	}

	override fun setupView() {
		dialog?.let { showKeyboard(it) }
	}

	companion object {
		fun newInstance(): EnterMegaCredentialsDialog = EnterMegaCredentialsDialog()
	}
}
