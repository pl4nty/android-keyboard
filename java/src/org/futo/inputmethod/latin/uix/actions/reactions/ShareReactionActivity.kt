package org.futo.inputmethod.latin.uix.actions.reactions

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R

class ShareReactionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = when(intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            )
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            ).orEmpty()
            else -> emptyList()
        }

        lifecycleScope.launch {
            val added = uris.mapNotNull { ReactionStore.addUri(applicationContext, it) }
            if(added.size == 1) ReactionStore.pendingEdit.value = added.first().file

            val message = if(added.isEmpty()) {
                getString(R.string.action_reactions_share_failed)
            } else {
                resources.getQuantityString(R.plurals.action_reactions_share_saved, added.size, added.size)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
