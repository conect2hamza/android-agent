package com.personal.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.personal.assistant.AppContainer
import com.personal.assistant.core.model.Message
import com.personal.assistant.core.model.Sender
import com.personal.assistant.ui.containerViewModel
import java.time.format.DateTimeFormatter

private val MESSAGE_TIME_12 = DateTimeFormatter.ofPattern("h:mm a")
private val MESSAGE_TIME_24 = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The primary screen. Everything the assistant can do is reachable from a sentence typed here, and the
 * quick suggestions exist so a first-time user can see what "a sentence" is supposed to look like
 * without reading documentation.
 */
@Composable
fun ChatScreen(container: AppContainer, use24HourClock: Boolean) {
    val viewModel = containerViewModel(container) { ChatViewModel(it) }
    val messages by viewModel.messages.collectAsState()
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            // The message flow is newest-first, so the list is reversed rather than re-sorted.
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item { EmptyChatHint(onPick = { input = it }) }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message, use24HourClock)
            }
        }

        if (state.candidates.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.candidates.take(3).forEach { task ->
                    AssistChip(
                        onClick = { viewModel.chooseCandidate(task) },
                        label = { Text(task.title, maxLines = 1) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tomorrow at 4 PM website work") },
                maxLines = 4,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        viewModel.send(input)
                        input = ""
                    },
                ),
            )
            Spacer(Modifier.padding(4.dp))
            Button(
                onClick = {
                    viewModel.send(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !state.sending,
            ) {
                if (state.sending) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, use24HourClock: Boolean) {
    val fromUser = message.sender == Sender.USER
    val background = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.primary
        Sender.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        Sender.SYSTEM -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (message.sender) {
        Sender.USER -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = message.content, style = MaterialTheme.typography.bodyLarge, color = foreground)
            Text(
                text = message.timestamp.format(if (use24HourClock) MESSAGE_TIME_24 else MESSAGE_TIME_12),
                style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EmptyChatHint(onPick: (String) -> Unit) {
    val examples = listOf(
        "Tomorrow at 10 AM work on the website",
        "Remind me at 5 PM to call Ahmed",
        "Kal 4 baje website ka kaam",
        "Every Monday at 9 AM AI project",
        "What do I have tomorrow?",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
        Text("Tell me what you're planning.", style = MaterialTheme.typography.titleMedium)
        Text(
            "English, Urdu or Roman Urdu -- all handled on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        examples.forEach { example ->
            Box(modifier = Modifier.padding(vertical = 3.dp)) {
                AssistChip(onClick = { onPick(example) }, label = { Text(example) })
            }
        }
    }
}
